package com.agentwork.graphmesh.extraction.agent

import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.query.graphrag.GraphRagResult
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import com.agentwork.graphmesh.query.graphrag.SelectedEdge
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import com.agentwork.graphmesh.storage.ObjectType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ExtractionToolsTest {

    @Test
    fun `GraphQueryTool queries graph and returns answer with sources`() = runBlocking {
        val graphRagService = mockk<GraphRagService>()
        every { graphRagService.query(any()) } returns GraphRagResult(
            answer = "Photosynthesis converts sunlight to energy.",
            selectedEdges = listOf(
                SelectedEdge(
                    subject = "Photosynthesis",
                    predicate = "converts",
                    objectValue = "sunlight",
                    dataset = "",
                    reasoning = "Direct relationship",
                    relevanceScore = 1.0
                )
            ),
            retrievedEdgeCount = 10,
            durationMs = 42L
        )

        val tool = GraphQueryTool(graphRagService, "col-1")
        val result = tool.execute(GraphQueryTool.Args(question = "What is photosynthesis?"))

        assertContains(result, "Photosynthesis converts sunlight to energy.")
        assertContains(result, "Photosynthesis")
    }

    @Test
    fun `ValidateEntityTool returns EXISTS when entity found`() = runBlocking {
        val quadStore = mockk<QuadStore>()
        val entityId = EntityIdGenerator.generate("Photosynthesis").value
        every { quadStore.findByEntities("col-1", listOf(entityId)) } returns listOf(
            StoredQuad(
                subject = entityId,
                predicate = "http://www.w3.org/2000/01/rdf-schema#label",
                objectValue = "Photosynthesis",
                dataset = "",
                objectType = ObjectType.LITERAL
            )
        )

        val tool = ValidateEntityTool(quadStore, "col-1")
        val result = tool.execute(ValidateEntityTool.Args(entityName = "Photosynthesis"))

        assertContains(result, "EXISTS")
        assertContains(result, "label")
    }

    @Test
    fun `ValidateEntityTool returns NOT_FOUND when entity missing`() = runBlocking {
        val quadStore = mockk<QuadStore>()
        val entityId = EntityIdGenerator.generate("Unknown").value
        every { quadStore.findByEntities("col-1", listOf(entityId)) } returns emptyList()

        val tool = ValidateEntityTool(quadStore, "col-1")
        val result = tool.execute(ValidateEntityTool.Args(entityName = "Unknown"))

        assertEquals("NOT_FOUND", result)
    }

    @Test
    fun `ContextExpandTool returns sibling chunk texts`() = runBlocking {
        val librarianService = mockk<LibrarianService>()

        val chunk = Document(
            id = "chunk-1", collectionId = "col-1", parentId = "page-1",
            type = DocumentType.CHUNK, title = "Chunk 1"
        )
        val sibling = Document(
            id = "chunk-2", collectionId = "col-1", parentId = "page-1",
            type = DocumentType.CHUNK, title = "Chunk 2"
        )

        every { librarianService.findById("chunk-1") } returns chunk
        every { librarianService.findChildren("page-1") } returns listOf(chunk, sibling)
        every { librarianService.getContent("chunk-2") } returns "Sibling chunk text.".toByteArray()

        val tool = ContextExpandTool(librarianService, "chunk-1")
        val result = tool.execute(ContextExpandTool.Args(reason = "need more context"))

        assertContains(result, "Sibling chunk text.")
        assertContains(result, "chunk-2")
    }

    @Test
    fun `ContextExpandTool returns empty message when no parent`() = runBlocking {
        val librarianService = mockk<LibrarianService>()

        val chunk = Document(
            id = "chunk-1", collectionId = "col-1", parentId = null,
            type = DocumentType.CHUNK, title = "Chunk 1"
        )
        every { librarianService.findById("chunk-1") } returns chunk

        val tool = ContextExpandTool(librarianService, "chunk-1")
        val result = tool.execute(ContextExpandTool.Args(reason = "need context"))

        assertContains(result, "No sibling chunks")
    }
}
