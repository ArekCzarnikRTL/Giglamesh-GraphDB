package com.agentwork.graphmesh.extraction.embedding

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EmbeddingConfigTest {

    @Test
    fun `default config uses text-embedding-3-small`() {
        val config = EmbeddingConfig()
        assertEquals("text-embedding-3-small", config.model)
    }

    @Test
    fun `config accepts custom model`() {
        val config = EmbeddingConfig(model = "text-embedding-ada-002")
        assertEquals("text-embedding-ada-002", config.model)
    }
}
