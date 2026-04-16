package com.agentwork.graphmesh.storage.vector

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VectorPayloadTest {

    @Test
    fun `toMap includes all set fields`() {
        val payload = VectorPayload(
            collection = "my-col",
            chunkId = "c1",
            documentId = "d1",
            entityUri = "http://example.org/Alice",
            source = "rdf-import"
        )

        val map = payload.toMap()

        assertEquals("my-col", map["collection"])
        assertEquals("c1", map["chunk_id"])
        assertEquals("d1", map["document_id"])
        assertEquals("http://example.org/Alice", map["entity_uri"])
        assertEquals("rdf-import", map["source"])
    }

    @Test
    fun `toMap omits null fields`() {
        val payload = VectorPayload(collection = "col")

        val map = payload.toMap()

        assertEquals(1, map.size)
        assertEquals("col", map["collection"])
    }

    @Test
    fun `toMap includes extra entries`() {
        val payload = VectorPayload(
            collection = "col",
            extra = mapOf("custom_key" to "custom_value", "number" to 42)
        )

        val map = payload.toMap()

        assertEquals("col", map["collection"])
        assertEquals("custom_value", map["custom_key"])
        assertEquals(42, map["number"])
    }

    @Test
    fun `fromMap reconstructs chunk payload`() {
        val map = mapOf<String, Any>(
            "collection" to "my-col",
            "chunk_id" to "c1",
            "document_id" to "d1"
        )

        val payload = VectorPayload.fromMap(map)

        assertEquals("my-col", payload.collection)
        assertEquals("c1", payload.chunkId)
        assertEquals("d1", payload.documentId)
        assertNull(payload.entityUri)
        assertNull(payload.source)
        assertTrue(payload.extra.isEmpty())
    }

    @Test
    fun `fromMap reconstructs entity payload`() {
        val map = mapOf<String, Any>(
            "collection" to "my-col",
            "entity_uri" to "http://example.org/Alice",
            "source" to "rdf-import"
        )

        val payload = VectorPayload.fromMap(map)

        assertEquals("my-col", payload.collection)
        assertNull(payload.chunkId)
        assertNull(payload.documentId)
        assertEquals("http://example.org/Alice", payload.entityUri)
        assertEquals("rdf-import", payload.source)
    }

    @Test
    fun `fromMap puts unknown keys into extra`() {
        val map = mapOf<String, Any>(
            "collection" to "col",
            "chunk_id" to "c1",
            "custom" to "value",
            "count" to 7
        )

        val payload = VectorPayload.fromMap(map)

        assertEquals("c1", payload.chunkId)
        assertEquals(2, payload.extra.size)
        assertEquals("value", payload.extra["custom"])
        assertEquals(7, payload.extra["count"])
    }

    @Test
    fun `fromMap handles missing collection gracefully`() {
        val map = mapOf<String, Any>("chunk_id" to "c1")

        val payload = VectorPayload.fromMap(map)

        assertEquals("", payload.collection)
        assertEquals("c1", payload.chunkId)
    }

    @Test
    fun `fromMap handles empty map`() {
        val payload = VectorPayload.fromMap(emptyMap())

        assertEquals("", payload.collection)
        assertNull(payload.chunkId)
        assertNull(payload.documentId)
        assertNull(payload.entityUri)
        assertNull(payload.source)
        assertTrue(payload.extra.isEmpty())
    }

    @Test
    fun `roundtrip chunk payload`() {
        val original = VectorPayload(
            collection = "my-col",
            chunkId = "c1",
            documentId = "d1"
        )

        val roundtripped = VectorPayload.fromMap(original.toMap())

        assertEquals(original, roundtripped)
    }

    @Test
    fun `roundtrip entity payload`() {
        val original = VectorPayload(
            collection = "my-col",
            entityUri = "http://example.org/Alice",
            source = "rdf-import"
        )

        val roundtripped = VectorPayload.fromMap(original.toMap())

        assertEquals(original, roundtripped)
    }

    @Test
    fun `roundtrip with extra`() {
        val original = VectorPayload(
            collection = "col",
            chunkId = "c1",
            extra = mapOf("tag" to "test")
        )

        val roundtripped = VectorPayload.fromMap(original.toMap())

        assertEquals(original, roundtripped)
    }
}
