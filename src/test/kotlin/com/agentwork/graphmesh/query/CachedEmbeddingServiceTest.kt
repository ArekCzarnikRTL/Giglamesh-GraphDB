package com.agentwork.graphmesh.query

import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.extraction.embedding.EmbeddingConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CachedEmbeddingServiceTest {

    private var callCount = 0

    private val mockProvider = object : LLMEmbeddingProvider {
        override suspend fun embed(text: String, model: LLModel): List<Double> {
            callCount++
            return listOf(1.0, 2.0, 3.0)
        }
    }

    private val service = CachedEmbeddingService(
        embeddingProvider = mockProvider,
        embeddingConfig = EmbeddingConfig(model = "text-embedding-3-small")
    )

    @Test
    fun `embed returns correct vector`() {
        val result = service.embed("hello")
        assertContentEquals(floatArrayOf(1.0f, 2.0f, 3.0f), result)
    }

    @Test
    fun `embed caches result for same text`() {
        callCount = 0
        service.embed("same question")
        service.embed("same question")
        assertEquals(1, callCount, "Should call provider only once for same text")
    }

    @Test
    fun `embed calls provider for different texts`() {
        callCount = 0
        service.embed("question one")
        service.embed("question two")
        assertEquals(2, callCount, "Should call provider for each distinct text")
    }
}
