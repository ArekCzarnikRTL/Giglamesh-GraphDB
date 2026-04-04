package com.agentwork.graphmesh.query.nlp

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.query.docrag.DocumentRagQuery
import com.agentwork.graphmesh.query.docrag.DocumentRagService
import com.agentwork.graphmesh.query.graphrag.GraphRagQuery
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@OptIn(kotlin.time.ExperimentalTime::class)
@Service
class NlpQueryService(
    private val promptExecutor: PromptExecutor,
    private val graphRagService: GraphRagService,
    private val documentRagService: DocumentRagService,
    private val quadStore: QuadStore,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val llmModelName: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun query(query: NlpQuery): NlpQueryResult {
        val startTime = System.currentTimeMillis()

        // Step 1: Intent Detection
        val detectedIntent = if (query.forceIntent != null) {
            DetectedIntent(query.forceIntent, 1.0, "Intent forced by caller")
        } else {
            logger.info("Detecting intent for: '{}'", query.question)
            detectIntent(query.question)
        }
        logger.info("Detected intent: {} (confidence: {})", detectedIntent.intent, detectedIntent.confidence)

        // Step 2: Route to appropriate service
        val (answer, sources) = route(query.question, detectedIntent.intent, query.collectionId)

        val durationMs = System.currentTimeMillis() - startTime
        logger.info("NLP query completed in {} ms via {}", durationMs, detectedIntent.intent)

        return NlpQueryResult(
            answer = answer,
            detectedIntent = detectedIntent,
            wasReformulated = false,
            effectiveQuestion = query.question,
            durationMs = durationMs,
            sources = sources
        )
    }

    private fun detectIntent(question: String): DetectedIntent {
        val intentPrompt = prompt("intent-detection") {
            system("""
                You are a query classifier. Analyze the question and determine the optimal query type.

                Respond in this exact format:
                INTENT|CONFIDENCE|REASONING

                Where INTENT is one of: GRAPH_QUERY, DOCUMENT_QUERY, STRUCTURED_QUERY, HYBRID
                CONFIDENCE is a number between 0.0 and 1.0
                REASONING is a brief explanation

                Rules:
                - GRAPH_QUERY: Questions about entities, relationships, connections in the knowledge graph
                - DOCUMENT_QUERY: Questions best answered from document content, summaries, citations
                - STRUCTURED_QUERY: Specific lookups for individual facts or triples
                - HYBRID: Complex questions that need both graph and document sources
            """.trimIndent())
            user(question)
        }

        val llmModel = LLModel(LLMProvider.OpenAI, llmModelName)
        val response = runBlocking {
            promptExecutor.execute(intentPrompt, llmModel)
        }

        return parseIntentResponse(response.first().content)
    }

    internal fun parseIntentResponse(response: String): DetectedIntent {
        val line = response.lines().firstOrNull { it.contains("|") }?.trim()
        if (line == null) {
            return DetectedIntent(QueryIntent.GRAPH_QUERY, 0.5, "Failed to parse intent, using fallback")
        }

        val parts = line.split("|", limit = 3).map { it.trim() }
        if (parts.size < 3) {
            return DetectedIntent(QueryIntent.GRAPH_QUERY, 0.5, "Incomplete intent response, using fallback")
        }

        val intent = try {
            QueryIntent.valueOf(parts[0].uppercase())
        } catch (_: IllegalArgumentException) {
            QueryIntent.GRAPH_QUERY
        }

        val confidence = parts[1].toDoubleOrNull() ?: 0.5
        val reasoning = parts[2]

        // Fallback to GRAPH_QUERY if confidence is too low
        val effectiveIntent = if (confidence < 0.5) QueryIntent.GRAPH_QUERY else intent

        return DetectedIntent(effectiveIntent, confidence, reasoning)
    }

    private fun route(question: String, intent: QueryIntent, collectionId: String): Pair<String, List<String>> {
        return when (intent) {
            QueryIntent.GRAPH_QUERY -> {
                val result = graphRagService.query(GraphRagQuery(question, collectionId))
                val sources = result.selectedEdges.map { edge ->
                    "${edge.subject} --[${edge.predicate}]--> ${edge.objectValue}"
                }
                result.answer to sources
            }

            QueryIntent.DOCUMENT_QUERY -> {
                val result = documentRagService.query(DocumentRagQuery(question, collectionId))
                val sources = result.sources.map { src ->
                    "${src.documentTitle} (page ${src.pageNumber ?: "?"})"
                }
                result.answer to sources
            }

            QueryIntent.STRUCTURED_QUERY -> {
                val quads = quadStore.query(collectionId, QuadQuery())
                val text = quads.take(20).joinToString("\n") { q ->
                    "${q.subject} --[${q.predicate}]--> ${q.objectValue}"
                }
                val answer = text.ifEmpty { "No matching triples found." }
                answer to quads.take(20).map { "${it.dataset}" }.distinct()
            }

            QueryIntent.HYBRID -> {
                val graphResult = graphRagService.query(GraphRagQuery(question, collectionId))
                val docResult = documentRagService.query(DocumentRagQuery(question, collectionId))

                val answer = """
                    Based on the Knowledge Graph:
                    ${graphResult.answer}

                    Based on Documents:
                    ${docResult.answer}
                """.trimIndent()

                val sources = graphResult.selectedEdges.map { edge ->
                    "[Graph] ${edge.subject} --[${edge.predicate}]--> ${edge.objectValue}"
                } + docResult.sources.map { src ->
                    "[Document] ${src.documentTitle} (page ${src.pageNumber ?: "?"})"
                }

                answer to sources
            }
        }
    }
}
