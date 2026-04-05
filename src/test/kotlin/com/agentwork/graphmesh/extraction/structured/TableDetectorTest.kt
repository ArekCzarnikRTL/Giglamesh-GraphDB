package com.agentwork.graphmesh.extraction.structured

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableDetectorTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `parseDetectionResult parses valid detection with table`() {
        val json = """{"has_table": true, "confidence": 0.85, "description": "Preisliste mit Produkten"}"""
        val result = parseDetectionResult(json)
        assertTrue(result.hasTable)
        assertEquals(0.85, result.confidence)
        assertEquals("Preisliste mit Produkten", result.tableDescription)
    }

    @Test
    fun `parseDetectionResult parses no-table response`() {
        val json = """{"has_table": false, "confidence": 0.1, "description": null}"""
        val result = parseDetectionResult(json)
        assertFalse(result.hasTable)
        assertEquals(0.1, result.confidence)
        assertNull(result.tableDescription)
    }

    @Test
    fun `parseDetectionResult handles missing optional fields`() {
        val json = """{"has_table": true, "confidence": 0.9}"""
        val result = parseDetectionResult(json)
        assertTrue(result.hasTable)
        assertEquals(0.9, result.confidence)
        assertNull(result.tableDescription)
    }

    @Test
    fun `parseDetectionResult strips markdown code fences`() {
        val json = "```json\n{\"has_table\": true, \"confidence\": 0.8, \"description\": \"Test\"}\n```"
        val result = parseDetectionResult(json)
        assertTrue(result.hasTable)
        assertEquals(0.8, result.confidence)
    }

    @Test
    fun `parseDetectionResult returns false for malformed JSON`() {
        val result = parseDetectionResult("not valid json at all")
        assertFalse(result.hasTable)
        assertEquals(0.0, result.confidence)
    }

    @Test
    fun `parseDetectionResult returns false for empty input`() {
        val result = parseDetectionResult("")
        assertFalse(result.hasTable)
        assertEquals(0.0, result.confidence)
    }

    @Test
    fun `parseDetectionResult handles missing has_table field`() {
        val json = """{"confidence": 0.5, "description": "Something"}"""
        val result = parseDetectionResult(json)
        assertFalse(result.hasTable)
    }

    private fun parseDetectionResult(response: String): DetectionResult {
        return try {
            val cleaned = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val map = objectMapper.readValue<Map<String, Any?>>(cleaned)
            DetectionResult(
                hasTable = map["has_table"] as? Boolean ?: false,
                confidence = (map["confidence"] as? Number)?.toDouble() ?: 0.0,
                tableDescription = map["description"] as? String
            )
        } catch (_: Exception) {
            DetectionResult(hasTable = false, confidence = 0.0)
        }
    }
}
