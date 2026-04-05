package com.agentwork.graphmesh.extraction.definition

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefinitionExtractorServiceTest {

    private val objectMapper = jacksonObjectMapper()

    // --- JSONL Parsing (standalone copy) ---

    @Test
    fun `parseJsonlDefinitions extracts valid definitions`() {
        val response = """
            {"entity": "Photosynthesis", "definition": "Process by which plants convert sunlight into chemical energy"}
            {"entity": "Chlorophyll", "definition": "Green pigment that enables photosynthesis"}
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(2, results.size)
        assertEquals("Photosynthesis", results[0].entity)
        assertEquals("Process by which plants convert sunlight into chemical energy", results[0].definition)
        assertEquals("Chlorophyll", results[1].entity)
    }

    @Test
    fun `parseJsonlDefinitions skips blank lines`() {
        val response = """
            {"entity": "A", "definition": "Def A"}

            {"entity": "B", "definition": "Def B"}
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `parseJsonlDefinitions skips invalid JSON lines`() {
        val response = """
            {"entity": "A", "definition": "Def A"}
            This is not JSON
            {"entity": "B", "definition": "Def B"}
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `parseJsonlDefinitions skips lines with missing fields`() {
        val response = """
            {"entity": "A", "definition": "Def A"}
            {"entity": "B"}
            {"definition": "orphan definition"}
            {"entity": "C", "definition": "Def C"}
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(2, results.size)
        assertEquals("A", results[0].entity)
        assertEquals("C", results[1].entity)
    }

    @Test
    fun `parseJsonlDefinitions skips lines with empty values`() {
        val response = """
            {"entity": "", "definition": "Def A"}
            {"entity": "B", "definition": ""}
            {"entity": "C", "definition": "Def C"}
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(1, results.size)
        assertEquals("C", results[0].entity)
    }

    @Test
    fun `parseJsonlDefinitions strips markdown code fences`() {
        val response = """
            ```json
            {"entity": "A", "definition": "Def A"}
            {"entity": "B", "definition": "Def B"}
            ```
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `parseJsonlDefinitions handles empty response`() {
        val results = parseJsonlDefinitions("")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `parseJsonlDefinitions handles truncated last line`() {
        val response = """
            {"entity": "A", "definition": "Def A"}
            {"entity": "B", "defini
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(1, results.size)
        assertEquals("A", results[0].entity)
    }

    // Standalone copy of parsing logic (same pattern as RelationshipExtractorServiceTest)
    private fun parseJsonlDefinitions(llmResponse: String): List<DefinitionResult> {
        return llmResponse.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val map = objectMapper.readValue<Map<String, String>>(line)
                    val entity = map["entity"]?.takeIf { it.isNotBlank() }
                    val definition = map["definition"]?.takeIf { it.isNotBlank() }
                    if (entity != null && definition != null) DefinitionResult(entity, definition) else null
                } catch (_: Exception) {
                    null
                }
            }
    }
}
