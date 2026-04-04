package com.agentwork.graphmesh.extraction.embedding

import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.storage.vector.VectorPoint
import com.agentwork.graphmesh.storage.vector.VectorStore
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class EmbeddingService(
    private val embeddingProvider: LLMEmbeddingProvider,
    private val vectorStore: VectorStore,
    private val librarianService: LibrarianService,
    private val config: EmbeddingConfig
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun embed(chunkId: String, documentId: String, collectionName: String) {
        val content = librarianService.getContent(chunkId)
        val text = String(content, Charsets.UTF_8)

        if (text.isBlank()) {
            logger.debug("Skipping empty chunk: {}", chunkId)
            return
        }

        val model = LLModel(LLMProvider.OpenAI, config.model)

        val embedding = runBlocking {
            embeddingProvider.embed(text, model)
        }

        val vector = FloatArray(embedding.size) { embedding[it].toFloat() }

        val point = VectorPoint(
            id = chunkId,
            vector = vector,
            payload = mapOf(
                "chunk_id" to chunkId,
                "document_id" to documentId,
                "collection" to collectionName
            )
        )

        vectorStore.upsert(collectionName, listOf(point))

        logger.debug("Embedded chunk: id={}, collection={}, dimension={}", chunkId, collectionName, vector.size)
    }
}
