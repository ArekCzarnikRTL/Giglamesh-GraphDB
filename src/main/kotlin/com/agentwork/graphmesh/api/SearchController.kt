package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.llm.resolveLlmModel

import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.extraction.embedding.EmbeddingConfig
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.storage.vector.VectorStore
import kotlinx.coroutines.runBlocking
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class SearchController(
    private val vectorStore: VectorStore,
    private val embeddingProvider: LLMEmbeddingProvider,
    private val librarianService: LibrarianService,
    private val embeddingConfig: EmbeddingConfig
) {

    @QueryMapping
    fun vectorSearch(
        @Argument collectionId: String,
        @Argument query: String,
        @Argument limit: Int?
    ): List<Map<String, Any?>> {
        val model = resolveLlmModel(embeddingConfig.model)

        val embedding = runBlocking {
            embeddingProvider.embed(query, model)
        }

        val queryVector = FloatArray(embedding.size) { embedding[it].toFloat() }

        val results = vectorStore.search(
            collection = collectionId,
            queryVector = queryVector,
            limit = limit ?: 10
        )

        return results.map { result ->
            val chunkText = try {
                val content = librarianService.getContent(result.id)
                String(content, Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }

            val payload = result.payload.toMap().map { (k, v) -> mapOf("key" to k, "value" to v.toString()) }

            mapOf(
                "id" to result.id,
                "score" to result.score,
                "payload" to payload,
                "text" to chunkText
            )
        }
    }
}
