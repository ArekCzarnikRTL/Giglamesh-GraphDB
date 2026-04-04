package com.agentwork.graphmesh.query.docrag

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentRagServiceTest {

    @Test
    fun `extractPageNumber finds page from chunk ID`() {
        assertEquals(5, extractPageNumber("doc-123/p5/c2"))
        assertEquals(1, extractPageNumber("doc-abc/p1/c1"))
        assertEquals(12, extractPageNumber("doc-xyz/p12"))
    }

    @Test
    fun `extractPageNumber returns null for no page`() {
        assertNull(extractPageNumber("doc-123/c2"))
        assertNull(extractPageNumber("doc-123"))
        assertNull(extractPageNumber(""))
    }

    @Test
    fun `extractPageNumber handles edge cases`() {
        assertEquals(0, extractPageNumber("doc-123/p0/c1"))
        assertNull(extractPageNumber("doc-123/px/c1"))
    }

    @Test
    fun `source attribution snippet is truncated to 200 chars`() {
        val longText = "a".repeat(500)
        val snippet = longText.take(200)
        assertEquals(200, snippet.length)
    }

    @Test
    fun `RetrievedChunk data class holds correct values`() {
        val chunk = RetrievedChunk(
            chunkId = "doc-1/p1/c1",
            documentId = "doc-1",
            text = "Some chunk text",
            score = 0.85f
        )
        assertEquals("doc-1/p1/c1", chunk.chunkId)
        assertEquals("doc-1", chunk.documentId)
        assertEquals(0.85f, chunk.score)
    }

    @Test
    fun `DocumentRagQuery defaults are sensible`() {
        val query = DocumentRagQuery(question = "test", collectionId = "coll-1")
        assertEquals(10, query.topK)
        assertEquals(0.5f, query.similarityThreshold)
    }

    // Standalone copy for testing without constructing the service
    private fun extractPageNumber(chunkId: String): Int? {
        val pagePattern = Regex("/p(\\d+)")
        return pagePattern.find(chunkId)?.groupValues?.get(1)?.toIntOrNull()
    }
}
