package com.agentwork.graphmesh.query.graphrag

import com.agentwork.graphmesh.llm.resolveLlmModel

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
        val embeddingModel = resolveLlmModel(embeddingConfig.model)

        val embedding = runBlocking {
            embeddingProvider.embed(query.question, embeddingModel)
        }
        val queryVector = FloatArray(embedding.size) { embedding[it].toFloat() }

        // Vector hits → bare chunk ids from the embedding payload
        val searchResults = vectorStore.search(
            collection = query.collectionId,
            queryVector = queryVector,
            limit = 50
        )

        val chunkUrns = searchResults
            .mapNotNull { it.payload["chunk_id"]?.toString() }
            .map { "urn:chunk:$it" }
            .distinct()

        if (chunkUrns.isEmpty()) {
            logger.debug("retrieveSubgraph: no chunk_ids in vector hits")
            return emptyList()
        }

        // Phase 1: chunks → provenance subgraphs
        val subgraphUris = quadStore.findSubgraphsForChunks(query.collectionId, chunkUrns)
        if (subgraphUris.isEmpty()) {
            logger.debug("retrieveSubgraph: no subgraphs for {} chunkUrns", chunkUrns.size)
            return emptyList()
        }

        // Phase 2: subgraphs → unpacked quoted triples (the actual knowledge edges)
        val quotedTriples = quadStore.findQuotedTriplesForSubgraphs(query.collectionId, subgraphUris)

        // Phase 3: 1-hop entity expansion
        val entityUris = collectEntityUris(quotedTriples)
        val expandedEdges = if (entityUris.isNotEmpty()) {
            quadStore.findByEntities(query.collectionId, entityUris)
        } else {
            emptyList()
        }

        logger.debug(
            "retrieveSubgraph: {} chunks → {} subgraphs → {} quoted triples → {} entities → {} expanded edges",
            chunkUrns.size, subgraphUris.size, quotedTriples.size, entityUris.size, expandedEdges.size
        )

        return (quotedTriples + expandedEdges).distinct().take(query.maxEdges)
    }

    /**
     * Pure helper: collects entity URIs (subjects/objects starting with the
     * GraphMesh entity-URI prefix) from a list of unpacked quoted triples.
     * `internal` so it can be unit-tested without constructing the full service.
     */
    internal fun collectEntityUris(quotedTriples: List<StoredQuad>): List<String> {
        return quotedTriples
            .flatMap { listOf(it.subject, it.objectValue) }
            .filter { it.startsWith("http://graphmesh.io/entity/") }
            .distinct()
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

        val llmModel = resolveLlmModel(llmModelName)
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

        val llmModel = resolveLlmModel(llmModelName)
        val response = runBlocking {
            promptExecutor.execute(synthesisPrompt, llmModel)
        }

        return response.first().content
    }
}
