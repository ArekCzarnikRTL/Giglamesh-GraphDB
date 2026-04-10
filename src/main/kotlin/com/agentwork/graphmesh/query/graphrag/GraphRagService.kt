package com.agentwork.graphmesh.query.graphrag

import com.agentwork.graphmesh.llm.resolveLlmModel

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import com.agentwork.graphmesh.messaging.ExplainabilityEventProducer
import com.agentwork.graphmesh.query.CachedEmbeddingService
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
    private val cachedEmbeddingService: CachedEmbeddingService,
    private val vectorStore: VectorStore,
    private val quadStore: QuadStore,
    private val promptExecutor: PromptExecutor,
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

        // Phase 2+3: Combined selection and synthesis
        logger.info("Phase 2+3: Select and synthesize from {} edges", subgraph.size)
        val (answer, selectedEdges) = selectAndSynthesize(query.question, subgraph, query.maxSelectedEdges)
        logger.info("{} edges selected, answer generated", selectedEdges.size)

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
        val queryVector = cachedEmbeddingService.embed(query.question)

        val searchResults = vectorStore.search(
            collection = query.collectionId,
            queryVector = queryVector,
            limit = 50
        )

        logger.info("Vector search returned {} hits for collection {}", searchResults.size, query.collectionId)

        // Split results into chunk-based and entity-based hits
        val chunkUrns = searchResults
            .mapNotNull { it.payload["chunk_id"]?.toString() }
            .map { "urn:chunk:$it" }
            .distinct()

        val entityUris = searchResults
            .mapNotNull { it.payload["entity_uri"]?.toString() }
            .distinct()

        if (chunkUrns.isEmpty() && entityUris.isEmpty()) {
            logger.info("No vector hits, falling back to quad store scan for collection {}", query.collectionId)
            return fallbackQuadStoreScan(query)
        }

        // Path 1: Chunk-based retrieval (existing provenance path)
        val chunkTriples = if (chunkUrns.isNotEmpty()) {
            val subgraphUris = quadStore.findSubgraphsForChunks(query.collectionId, chunkUrns)
            if (subgraphUris.isNotEmpty()) {
                quadStore.findQuotedTriplesForSubgraphs(query.collectionId, subgraphUris)
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        // Path 2: Entity-based retrieval (RDF import path)
        val entityTriples = if (entityUris.isNotEmpty()) {
            quadStore.findByEntities(query.collectionId, entityUris)
        } else {
            emptyList()
        }

        // 1-hop entity expansion on chunk triples (existing behavior)
        val chunkEntityUris = collectEntityUris(chunkTriples)
        val expandedEdges = if (chunkEntityUris.isNotEmpty()) {
            quadStore.findByEntities(query.collectionId, chunkEntityUris)
        } else {
            emptyList()
        }

        logger.debug(
            "retrieveSubgraph: {} chunks -> {} chunk triples, {} entity URIs -> {} entity triples, {} expanded edges",
            chunkUrns.size, chunkTriples.size, entityUris.size, entityTriples.size, expandedEdges.size
        )

        return (chunkTriples + entityTriples + expandedEdges).distinct().take(query.maxEdges)
    }

    /**
     * Fallback when no embeddings exist: load all quads from the collection
     * directly from Cassandra and let the LLM select the relevant edges.
     */
    private fun fallbackQuadStoreScan(query: GraphRagQuery): List<StoredQuad> {
        val allQuads = quadStore.query(query.collectionId, com.agentwork.graphmesh.storage.QuadQuery())
        logger.info("Fallback quad store scan: {} quads in collection {}", allQuads.size, query.collectionId)
        return allQuads.take(query.maxEdges)
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

    internal fun parseSelectAndSynthesize(llmResponse: String, edges: List<StoredQuad>): Pair<String, List<SelectedEdge>> {
        val edgesMarker = "EDGES:"
        val answerMarker = "ANSWER:"
        val edgesIdx = llmResponse.indexOf(edgesMarker)
        val rawAnswer = if (edgesIdx >= 0) llmResponse.substring(0, edgesIdx) else llmResponse
        val cleanAnswer = rawAnswer.removePrefix(answerMarker).trim()
        val edgeSection = if (edgesIdx >= 0) llmResponse.substring(edgesIdx + edgesMarker.length) else ""
        val selectedEdges = edgeSection.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("|") }
            .mapNotNull { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val index = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                val reasoning = parts[1].trim()
                if (index !in edges.indices || reasoning.isBlank()) return@mapNotNull null
                val quad = edges[index]
                SelectedEdge(subject = quad.subject, predicate = quad.predicate, objectValue = quad.objectValue,
                    dataset = quad.dataset, reasoning = reasoning, relevanceScore = 1.0 - (index.toDouble() / edges.size))
            }
        return cleanAnswer to selectedEdges
    }

    private fun selectAndSynthesize(
        question: String,
        edges: List<StoredQuad>,
        maxSelected: Int
    ): Pair<String, List<SelectedEdge>> {
        val edgeList = edges.mapIndexed { index, quad ->
            "$index|${quad.subject}|${quad.predicate}|${quad.objectValue}"
        }.joinToString("\n")

        val combinedPrompt = prompt("select-and-synthesize") {
            system("""
                You are a knowledge assistant. Answer the user's question based ONLY on the
                provided knowledge graph facts. Do not make up information beyond what the facts state.
                If the facts don't contain enough information, say so.

                After your answer, list the fact numbers you used with a brief reason.
                Select at most $maxSelected facts. Only cite truly relevant facts.

                Knowledge graph facts:
                $edgeList

                Respond in this exact format:
                ANSWER:
                <your answer here>

                EDGES:
                <index>|<why this fact was relevant>
            """.trimIndent())
            user(question)
        }

        val llmModel = resolveLlmModel(llmModelName)
        val response = runBlocking {
            promptExecutor.execute(combinedPrompt, llmModel)
        }

        return parseSelectAndSynthesize(response.first().content, edges)
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
