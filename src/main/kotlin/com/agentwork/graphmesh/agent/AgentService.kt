package com.agentwork.graphmesh.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.reActStrategy
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.messaging.ExplainabilityEventProducer
import com.agentwork.graphmesh.provenance.query.AgentIterationCollector
import com.agentwork.graphmesh.query.docrag.DocumentRagService
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AgentService(
    private val promptExecutor: PromptExecutor,
    private val graphRagService: GraphRagService,
    private val documentRagService: DocumentRagService,
    private val toolGroupRegistry: ToolGroupRegistry,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val modelName: String,
    private val explainabilityProducer: ExplainabilityEventProducer
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun query(
        question: String,
        collectionId: String,
        config: AgentQueryConfig = AgentQueryConfig(),
        allowedGroups: Set<String> = setOf("all")
    ): AgentQueryResult {
        val sessionId = UUID.randomUUID()
        val startTime = System.currentTimeMillis()

        val allowedToolNames = toolGroupRegistry.resolveToolNames(allowedGroups)

        val toolRegistry = ToolRegistry {
            if ("knowledge_query" in allowedToolNames) {
                tool(KnowledgeQueryTool(graphRagService, collectionId))
            }
            if ("document_query" in allowedToolNames) {
                tool(DocumentQueryTool(documentRagService, collectionId))
            }
        }

        val collector = AgentIterationCollector()
        @Suppress("UNUSED_VARIABLE")
        val bridge = KoogAgentTracingBridge(collector)

        // NOTE: Koog Tracing installation intentionally omitted until Koog 0.7.3 event API
        // is verified. The bridge + collector contract is ready to be wired when
        // install(Tracing) { addMessageProcessor(...) } is confirmed available in the runtime.
        // Until then, the collector remains empty and the producer sends an empty iteration list.
        // See docs/superpowers/plans/2026-04-06-query-explainability.md Task 9 for the contract.

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = LLModel(LLMProvider.OpenAI, modelName),
            strategy = reActStrategy(reasoningInterval = 1, name = "query_agent"),
            toolRegistry = toolRegistry,
            systemPrompt = config.systemPrompt
        )

        val answer = runBlocking {
            agent.run(question)
        }

        val durationMs = System.currentTimeMillis() - startTime
        val iterations = collector.snapshot()

        explainabilityProducer.sendAgentEvent(
            sessionId = sessionId,
            collectionId = collectionId,
            queryText = question,
            iterations = iterations,
            answerText = answer,
        )

        logger.info(
            "Agent query complete: question='{}', groups={}, iterations={}, durationMs={}",
            question.take(80), allowedGroups, iterations.size, durationMs
        )

        return AgentQueryResult(
            sessionId = sessionId,
            answer = answer,
            durationMs = durationMs,
        )
    }

    fun getAvailableTools(): List<ToolInfo> = listOf(
        ToolInfo(
            name = "knowledge_query",
            description = "Query the knowledge graph for entities and relationships.",
            groups = toolGroupRegistry.getGroupsForTool("knowledge_query")
        ),
        ToolInfo(
            name = "document_query",
            description = "Search documents for relevant text passages.",
            groups = toolGroupRegistry.getGroupsForTool("document_query")
        )
    )

    fun getToolGroups(): List<ToolGroup> = toolGroupRegistry.getGroups()
}
