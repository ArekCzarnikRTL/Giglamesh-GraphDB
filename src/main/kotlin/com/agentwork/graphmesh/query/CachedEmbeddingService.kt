package com.agentwork.graphmesh.query

import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import com.agentwork.graphmesh.extraction.embedding.EmbeddingConfig
import com.agentwork.graphmesh.llm.resolveLlmModel
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class CachedEmbeddingService(
    private val embeddingProvider: LLMEmbeddingProvider,
    private val embeddingConfig: EmbeddingConfig,
) {

    private val cache: Cache<String, FloatArray> = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofMinutes(30))
        .build()

    fun embed(text: String): FloatArray {
        val model = resolveLlmModel(embeddingConfig.model)
        val key = "${model.id}:${text.hashCode()}"
        return cache.get(key) { _ ->
            val embedding = runBlocking { embeddingProvider.embed(text, model) }
            FloatArray(embedding.size) { embedding[it].toFloat() }
        }
    }
}
