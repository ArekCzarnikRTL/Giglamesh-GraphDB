package com.agentwork.graphmesh.query.graphrag

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.extraction.embedding.EmbeddingConfig
import com.agentwork.graphmesh.messaging.ExplainabilityEventProducer
import com.agentwork.graphmesh.provenance.query.SelectedEdgeExplanation
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import com.agentwork.graphmesh.storage.vector.VectorStore
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@OptIn(kotlin.time.ExperimentalTime::class)
@Service
class GraphRagService(
    private val embeddingProvider: LLMEmbeddingProvider,
    private val vectorStore: VectorStore,
    private val quadStore: QuadStore,
    private val promptExecutor: PromptExecutor,
    private val embeddingConfig: EmbeddingConfig,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val llmModelName: String,
    private val explainabilityProducer: ExplainabilityEventProducer
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun query(query: GraphRagQuery): GraphRagResult {
        val sessionId = UUID.randomUUID()
        val startTime = System.currentTimeMillis()

        // Phase 1: Subgraph Retrieval
        logger.info("Phase 1: Subgraph retrieval for collection {}", query.collectionId)
        val subgraph = retrieveSubgraph(query)
        logger.info("Retrieved {} edges", subgraph.size)

        if (subgraph.isEmpty()) {
            val emptyAnswer = "No relevant knowledge found for this question."
            explainabilityProducer.sendGraphRagEvent(
                sessionId = sessionId,
                collectionId = query.collectionId,
                queryText = query.question,
                retrievedEdgeCount = 0,
                selectedEdges = emptyList(),
                answerText = emptyAnswer
            )
            return GraphRagResult(
                sessionId = sessionId,
                answer = emptyAnswer,
                selectedEdges = emptyList(),
                retrievedEdgeCount = 0,
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // Phase 2: Edge Selection
        logger.info("Phase 2: Edge selection from {} edges", subgraph.size)
        val selectedEdges = selectEdges(query.question, subgraph, query.maxSelectedEdges)
        logger.info("{} edges selected", selectedEdges.size)

        // Phase 3: Answer Synthesis
        logger.info("Phase 3: Answer synthesis")
        val answer = synthesizeAnswer(query.question, selectedEdges)

        val durationMs = System.currentTimeMillis() - startTime
        logger.info("Graph RAG pipeline completed in {} ms", durationMs)

        explainabilityProducer.sendGraphRagEvent(
            sessionId = sessionId,
            collectionId = query.collectionId,
            queryText = query.question,
            retrievedEdgeCount = subgraph.size,
            selectedEdges = selectedEdges.map {
                SelectedEdgeExplanation(it.subject, it.predicate, it.objectValue, it.reasoning)
            },
            answerText = answer
        )

        return GraphRagResult(
            sessionId = sessionId,
            answer = answer,
            selectedEdges = selectedEdges,
            retrievedEdgeCount = subgraph.size,
            durationMs = durationMs
        )
    }

    private fun retrieveSubgraph(query: GraphRagQuery): List<StoredQuad> {
        val embeddingModel = LLModel(LLMProvider.OpenAI, embeddingConfig.model)

        val embedding = runBlocking {
            embeddingProvider.embed(query.question, embeddingModel)
        }
        val queryVector = FloatArray(embedding.size) { embedding[it].toFloat() }

        // Find similar chunks via vector search
        val searchResults = vectorStore.search(
            collection = query.collectionId,
            queryVector = queryVector,
            limit = 50
        )

        // Extract entity IDs from search results
        val entityIds = searchResults.mapNotNull { result ->
            result.payload["chunk_id"]?.toString()
        }

        if (entityIds.isEmpty()) return emptyList()

        // Get subgraph edges for these entities
        val edges = quadStore.findByEntities(query.collectionId, entityIds)

        return edges.take(query.maxEdges)
    }

    private fun selectEdges(
        question: String,
        edges: List<StoredQuad>,
        maxSelected: Int
    ): List<SelectedEdge> {
        val edgeList = edges.mapIndexed { index, quad ->
            "$index|${quad.subject}|${quad.predicate}|${quad.objectValue}"
        }.joinToString("\n")

        val selectionPrompt = prompt("edge-selection") {
            system("""
                You are a knowledge graph analyst. Given a question and a list of graph edges,
                select the most relevant edges that help answer the question.

                For each selected edge, respond with:
                INDEX|REASONING

                Where INDEX is the edge number and REASONING explains why it's relevant.
                Select at most $maxSelected edges. Only select truly relevant edges.
            """.trimIndent())
            user("""
                Question: $question

                Edges:
                $edgeList

                Select the relevant edges (INDEX|REASONING format, one per line):
            """.trimIndent())
        }

        val llmModel = LLModel(LLMProvider.OpenAI, llmModelName)
        val response = runBlocking {
            promptExecutor.execute(selectionPrompt, llmModel)
        }

        return parseEdgeSelection(response.first().content, edges)
    }

    internal fun parseEdgeSelection(llmResponse: String, edges: List<StoredQuad>): List<SelectedEdge> {
        return llmResponse.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("|") }
            .mapNotNull { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val index = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                val reasoning = parts[1].trim()
                if (index !in edges.indices || reasoning.isBlank()) return@mapNotNull null

                val quad = edges[index]
                SelectedEdge(
                    subject = quad.subject,
                    predicate = quad.predicate,
                    objectValue = quad.objectValue,
                    dataset = quad.dataset,
                    reasoning = reasoning,
                    relevanceScore = 1.0 - (index.toDouble() / edges.size)
                )
            }
    }

    private fun synthesizeAnswer(question: String, selectedEdges: List<SelectedEdge>): String {
        val context = selectedEdges.joinToString("\n") { edge ->
            "${edge.subject} --[${edge.predicate}]--> ${edge.objectValue}"
        }

        val synthesisPrompt = prompt("answer-synthesis") {
            system("""
                You are a knowledge assistant. Answer the user's question based ONLY on the
                provided knowledge graph facts. If the facts don't contain enough information,
                say so. Do not make up information beyond what the facts state.

                Knowledge graph facts:
                $context
            """.trimIndent())
            user(question)
        }

        val llmModel = LLModel(LLMProvider.OpenAI, llmModelName)
        val response = runBlocking {
            promptExecutor.execute(synthesisPrompt, llmModel)
        }

        return response.first().content
    }
}
