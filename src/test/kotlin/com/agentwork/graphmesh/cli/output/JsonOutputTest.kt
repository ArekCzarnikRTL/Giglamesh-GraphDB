package com.agentwork.graphmesh.cli.output

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonOutputTest {

    private val lines = mutableListOf<String>()
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val out = JsonOutput({ lines += it.toString() }, mapper)

    @Test
    fun `writeCollections emits a valid JSON array`() {
        out.writeCollections(listOf(
            CollectionView(id = "c1", name = "First", description = "d", tags = listOf("a", "b"), createdAt = "2026-01-01T00:00:00Z")
        ))

        val node = mapper.readTree(lines.single())
        assertTrue(node.isArray)
        assertEquals("c1", node[0]["id"].asText())
        assertEquals("First", node[0]["name"].asText())
        assertEquals(2, node[0]["tags"].size())
    }

    @Test
    fun `writeGraphRag emits structured object`() {
        out.writeGraphRag(GraphRagResponseView(
            sessionId = "s-1",
            answer = "The answer",
            retrievedEdgeCount = 12,
            durationMs = 345,
            selectedEdges = listOf(
                SelectedEdgeView("S", "P", "O", "because", 0.9)
            )
        ))

        val node = mapper.readTree(lines.single())
        assertEquals("The answer", node["answer"].asText())
        assertEquals(12, node["retrievedEdgeCount"].asInt())
        assertEquals("S", node["selectedEdges"][0]["subject"].asText())
    }

    @Test
    fun `writeMessage emits a JSON string with message field`() {
        out.writeMessage("hello")

        val node = mapper.readTree(lines.single())
        assertEquals("hello", node["message"].asText())
    }
}
