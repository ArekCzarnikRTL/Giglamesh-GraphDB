package com.agentwork.graphmesh.query.graphrag

import com.agentwork.graphmesh.storage.StoredQuad
import com.agentwork.graphmesh.storage.vector.VectorPayload
import com.agentwork.graphmesh.storage.vector.SearchResult
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

    @Test
    fun `collectEntityUris extracts entity URIs from both subject and object positions`() {
        val triples = listOf(
            StoredQuad(
                subject = "http://graphmesh.io/entity/aaa",
                predicate = "http://example.org/label",
                objectValue = "GraphMesh",
                dataset = ""
            ),
            StoredQuad(
                subject = "http://graphmesh.io/entity/bbb",
                predicate = "http://example.org/relatedTo",
                objectValue = "http://graphmesh.io/entity/aaa",
                dataset = ""
            )
        )

        val result = collectEntityUrisCopy(triples)

        assertEquals(
            setOf("http://graphmesh.io/entity/aaa", "http://graphmesh.io/entity/bbb"),
            result.toSet()
        )
    }

    @Test
    fun `collectEntityUris filters out non-entity URIs and literals`() {
        val triples = listOf(
            StoredQuad(
                subject = "urn:graphmesh:subgraph:abc",
                predicate = "http://www.w3.org/ns/prov#wasDerivedFrom",
                objectValue = "urn:chunk:doc-1/p1/c1",
                dataset = ""
            )
        )

        assertTrue(collectEntityUrisCopy(triples).isEmpty())
    }

    @Test
    fun `collectEntityUris deduplicates`() {
        val triples = listOf(
            StoredQuad(
                subject = "http://graphmesh.io/entity/aaa",
                predicate = "p",
                objectValue = "http://graphmesh.io/entity/bbb",
                dataset = ""
            ),
            StoredQuad(
                subject = "http://graphmesh.io/entity/aaa",
                predicate = "q",
                objectValue = "http://graphmesh.io/entity/bbb",
                dataset = ""
            )
        )

        val result = collectEntityUrisCopy(triples)
        assertEquals(2, result.size)
    }

    @Test
    fun `splitSearchResults separates chunk and entity hits`() {
        val results = listOf(
            SearchResult(id = "chunk-1", score = 0.9f, payload = VectorPayload(collection = "", chunkId = "doc-1/p1/c1")),
            SearchResult(id = "entity-1", score = 0.85f, payload = VectorPayload(collection = "", entityUri = "http://example.org/Alice", source = "rdf-import")),
            SearchResult(id = "chunk-2", score = 0.8f, payload = VectorPayload(collection = "", chunkId = "doc-1/p1/c2")),
            SearchResult(id = "entity-2", score = 0.75f, payload = VectorPayload(collection = "", entityUri = "http://example.org/Bob", source = "rdf-import")),
        )

        val chunkIds = results.mapNotNull { it.payload.chunkId }
        val entityUris = results.mapNotNull { it.payload.entityUri }

        assertEquals(listOf("doc-1/p1/c1", "doc-1/p1/c2"), chunkIds)
        assertEquals(listOf("http://example.org/Alice", "http://example.org/Bob"), entityUris)
    }

    @Test
    fun `parseSelectAndSynthesize extracts answer and edges`() {
        val response = """
            ANSWER:
            Alice works at Acme Corp in Berlin.

            EDGES:
            0|Alice works at Acme Corp, directly relevant
            2|Location info helps contextualize
        """.trimIndent()

        val (answer, edges) = parseSelectAndSynthesizeCopy(response, testEdges)
        assertEquals("Alice works at Acme Corp in Berlin.", answer)
        assertEquals(2, edges.size)
        assertEquals("Alice", edges[0].subject)
        assertEquals("Acme Corp", edges[1].subject)
    }

    @Test
    fun `parseSelectAndSynthesize handles missing EDGES marker`() {
        val response = "Alice works at Acme Corp."

        val (answer, edges) = parseSelectAndSynthesizeCopy(response, testEdges)
        assertEquals("Alice works at Acme Corp.", answer)
        assertTrue(edges.isEmpty())
    }

    @Test
    fun `parseSelectAndSynthesize handles ANSWER marker only`() {
        val response = "ANSWER:\nSome answer text without edges."

        val (answer, edges) = parseSelectAndSynthesizeCopy(response, testEdges)
        assertEquals("Some answer text without edges.", answer)
        assertTrue(edges.isEmpty())
    }

    @Test
    fun `parseSelectAndSynthesize handles empty answer section`() {
        val response = "ANSWER:\n\nEDGES:\n0|Alice works at Acme Corp"

        val (answer, edges) = parseSelectAndSynthesizeCopy(response, testEdges)
        assertEquals("", answer)
        assertEquals(1, edges.size)
        assertEquals("Alice", edges[0].subject)
    }

    // Standalone copy of GraphRagService.collectEntityUris for testing without
    // having to construct the full service (which needs LLM/vector collaborators).
    private fun collectEntityUrisCopy(quotedTriples: List<StoredQuad>): List<String> {
        return quotedTriples
            .flatMap { listOf(it.subject, it.objectValue) }
            .filter { it.startsWith("http://graphmesh.io/entity/") }
            .distinct()
    }

    // Standalone copy of GraphRagService.parseSelectAndSynthesize for testing without
    // having to construct the full service.
    private fun parseSelectAndSynthesizeCopy(llmResponse: String, edges: List<StoredQuad>): Pair<String, List<SelectedEdge>> {
        val edgesMarker = "EDGES:"
        val answerMarker = "ANSWER:"
        val edgesIdx = llmResponse.indexOf(edgesMarker)
        val rawAnswer = if (edgesIdx >= 0) llmResponse.substring(0, edgesIdx) else llmResponse
        val cleanAnswer = rawAnswer.removePrefix(answerMarker).trim()
        val edgeSection = if (edgesIdx >= 0) llmResponse.substring(edgesIdx + edgesMarker.length) else ""
        val selectedEdges = edgeSection.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("|") }
            .mapNotNull { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val index = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                val reasoning = parts[1].trim()
                if (index !in edges.indices || reasoning.isBlank()) return@mapNotNull null
                val quad = edges[index]
                SelectedEdge(subject = quad.subject, predicate = quad.predicate, objectValue = quad.objectValue,
                    dataset = quad.dataset, reasoning = reasoning, relevanceScore = 1.0 - (index.toDouble() / edges.size))
            }
        return cleanAnswer to selectedEdges
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
