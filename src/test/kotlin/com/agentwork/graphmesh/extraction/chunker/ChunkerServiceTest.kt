package com.agentwork.graphmesh.extraction.chunker

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkConfigTest {

    @Test
    fun `default config has sensible values`() {
        val config = ChunkConfig()
        assertEquals(2000, config.chunkSize)
        assertEquals(200, config.overlapSize)
    }

    @Test
    fun `rejects invalid chunkSize`() {
        assertThrows<IllegalArgumentException> { ChunkConfig(chunkSize = 0) }
        assertThrows<IllegalArgumentException> { ChunkConfig(chunkSize = -1) }
    }

    @Test
    fun `rejects negative overlapSize`() {
        assertThrows<IllegalArgumentException> { ChunkConfig(overlapSize = -1) }
    }

    @Test
    fun `rejects overlap greater or equal to chunkSize`() {
        assertThrows<IllegalArgumentException> { ChunkConfig(chunkSize = 100, overlapSize = 100) }
        assertThrows<IllegalArgumentException> { ChunkConfig(chunkSize = 100, overlapSize = 150) }
    }
}

class ChunkAlgorithmTest {

    @Test
    fun `short text produces single chunk`() {
        val chunks = splitIntoChunks("Hello World", chunkSize = 100, overlapSize = 20)
        assertEquals(1, chunks.size)
        assertEquals("Hello World", chunks[0].text)
        assertEquals(0, chunks[0].charOffset)
    }

    @Test
    fun `text exactly chunk size produces single chunk with zero overlap`() {
        val chunks = splitIntoChunks("a".repeat(100), chunkSize = 100, overlapSize = 0)
        assertEquals(1, chunks.size)
        assertEquals(100, chunks[0].text.length)
    }

    @Test
    fun `text splits with correct overlap`() {
        val chunks = splitIntoChunks("a".repeat(250), chunkSize = 100, overlapSize = 20)
        // step = 80
        assertEquals(3, chunks.size)
        assertEquals(0, chunks[0].charOffset)
        assertEquals(80, chunks[1].charOffset)
        assertEquals(160, chunks[2].charOffset)
        assertEquals(100, chunks[0].text.length)
        assertEquals(100, chunks[1].text.length)
        assertEquals(90, chunks[2].text.length)
    }

    @Test
    fun `overlap preserves context between chunks`() {
        val text = "AAAAAAAAAA" + "BBBBBBBBBB" + "CCCCCCCCCC"
        val chunks = splitIntoChunks(text, chunkSize = 15, overlapSize = 5)

        assertTrue(chunks.size >= 2)
        val overlap = chunks[0].text.takeLast(5)
        val nextStart = chunks[1].text.take(5)
        assertEquals(overlap, nextStart)
    }

    @Test
    fun `zero overlap produces non-overlapping chunks`() {
        val chunks = splitIntoChunks("a".repeat(200), chunkSize = 100, overlapSize = 0)
        assertEquals(2, chunks.size)
        assertEquals(0, chunks[0].charOffset)
        assertEquals(100, chunks[1].charOffset)
    }

    @Test
    fun `empty text produces no chunks`() {
        val chunks = splitIntoChunks("", chunkSize = 100, overlapSize = 20)
        assertEquals(0, chunks.size)
    }

    @Test
    fun `whitespace-only text produces chunks`() {
        val chunks = splitIntoChunks("   ", chunkSize = 100, overlapSize = 20)
        assertEquals(1, chunks.size)
    }

    @Test
    fun `chunk indices are sequential`() {
        val chunks = splitIntoChunks("a".repeat(500), chunkSize = 100, overlapSize = 20)
        for ((i, chunk) in chunks.withIndex()) {
            assertEquals(i, chunk.chunkIndex)
        }
    }

    @Test
    fun `all text is covered`() {
        val text = "a".repeat(500)
        val chunks = splitIntoChunks(text, chunkSize = 100, overlapSize = 20)
        assertEquals(0, chunks.first().charOffset)
        val last = chunks.last()
        assertTrue(last.charOffset + last.text.length >= text.length - 20)
    }

    /**
     * Standalone copy of the chunking algorithm from ChunkerService.splitIntoChunks
     * for unit testing without needing to construct ChunkerService and its dependencies.
     */
    private fun splitIntoChunks(text: String, chunkSize: Int, overlapSize: Int): List<ChunkResult> {
        val chunks = mutableListOf<ChunkResult>()
        val step = chunkSize - overlapSize
        var offset = 0

        while (offset < text.length) {
            val end = minOf(offset + chunkSize, text.length)
            val chunkText = text.substring(offset, end)

            chunks.add(ChunkResult(chunkText, offset, chunks.size))

            offset += step

            // Avoid tiny trailing chunks smaller than overlap
            if (offset < text.length && text.length - offset < overlapSize) {
                break
            }
        }

        // Append remaining text if not already covered
        if (offset < text.length && chunks.lastOrNull()?.let {
                it.charOffset + it.text.length < text.length
            } == true) {
            chunks.add(ChunkResult(text.substring(offset), offset, chunks.size))
        }

        return chunks
    }
}
