package com.agentwork.graphmesh.query.docrag

import com.agentwork.graphmesh.llm.resolveLlmModel

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.query.CachedEmbeddingService
import com.agentwork.graphmesh.messaging.ExplainabilityEventProducer
import com.agentwork.graphmesh.storage.vector.VectorStore
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@OptIn(kotlin.time.ExperimentalTime::class)
@Service
class DocumentRagService(
    private val cachedEmbeddingService: CachedEmbeddingService,
    private val vectorStore: VectorStore,
    private val librarianService: LibrarianService,
    private val promptExecutor: PromptExecutor,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val llmModelName: String,
    private val explainabilityProducer: ExplainabilityEventProducer
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun query(query: DocumentRagQuery): DocumentRagResult {
        val sessionId = UUID.randomUUID()
        val startTime = System.currentTimeMillis()

        // Phase 1: Chunk Retrieval
        logger.info("Phase 1: Chunk retrieval for collection {}, topK={}", query.collectionId, query.topK)
        val chunks = retrieveChunks(query)
        logger.info("Retrieved {} chunks", chunks.size)

        if (chunks.isEmpty()) {
            val emptyAnswer = "No relevant documents found for this question."
            explainabilityProducer.sendDocRagEvent(
                sessionId = sessionId,
                collectionId = query.collectionId,
                queryText = query.question,
                retrievedChunkCount = 0,
                selectedChunkIds = emptyList(),
                answerText = emptyAnswer
            )
            return DocumentRagResult(
                sessionId = sessionId,
                answer = emptyAnswer,
                sources = emptyList(),
                retrievedChunkCount = 0,
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // Phase 2: Answer Synthesis
        logger.info("Phase 2: Answer synthesis from {} chunks", chunks.size)
        val answer = synthesizeAnswer(query.question, chunks)

        // Build source attributions
        val sources = buildSourceAttributions(chunks)

        val durationMs = System.currentTimeMillis() - startTime
        logger.info("Document RAG pipeline completed in {} ms", durationMs)

        explainabilityProducer.sendDocRagEvent(
            sessionId = sessionId,
            collectionId = query.collectionId,
            queryText = query.question,
            retrievedChunkCount = chunks.size,
            selectedChunkIds = chunks.map { it.chunkId },
            answerText = answer
        )

        return DocumentRagResult(
            sessionId = sessionId,
            answer = answer,
            sources = sources,
            retrievedChunkCount = chunks.size,
            durationMs = durationMs
        )
    }

    private fun retrieveChunks(query: DocumentRagQuery): List<RetrievedChunk> {
        val queryVector = query.precomputedEmbedding ?: cachedEmbeddingService.embed(query.question)

        logger.debug(
            "Vector search: collection={}, topK={}, threshold={}",
            query.collectionId, query.topK, query.similarityThreshold
        )

        val searchResults = vectorStore.search(
            collection = query.collectionId,
            queryVector = queryVector,
            limit = query.topK,
            scoreThreshold = query.similarityThreshold
        )

        // Surface the score distribution so operators can tell when the threshold is
        // mis-tuned for their embedding provider (the #1 cause of "No relevant
        // documents found" after switching between Ollama and OpenAI embeddings).
        if (logger.isDebugEnabled) {
            val scores = searchResults.map { it.score }
            logger.debug(
                "Vector search returned {} hits, score range=[{}..{}]",
                searchResults.size,
                scores.minOrNull(),
                scores.maxOrNull()
            )
        }

        return searchResults.map { result ->
            val chunkId = result.id
            val documentId = result.payload.documentId ?: ""

            val text = try {
                String(librarianService.getContent(chunkId), Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }

            RetrievedChunk(
                chunkId = chunkId,
                documentId = documentId,
                text = text,
                score = result.score
            )
        }.filter { it.text.isNotBlank() }
    }

    private fun synthesizeAnswer(question: String, chunks: List<RetrievedChunk>): String {
        val context = chunks.mapIndexed { index, chunk ->
            "[Source ${index + 1}]\n${chunk.text}"
        }.joinToString("\n\n")

        val synthesisPrompt = prompt("document-rag-synthesis") {
            system("""
                You are a document analysis assistant. Answer the user's question based ONLY on
                the provided document excerpts. Reference sources by their number [Source N] when
                citing information. If the excerpts don't contain enough information, say so.
                Do not make up information beyond what the sources state.

                Document excerpts:
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

    private fun buildSourceAttributions(chunks: List<RetrievedChunk>): List<SourceAttribution> {
        return chunks.map { chunk ->
            val doc = try {
                librarianService.findById(chunk.documentId)
            } catch (_: Exception) {
                null
            }

            // Extract page number from chunk ID hierarchy (e.g., "doc-123/p5/c2" -> page 5)
            val pageNumber = extractPageNumber(chunk.chunkId)

            SourceAttribution(
                chunkId = chunk.chunkId,
                documentId = chunk.documentId,
                documentTitle = doc?.title ?: "Unknown",
                pageNumber = pageNumber,
                score = chunk.score,
                snippet = chunk.text.take(200)
            )
        }
    }

    internal fun extractPageNumber(chunkId: String): Int? {
        val pagePattern = Regex("/p(\\d+)")
        return pagePattern.find(chunkId)?.groupValues?.get(1)?.toIntOrNull()
    }
}
