package com.agentwork.graphmesh.streaming

import com.agentwork.graphmesh.llm.resolveLlmModel

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import com.agentwork.graphmesh.agent.AgentQueryConfig
import com.agentwork.graphmesh.agent.DocumentQueryTool
import com.agentwork.graphmesh.agent.KnowledgeQueryTool
import com.agentwork.graphmesh.agent.ToolGroupRegistry
import com.agentwork.graphmesh.query.docrag.DocumentRagService
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@OptIn(kotlin.time.ExperimentalTime::class)
@Service
class StreamingAgentServiceImpl(
    private val promptExecutor: PromptExecutor,
    private val graphRagService: GraphRagService,
    private val documentRagService: DocumentRagService,
    private val toolGroupRegistry: ToolGroupRegistry,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val modelName: String
) : StreamingAgentService {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val DEFAULT_SYSTEM_PROMPT = """Du bist ein Wissensassistent. Beantworte die Frage des Benutzers.

Verwende die verfuegbaren Tools um Informationen zu sammeln:
- knowledge_query: Frage den Knowledge Graph ab. Parameter: {"question": "deine Frage"}
- document_query: Suche in Dokumenten. Parameter: {"question": "deine Frage"}

Wenn du ein Tool aufrufen willst, antworte NUR mit dem Tool-Aufruf.
Wenn du genug Informationen hast, antworte direkt mit der finalen Antwort."""
    }

    override fun queryStreaming(
        question: String,
        collectionId: String,
        config: AgentQueryConfig,
        allowedGroups: Set<String>
    ): Flow<StreamToken> = flow {
        val allowedToolNames = toolGroupRegistry.resolveToolNames(allowedGroups)
        val tools = buildTools(collectionId, allowedToolNames)
        val model = resolveLlmModel(modelName)
        val conversationHistory = mutableListOf<Pair<String, String>>() // role -> content

        for (iteration in 1..config.maxIterations) {
            val currentPrompt = buildPrompt(question, conversationHistory)
            logger.debug("Agent streaming iteration {}: history={}", iteration, conversationHistory.size)

            val textBuilder = StringBuilder()
            var toolCallName: String? = null
            var toolCallContent: String? = null
            var frameCount = 0

            promptExecutor.executeStreaming(currentPrompt, model).collect { frame ->
                frameCount++
                when (frame) {
                    is StreamFrame.TextDelta -> {
                        textBuilder.append(frame.text)
                        // Emit the delta live so the frontend can render progress instead of
                        // sitting on "Warte auf Stream". Consecutive THOUGHT tokens are merged
                        // in the UI (see AgentStreamTimeline).
                        emit(StreamToken(content = frame.text, type = StreamTokenType.THOUGHT))
                    }
                    is StreamFrame.ToolCallComplete -> {
                        logger.debug("Received ToolCallComplete: name={}", frame.name)
                        toolCallName = frame.name
                        toolCallContent = frame.content
                    }
                    is StreamFrame.End -> {
                        logger.debug("Stream ended after {} frames, buffered text length={}", frameCount, textBuilder.length)
                    }
                    else -> { /* ignore other frame types */ }
                }
            }

            val rawText = textBuilder.toString().trim()

            // Koog's streaming executor is invoked here without tool bindings, so models that
            // cannot emit structured tool_calls (e.g. local Ollama setups) instead follow the
            // system-prompt instruction and output the tool call as plain text. Recover that
            // case by parsing the buffered text for a `toolname({...})` pattern that matches a
            // registered tool, otherwise the ReAct loop would never fire and the answer would
            // be empty.
            if (toolCallName == null && rawText.isNotEmpty()) {
                val parsed = tryParseTextualToolCall(rawText, tools.keys)
                if (parsed != null) {
                    logger.debug("Parsed textual tool call from buffered text: name={}", parsed.first)
                    toolCallName = parsed.first
                    toolCallContent = parsed.second
                }
            }

            if (toolCallName != null) {
                // Agent wants to call a tool
                emit(StreamToken(
                    content = "$toolCallName(${toolCallContent ?: ""})",
                    type = StreamTokenType.ACTION,
                    endOfMessage = true
                ))

                val observation = executeTool(toolCallName!!, toolCallContent, tools)
                emit(StreamToken(
                    content = observation,
                    type = StreamTokenType.OBSERVATION,
                    endOfMessage = true
                ))

                // Add to conversation history for next iteration
                conversationHistory.add("assistant" to textBuilder.toString())
                conversationHistory.add("tool" to "Tool: $toolCallName\nResult: $observation")
            } else {
                // No tool call — emit the buffered text as the final answer.
                emit(StreamToken(
                    content = rawText,
                    type = StreamTokenType.ANSWER,
                    endOfMessage = true,
                    endOfStream = true
                ))
                return@flow
            }
        }

        // Max iterations reached
        emit(StreamToken(
            content = "Max iterations reached. Last response: ${conversationHistory.lastOrNull()?.second ?: ""}",
            type = StreamTokenType.ANSWER,
            endOfMessage = true,
            endOfStream = true
        ))
    }

    private fun buildPrompt(
        question: String,
        history: List<Pair<String, String>>
    ) = prompt("streaming-agent") {
        system(DEFAULT_SYSTEM_PROMPT)
        for ((role, content) in history) {
            when (role) {
                "assistant" -> assistant(content)
                "tool" -> user(content)
            }
        }
        user(question.takeIf { history.isEmpty() } ?: "Continue based on the tool results above.")
    }

    private fun buildTools(
        collectionId: String,
        allowedToolNames: Set<String>
    ): Map<String, suspend (String?) -> String> {
        val tools = mutableMapOf<String, suspend (String?) -> String>()
        if ("knowledge_query" in allowedToolNames) {
            val tool = KnowledgeQueryTool(graphRagService, collectionId)
            tools["knowledge_query"] = { args ->
                val question = parseToolQuestion(args)
                tool.execute(KnowledgeQueryTool.Args(question = question))
            }
        }
        if ("document_query" in allowedToolNames) {
            val tool = DocumentQueryTool(documentRagService, collectionId)
            tools["document_query"] = { args ->
                val question = parseToolQuestion(args)
                tool.execute(DocumentQueryTool.Args(question = question))
            }
        }
        return tools
    }

    private suspend fun executeTool(
        toolName: String,
        toolArgs: String?,
        tools: Map<String, suspend (String?) -> String>
    ): String {
        val toolFn = tools[toolName]
            ?: return "Error: Tool '$toolName' not available."
        return try {
            toolFn(toolArgs)
        } catch (e: Exception) {
            logger.error("Tool execution failed: {}", toolName, e)
            "Error: ${e.message}"
        }
    }

    /**
     * Parse free-form text for a tool call in the form `toolname({...json...})`.
     * Returns the `(name, jsonArgs)` pair for the first occurrence whose name is in
     * [knownTools], or `null` if no match is found.
     *
     * Used as a fallback when the underlying LLM emits tool calls as text instead of
     * structured `tool_calls` messages (common with Ollama models behind Koog's bare
     * `executeStreaming` API, which does not bind tools to the LLM protocol).
     */
    internal fun tryParseTextualToolCall(
        text: String,
        knownTools: Set<String>
    ): Pair<String, String>? {
        if (knownTools.isEmpty()) return null
        // Match `name( { ... } )`, allowing whitespace/newlines inside the JSON payload.
        val pattern = Regex("""(\w+)\s*\(\s*(\{[\s\S]*?\})\s*\)""")
        for (match in pattern.findAll(text)) {
            val name = match.groupValues[1]
            if (name in knownTools) {
                return name to match.groupValues[2]
            }
        }
        return null
    }

    internal fun parseToolQuestion(args: String?): String {
        if (args.isNullOrBlank()) return ""
        return try {
            val map = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                .readValue(args, Map::class.java)
            (map["question"] as? String) ?: args
        } catch (_: Exception) {
            args
        }
    }
}
