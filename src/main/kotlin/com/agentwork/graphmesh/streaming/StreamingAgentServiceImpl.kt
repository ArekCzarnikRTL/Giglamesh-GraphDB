package com.agentwork.graphmesh.streaming

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
        val model = LLModel(LLMProvider.OpenAI, modelName)
        val conversationHistory = mutableListOf<Pair<String, String>>() // role -> content

        for (iteration in 1..config.maxIterations) {
            val currentPrompt = buildPrompt(question, conversationHistory)

            val textBuilder = StringBuilder()
            var toolCallName: String? = null
            var toolCallContent: String? = null

            promptExecutor.executeStreaming(currentPrompt, model).collect { frame ->
                when (frame) {
                    is StreamFrame.TextDelta -> {
                        textBuilder.append(frame.text)
                        emit(StreamToken(content = frame.text, type = StreamTokenType.THOUGHT))
                    }
                    is StreamFrame.ToolCallComplete -> {
                        toolCallName = frame.name
                        toolCallContent = frame.content
                    }
                    is StreamFrame.End -> { /* handled below */ }
                    else -> { /* ignore other frame types */ }
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
                // No tool call — this is the final answer
                emit(StreamToken(
                    content = "",
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
