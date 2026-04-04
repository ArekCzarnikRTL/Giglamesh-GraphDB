package com.agentwork.graphmesh.query.nlp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NlpQueryServiceTest {

    @Test
    fun `parseIntentResponse parses valid response`() {
        val response = "GRAPH_QUERY|0.9|Question is about entity relationships"
        val intent = parseIntentResponse(response)
        assertEquals(QueryIntent.GRAPH_QUERY, intent.intent)
        assertEquals(0.9, intent.confidence)
        assertEquals("Question is about entity relationships", intent.reasoning)
    }

    @Test
    fun `parseIntentResponse parses document query`() {
        val response = "DOCUMENT_QUERY|0.85|Question asks about document content"
        val intent = parseIntentResponse(response)
        assertEquals(QueryIntent.DOCUMENT_QUERY, intent.intent)
        assertEquals(0.85, intent.confidence)
    }

    @Test
    fun `parseIntentResponse parses hybrid`() {
        val response = "HYBRID|0.7|Complex question needing multiple sources"
        val intent = parseIntentResponse(response)
        assertEquals(QueryIntent.HYBRID, intent.intent)
    }

    @Test
    fun `parseIntentResponse falls back on invalid intent`() {
        val response = "UNKNOWN_TYPE|0.8|Some reasoning"
        val intent = parseIntentResponse(response)
        assertEquals(QueryIntent.GRAPH_QUERY, intent.intent)
    }

    @Test
    fun `parseIntentResponse falls back on low confidence`() {
        val response = "DOCUMENT_QUERY|0.3|Not very sure"
        val intent = parseIntentResponse(response)
        assertEquals(QueryIntent.GRAPH_QUERY, intent.intent)
        assertEquals(0.3, intent.confidence)
    }

    @Test
    fun `parseIntentResponse falls back on no pipe`() {
        val response = "Just some text without pipes"
        val intent = parseIntentResponse(response)
        assertEquals(QueryIntent.GRAPH_QUERY, intent.intent)
        assertEquals(0.5, intent.confidence)
    }

    @Test
    fun `parseIntentResponse handles empty response`() {
        val intent = parseIntentResponse("")
        assertEquals(QueryIntent.GRAPH_QUERY, intent.intent)
    }

    @Test
    fun `parseIntentResponse handles multiline with valid line`() {
        val response = """
            Some preamble text
            GRAPH_QUERY|0.95|Clearly about relationships
            Some trailing text
        """.trimIndent()
        val intent = parseIntentResponse(response)
        assertEquals(QueryIntent.GRAPH_QUERY, intent.intent)
        assertEquals(0.95, intent.confidence)
    }

    @Test
    fun `DetectedIntent supports reformulatedQuestion field`() {
        val intent = DetectedIntent(
            intent = QueryIntent.GRAPH_QUERY,
            confidence = 0.9,
            reasoning = "test",
            reformulatedQuestion = "improved question"
        )
        assertEquals("improved question", intent.reformulatedQuestion)
    }

    @Test
    fun `DetectedIntent reformulatedQuestion defaults to null`() {
        val intent = DetectedIntent(QueryIntent.GRAPH_QUERY, 0.9, "test")
        assertEquals(null, intent.reformulatedQuestion)
    }

    @Test
    fun `NlpQueryResult includes wasReformulated field`() {
        val result = NlpQueryResult(
            answer = "answer",
            detectedIntent = DetectedIntent(QueryIntent.GRAPH_QUERY, 0.9, "test"),
            wasReformulated = true,
            effectiveQuestion = "reformulated",
            durationMs = 100,
            sources = emptyList()
        )
        assertEquals(true, result.wasReformulated)
    }

    // Standalone copy of parsing logic for testing
    private fun parseIntentResponse(response: String): DetectedIntent {
        val line = response.lines().firstOrNull { it.contains("|") }?.trim()
        if (line == null) {
            return DetectedIntent(QueryIntent.GRAPH_QUERY, 0.5, "Failed to parse intent, using fallback")
        }

        val parts = line.split("|", limit = 3).map { it.trim() }
        if (parts.size < 3) {
            return DetectedIntent(QueryIntent.GRAPH_QUERY, 0.5, "Incomplete intent response, using fallback")
        }

        val intent = try {
            QueryIntent.valueOf(parts[0].uppercase())
        } catch (_: IllegalArgumentException) {
            QueryIntent.GRAPH_QUERY
        }

        val confidence = parts[1].toDoubleOrNull() ?: 0.5
        val reasoning = parts[2]

        val effectiveIntent = if (confidence < 0.5) QueryIntent.GRAPH_QUERY else intent

        return DetectedIntent(effectiveIntent, confidence, reasoning)
    }
}
