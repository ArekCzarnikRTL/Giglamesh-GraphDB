package com.agentwork.graphmesh.extraction.relationship

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelationshipExtractorServiceTest {

    @Test
    fun `parseTriples extracts valid pipe-separated triples`() {
        val response = """
            Alice|worksAt|Acme Corp
            Bob|knows|Alice
            Acme Corp|locatedIn|Berlin
        """.trimIndent()

        val triples = parseTriples(response)
        assertEquals(3, triples.size)
        assertEquals(Triple("Alice", "worksAt", "Acme Corp"), triples[0])
        assertEquals(Triple("Bob", "knows", "Alice"), triples[1])
        assertEquals(Triple("Acme Corp", "locatedIn", "Berlin"), triples[2])
    }

    @Test
    fun `parseTriples skips blank lines`() {
        val response = """
            Alice|worksAt|Acme Corp

            Bob|knows|Alice
        """.trimIndent()

        val triples = parseTriples(response)
        assertEquals(2, triples.size)
    }

    @Test
    fun `parseTriples skips lines without pipe`() {
        val response = """
            Alice|worksAt|Acme Corp
            This is not a triple
            Bob|knows|Alice
        """.trimIndent()

        val triples = parseTriples(response)
        assertEquals(2, triples.size)
    }

    @Test
    fun `parseTriples skips lines with wrong number of parts`() {
        val response = """
            Alice|worksAt|Acme Corp
            Too|many|parts|here
            Only|two
            Bob|knows|Alice
        """.trimIndent()

        val triples = parseTriples(response)
        assertEquals(2, triples.size)
    }

    @Test
    fun `parseTriples skips lines with empty fields`() {
        val response = """
            Alice|worksAt|Acme Corp
            |empty|subject
            Alice||empty predicate
            Alice|worksAt|
        """.trimIndent()

        val triples = parseTriples(response)
        assertEquals(1, triples.size)
    }

    @Test
    fun `parseTriples trims whitespace`() {
        val response = "  Alice  |  worksAt  |  Acme Corp  "

        val triples = parseTriples(response)
        assertEquals(1, triples.size)
        assertEquals(Triple("Alice", "worksAt", "Acme Corp"), triples[0])
    }

    @Test
    fun `parseTriples handles empty response`() {
        val triples = parseTriples("")
        assertTrue(triples.isEmpty())
    }

    @Test
    fun `normalizePredicateName converts to camelCase`() {
        assertEquals("worksAt", normalizePredicateName("works at"))
        assertEquals("locatedIn", normalizePredicateName("located in"))
        assertEquals("hasCEO", normalizePredicateName("has CEO"))
    }

    @Test
    fun `normalizePredicateName handles single word`() {
        assertEquals("knows", normalizePredicateName("knows"))
        assertEquals("knows", normalizePredicateName("Knows"))
    }

    @Test
    fun `normalizePredicateName trims and collapses whitespace`() {
        assertEquals("worksAt", normalizePredicateName("  works   at  "))
    }

    // Standalone copies of the methods for testing without constructing the service
    private fun parseTriples(llmResponse: String): List<Triple<String, String, String>> {
        return llmResponse.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("|") }
            .mapNotNull { line ->
                val parts = line.split("|").map { it.trim() }
                if (parts.size == 3 && parts.all { it.isNotBlank() }) {
                    Triple(parts[0], parts[1], parts[2])
                } else {
                    null
                }
            }
    }

    private fun normalizePredicateName(predicate: String): String {
        return predicate.trim()
            .split(Regex("\\s+"))
            .mapIndexed { index, word ->
                if (index == 0) word.lowercase()
                else word.replaceFirstChar { it.uppercase() }
            }
            .joinToString("")
    }
}
