package com.agentwork.graphmesh.extraction.topic

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopicJsonlParsingTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `parses valid JSONL topics`() {
        val response = """
            {"topic": "Insolvenzrecht", "confidence": 0.95, "rationale": "Hauptthema des Textes"}
            {"topic": "Glaeubigerschutz", "confidence": 0.7, "rationale": "Nebenthema"}
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(2, results.size)
        assertEquals("Insolvenzrecht", results[0].topic)
        assertEquals(0.95, results[0].confidence)
        assertEquals("Hauptthema des Textes", results[0].rationale)
        assertEquals("Glaeubigerschutz", results[1].topic)
    }

    @Test
    fun `skips blank lines`() {
        val response = """
            {"topic": "A", "confidence": 0.9, "rationale": "r"}

            {"topic": "B", "confidence": 0.8, "rationale": "r"}
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `skips invalid JSON lines`() {
        val response = """
            {"topic": "A", "confidence": 0.9, "rationale": "r"}
            This is not JSON
            {"topic": "B", "confidence": 0.8, "rationale": "r"}
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `skips lines with missing topic field`() {
        val response = """
            {"topic": "A", "confidence": 0.9, "rationale": "r"}
            {"confidence": 0.8, "rationale": "missing topic"}
            {"topic": "C", "confidence": 0.7, "rationale": "r"}
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(2, results.size)
        assertEquals("A", results[0].topic)
        assertEquals("C", results[1].topic)
    }

    @Test
    fun `skips lines with blank topic`() {
        val response = """
            {"topic": "", "confidence": 0.9, "rationale": "r"}
            {"topic": "B", "confidence": 0.8, "rationale": "r"}
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(1, results.size)
        assertEquals("B", results[0].topic)
    }

    @Test
    fun `defaults confidence to 1_0 when missing`() {
        val response = """{"topic": "A", "rationale": "r"}"""
        val results = parseJsonlTopics(response)
        assertEquals(1, results.size)
        assertEquals(1.0, results[0].confidence)
    }

    @Test
    fun `clamps confidence to 0_0 to 1_0`() {
        val response = """
            {"topic": "A", "confidence": 1.5, "rationale": "r"}
            {"topic": "B", "confidence": -0.3, "rationale": "r"}
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(1.0, results[0].confidence)
        assertEquals(0.0, results[1].confidence)
    }

    @Test
    fun `rationale is optional`() {
        val response = """{"topic": "A", "confidence": 0.9}"""
        val results = parseJsonlTopics(response)
        assertEquals(1, results.size)
        assertEquals(null, results[0].rationale)
    }

    @Test
    fun `strips markdown code fences`() {
        val response = """
            ```json
            {"topic": "A", "confidence": 0.9, "rationale": "r"}
            {"topic": "B", "confidence": 0.8, "rationale": "r"}
            ```
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `handles empty response`() {
        val results = parseJsonlTopics("")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `handles truncated last line`() {
        val response = """
            {"topic": "A", "confidence": 0.9, "rationale": "r"}
            {"topic": "B", "confiden
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(1, results.size)
        assertEquals("A", results[0].topic)
    }

    // Standalone copy of parsing logic (same pattern as DefinitionExtractorServiceTest)
    private fun parseJsonlTopics(llmResponse: String): List<TopicResult> =
        llmResponse.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val map = objectMapper.readValue<Map<String, Any>>(line)
                    val topic = (map["topic"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val confidence = (map["confidence"] as? Number)?.toDouble() ?: 1.0
                    val rationale = map["rationale"] as? String
                    TopicResult(topic, confidence.coerceIn(0.0, 1.0), rationale)
                } catch (_: Exception) {
                    null
                }
            }
}
