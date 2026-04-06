package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentFilterCriteria
import com.agentwork.graphmesh.librarian.DocumentPageResult
import com.agentwork.graphmesh.librarian.DocumentState
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.librarian.LibrarianService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DocumentControllerTest {

    private val librarian = mockk<LibrarianService>()
    private val controller = DocumentController(librarian)

    @Test
    fun `documents resolver applies pagination defaults and returns DocumentPagePayload`() {
        every {
            librarian.findByCollectionPaginated(
                collectionId = "col-1",
                filter = DocumentFilterCriteria(),
                page = 0,
                pageSize = 20
            )
        } returns DocumentPageResult(
            items = listOf(Document(id = "d1", collectionId = "col-1")),
            totalCount = 1,
            hasNextPage = false
        )

        val result = controller.documents(
            collectionId = "col-1",
            filter = null,
            page = null,
            pageSize = null
        )

        assertEquals(1, result.items.size)
        assertEquals(1, result.totalCount)
        assertEquals(false, result.hasNextPage)
    }

    @Test
    fun `documents resolver passes through filter fields`() {
        val capturedFilter = slot<DocumentFilterCriteria>()
        every {
            librarian.findByCollectionPaginated(
                collectionId = "col-1",
                filter = capture(capturedFilter),
                page = 1,
                pageSize = 5
            )
        } returns DocumentPageResult(items = emptyList(), totalCount = 0, hasNextPage = false)

        controller.documents(
            collectionId = "col-1",
            filter = DocumentFilterInput(
                type = DocumentType.SOURCE,
                state = DocumentState.EXTRACTED,
                search = "memo"
            ),
            page = 1,
            pageSize = 5
        )

        assertEquals(DocumentType.SOURCE, capturedFilter.captured.type)
        assertEquals(DocumentState.EXTRACTED, capturedFilter.captured.state)
        assertEquals("memo", capturedFilter.captured.search)
    }

    @Test
    fun `documentChunks delegates to LibrarianService findChunksOf`() {
        every { librarian.findChunksOf("doc-1") } returns listOf(
            Document(id = "doc-1/c1", collectionId = "col-1", parentId = "doc-1", type = DocumentType.CHUNK)
        )

        val chunks = controller.documentChunks("doc-1")

        assertEquals(1, chunks.size)
        assertEquals("doc-1/c1", chunks.first().id)
        verify(exactly = 1) { librarian.findChunksOf("doc-1") }
    }
}
