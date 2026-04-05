package com.agentwork.graphmesh.api.mcp

import com.agentwork.graphmesh.collection.Collection
import com.agentwork.graphmesh.collection.CollectionService
import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentState
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.query.docrag.DocumentRagQuery
import com.agentwork.graphmesh.query.docrag.DocumentRagResult
import com.agentwork.graphmesh.query.docrag.DocumentRagService
import com.agentwork.graphmesh.query.docrag.SourceAttribution
import com.agentwork.graphmesh.query.graphrag.GraphRagQuery
import com.agentwork.graphmesh.query.graphrag.GraphRagResult
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import com.agentwork.graphmesh.query.graphrag.SelectedEdge
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphMeshMcpToolsTest {

    private val graphRagService = mockk<GraphRagService>()
    private val documentRagService = mockk<DocumentRagService>()
    private val collectionService = mockk<CollectionService>()
    private val librarianService = mockk<LibrarianService>()

    private val tools = GraphMeshMcpTools(
        graphRagService, documentRagService, collectionService, librarianService
    )

    // --- knowledgeQuery ---

    @Test
    fun `knowledgeQuery formats answer with sources`() {
        every { graphRagService.query(any()) } returns GraphRagResult(
            answer = "Alice works at Acme Corp.",
            selectedEdges = listOf(
                SelectedEdge("Alice", "worksAt", "Acme Corp", "ds1", "Direct relationship", 0.9)
            ),
            retrievedEdgeCount = 50,
            durationMs = 100
        )

        val output = tools.knowledgeQuery("Who does Alice work for?", "col-1", null)

        assertTrue(output.contains("Alice works at Acme Corp."))
        assertTrue(output.contains("Alice --[worksAt]--> Acme Corp"))
        assertTrue(output.contains("Reasoning: Direct relationship"))
        assertTrue(output.contains("1 edges from 50 retrieved"))
    }

    @Test
    fun `knowledgeQuery uses default maxEdges when null`() {
        every { graphRagService.query(any()) } returns GraphRagResult(
            answer = "Answer", selectedEdges = emptyList(), retrievedEdgeCount = 0, durationMs = 0
        )

        tools.knowledgeQuery("question", "col-1", null)

        verify { graphRagService.query(GraphRagQuery("question", "col-1", maxEdges = 150)) }
    }

    @Test
    fun `knowledgeQuery passes custom maxEdges`() {
        every { graphRagService.query(any()) } returns GraphRagResult(
            answer = "Answer", selectedEdges = emptyList(), retrievedEdgeCount = 0, durationMs = 0
        )

        tools.knowledgeQuery("question", "col-1", 50)

        verify { graphRagService.query(GraphRagQuery("question", "col-1", maxEdges = 50)) }
    }

    // --- documentQuery ---

    @Test
    fun `documentQuery formats answer with sources`() {
        every { documentRagService.query(any()) } returns DocumentRagResult(
            answer = "The document discusses AI.",
            sources = listOf(
                SourceAttribution("chunk-1", "doc-1", "AI Paper", 3, 0.85f, "AI is transforming...")
            ),
            retrievedChunkCount = 5,
            durationMs = 100
        )

        val output = tools.documentQuery("What is AI?", "col-1", null)

        assertTrue(output.contains("The document discusses AI."))
        assertTrue(output.contains("AI Paper"))
        assertTrue(output.contains("page 3"))
        assertTrue(output.contains("score: ${"%.2f".format(0.85f)}"))
        assertTrue(output.contains("1 chunks"))
    }

    @Test
    fun `documentQuery uses default topK when null`() {
        every { documentRagService.query(any()) } returns DocumentRagResult(
            answer = "Answer", sources = emptyList(), retrievedChunkCount = 0, durationMs = 0
        )

        tools.documentQuery("question", "col-1", null)

        verify { documentRagService.query(DocumentRagQuery("question", "col-1", topK = 10)) }
    }

    @Test
    fun `documentQuery passes custom topK`() {
        every { documentRagService.query(any()) } returns DocumentRagResult(
            answer = "Answer", sources = emptyList(), retrievedChunkCount = 0, durationMs = 0
        )

        tools.documentQuery("question", "col-1", 5)

        verify { documentRagService.query(DocumentRagQuery("question", "col-1", topK = 5)) }
    }

    // --- collectionList ---

    @Test
    fun `collectionList formats collections`() {
        every { collectionService.findAll(any()) } returns listOf(
            Collection(id = "c1", name = "Research", description = "Research papers", tags = setOf("ai", "ml")),
            Collection(id = "c2", name = "Legal", description = "Legal docs", tags = setOf("compliance"))
        )

        val output = tools.collectionList(null)

        assertTrue(output.contains("Research (ID: c1): Research papers"))
        assertTrue(output.contains("Legal (ID: c2): Legal docs"))
    }

    @Test
    fun `collectionList returns message on empty result`() {
        every { collectionService.findAll(any()) } returns emptyList()

        val output = tools.collectionList(null)

        assertEquals("No collections found.", output)
    }

    @Test
    fun `collectionList passes parsed tags to service`() {
        every { collectionService.findAll(any()) } returns emptyList()

        tools.collectionList("ai, ml")

        verify { collectionService.findAll(setOf("ai", "ml")) }
    }

    // --- documentSearch ---

    @Test
    fun `documentSearch formats documents`() {
        every { librarianService.findByCollection("col-1", null) } returns listOf(
            Document(id = "d1", collectionId = "col-1", title = "Report Q1", type = DocumentType.SOURCE, state = DocumentState.EXTRACTED),
            Document(id = "d2", collectionId = "col-1", title = "Report Q2", type = DocumentType.SOURCE, state = DocumentState.PROCESSING)
        )

        val output = tools.documentSearch("col-1", null)

        assertTrue(output.contains("Report Q1 (ID: d1, type: SOURCE, state: EXTRACTED)"))
        assertTrue(output.contains("Report Q2 (ID: d2, type: SOURCE, state: PROCESSING)"))
    }

    @Test
    fun `documentSearch returns message on empty result`() {
        every { librarianService.findByCollection("col-1", null) } returns emptyList()

        val output = tools.documentSearch("col-1", null)

        assertEquals("No documents found.", output)
    }

    @Test
    fun `documentSearch filters by title case-insensitively`() {
        every { librarianService.findByCollection("col-1", null) } returns listOf(
            Document(id = "d1", collectionId = "col-1", title = "Report Q1", type = DocumentType.SOURCE, state = DocumentState.EXTRACTED),
            Document(id = "d2", collectionId = "col-1", title = "Invoice March", type = DocumentType.SOURCE, state = DocumentState.EXTRACTED)
        )

        val output = tools.documentSearch("col-1", "report")

        assertTrue(output.contains("Report Q1"))
        assertTrue(!output.contains("Invoice March"))
    }
}
