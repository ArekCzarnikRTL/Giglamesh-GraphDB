package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.librarian.LibrarianService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentHierarchyControllerTest {

    private val librarian = mockk<LibrarianService>()
    private val controller = DocumentHierarchyController(librarian)

    @Test
    fun `returns null when root document does not exist`() {
        every { librarian.findById("doc-missing") } returns null

        val result = controller.documentHierarchy(collectionId = "col-1", documentId = "doc-missing")

        assertNull(result)
    }

    @Test
    fun `returns null when document belongs to different collection`() {
        every { librarian.findById("doc-1") } returns doc("doc-1", "other-col", DocumentType.SOURCE)

        val result = controller.documentHierarchy(collectionId = "col-1", documentId = "doc-1")

        assertNull(result)
    }

    @Test
    fun `builds a 3-level hierarchy`() {
        every { librarian.findById("doc-1") } returns doc("doc-1", "col-1", DocumentType.SOURCE, "Paper.pdf")
        every { librarian.findChildren("doc-1") } returns listOf(
            doc("doc-1/p1", "col-1", DocumentType.PAGE, "Page 1"),
            doc("doc-1/p2", "col-1", DocumentType.PAGE, "Page 2")
        )
        every { librarian.findChildren("doc-1/p1") } returns listOf(
            doc("doc-1/p1/c1", "col-1", DocumentType.CHUNK, "Chunk 1")
        )
        every { librarian.findChildren("doc-1/p2") } returns emptyList()
        every { librarian.findChildren("doc-1/p1/c1") } returns emptyList()

        val result = controller.documentHierarchy(collectionId = "col-1", documentId = "doc-1")!!

        assertEquals("doc-1", result.id)
        assertEquals("Paper.pdf", result.title)
        assertEquals("SOURCE", result.type)
        assertEquals(2, result.children.size)
        assertEquals(1, result.children.first { it.id == "doc-1/p1" }.children.size)
        assertEquals("Chunk 1", result.children.first { it.id == "doc-1/p1" }.children.single().title)
    }

    @Test
    fun `protects against cycles by not revisiting ids`() {
        every { librarian.findById("doc-1") } returns doc("doc-1", "col-1", DocumentType.SOURCE)
        // Pathological: child points back at parent
        every { librarian.findChildren("doc-1") } returns listOf(doc("doc-1", "col-1", DocumentType.SOURCE))

        val result = controller.documentHierarchy(collectionId = "col-1", documentId = "doc-1")!!

        // The duplicate id must not recurse infinitely
        assertTrue(result.children.isEmpty() || result.children.all { it.children.isEmpty() })
    }

    private fun doc(id: String, col: String, type: DocumentType, title: String = id) = Document(
        id = id,
        collectionId = col,
        type = type,
        title = title
    )
}
