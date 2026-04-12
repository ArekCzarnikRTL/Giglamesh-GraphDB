package com.agentwork.graphmesh.extraction.topic

import com.agentwork.graphmesh.rdf.EntityIdGenerator
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TopicDeduplicationTest {

    @Test
    fun `normalize trims whitespace`() {
        assertEquals("insolvenzrecht", normalize("  Insolvenzrecht  "))
    }

    @Test
    fun `normalize lowercases`() {
        assertEquals("insolvenzrecht", normalize("INSOLVENZRECHT"))
    }

    @Test
    fun `normalize collapses multiple spaces`() {
        assertEquals("eu datenschutz", normalize("EU   Datenschutz"))
    }

    @Test
    fun `normalize handles mixed whitespace`() {
        assertEquals("a b c", normalize("  A  B  C  "))
    }

    @Test
    fun `same normalized label produces same EntityId`() {
        val id1 = EntityIdGenerator.generate(normalize("Insolvenzrecht"))
        val id2 = EntityIdGenerator.generate(normalize("insolvenzrecht"))
        val id3 = EntityIdGenerator.generate(normalize("  INSOLVENZRECHT  "))
        assertEquals(id1, id2)
        assertEquals(id2, id3)
    }

    @Test
    fun `distinctBy normalized label removes duplicates`() {
        val topics = listOf(
            TopicResult("Insolvenzrecht", 0.9),
            TopicResult("insolvenzrecht", 0.7),
            TopicResult("Photosynthese", 0.8)
        )

        val deduped = topics.distinctBy { normalize(it.topic) }
        assertEquals(2, deduped.size)
        assertEquals("Insolvenzrecht", deduped[0].topic)
        assertEquals("Photosynthese", deduped[1].topic)
    }

    // Standalone copy of normalize (will be in TopicExtractorService)
    private fun normalize(topic: String): String =
        topic.trim().lowercase().replace(Regex("\\s+"), " ")
}
