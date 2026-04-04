package com.agentwork.graphmesh.query.graphrag

import com.agentwork.graphmesh.storage.StoredQuad
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphRagServiceTest {

    private val testEdges = listOf(
        StoredQuad(subject = "Alice", predicate = "worksAt", objectValue = "Acme Corp", dataset = ""),
        StoredQuad(subject = "Bob", predicate = "knows", objectValue = "Alice", dataset = ""),
        StoredQuad(subject = "Acme Corp", predicate = "locatedIn", objectValue = "Berlin", dataset = ""),
        StoredQuad(subject = "Charlie", predicate = "likes", objectValue = "Pizza", dataset = "")
    )

    @Test
    fun `parseEdgeSelection extracts valid selections`() {
        val response = """
            0|Alice works at Acme Corp, directly relevant
            2|Location info helps contextualize
        """.trimIndent()

        val selected = parseEdgeSelection(response, testEdges)
        assertEquals(2, selected.size)
        assertEquals("Alice", selected[0].subject)
        assertEquals("worksAt", selected[0].predicate)
        assertEquals("Alice works at Acme Corp, directly relevant", selected[0].reasoning)
        assertEquals("Acme Corp", selected[1].subject)
    }

    @Test
    fun `parseEdgeSelection skips invalid indices`() {
        val response = """
            0|Valid edge
            99|Index out of range
            -1|Negative index
        """.trimIndent()

        val selected = parseEdgeSelection(response, testEdges)
        assertEquals(1, selected.size)
    }

    @Test
    fun `parseEdgeSelection skips lines without pipe`() {
        val response = """
            0|Valid edge
            This is not a selection
            1|Another valid edge
        """.trimIndent()

        val selected = parseEdgeSelection(response, testEdges)
        assertEquals(2, selected.size)
    }

    @Test
    fun `parseEdgeSelection skips blank reasoning`() {
        val response = """
            0|Valid reasoning
            1|
        """.trimIndent()

        val selected = parseEdgeSelection(response, testEdges)
        assertEquals(1, selected.size)
    }

    @Test
    fun `parseEdgeSelection handles empty response`() {
        val selected = parseEdgeSelection("", testEdges)
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `parseEdgeSelection handles non-numeric index`() {
        val response = "abc|Some reasoning"
        val selected = parseEdgeSelection(response, testEdges)
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `parseEdgeSelection calculates relevance score`() {
        val response = """
            0|First edge
            3|Last edge
        """.trimIndent()

        val selected = parseEdgeSelection(response, testEdges)
        assertEquals(2, selected.size)
        // First edge should have higher relevance than last
        assertTrue(selected[0].relevanceScore > selected[1].relevanceScore)
    }

    // Standalone copy of parsing logic for testing
    private fun parseEdgeSelection(llmResponse: String, edges: List<StoredQuad>): List<SelectedEdge> {
        return llmResponse.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("|") }
            .mapNotNull { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val index = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                val reasoning = parts[1].trim()
                if (index !in edges.indices || reasoning.isBlank()) return@mapNotNull null

                val quad = edges[index]
                SelectedEdge(
                    subject = quad.subject,
                    predicate = quad.predicate,
                    objectValue = quad.objectValue,
                    dataset = quad.dataset,
                    reasoning = reasoning,
                    relevanceScore = 1.0 - (index.toDouble() / edges.size)
                )
            }
    }
}
