package com.agentwork.graphmesh.storage.vector

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionNamingTest {

    @Test
    fun `physicalName appends dimension`() {
        assertEquals("documents_384", CollectionNaming.physicalName("documents", 384))
    }

    @Test
    fun `physicalName with large dimension`() {
        assertEquals("embeddings_1536", CollectionNaming.physicalName("embeddings", 1536))
    }

    @Test
    fun `prefixPattern appends underscore`() {
        assertEquals("documents_", CollectionNaming.prefixPattern("documents"))
    }

    @Test
    fun `extractDimension returns dimension from valid name`() {
        assertEquals(384, CollectionNaming.extractDimension("documents_384"))
    }

    @Test
    fun `extractDimension returns null for invalid name`() {
        assertNull(CollectionNaming.extractDimension("documents_abc"))
    }

    @Test
    fun `extractDimension returns null for name without underscore`() {
        assertNull(CollectionNaming.extractDimension("documents"))
    }
}
