package com.agentwork.graphmesh.extraction.decoder

import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentState
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.librarian.LibrarianService
import io.mockk.*
import kotlin.test.Test
import kotlin.test.assertEquals

class TextDecoderServiceTest {

    private val librarian = mockk<LibrarianService>(relaxed = true)
    private val producer = mockk<PageExtractedProducer>(relaxed = true)
    private val splitter = MarkdownSplitter()
    private val service = TextDecoderService(librarian, producer, splitter)

    @Test
    fun `decode splits markdown and emits page events`() {
        val content = "# A\nContent of section A, long enough to be its own page please.\n# B\nContent of section B, also long enough to count as a full page here."
        val doc = Document(id = "doc-1", collectionId = "col-1", title = "x", mimeType = "text/markdown", type = DocumentType.SOURCE)
        val pageDoc1 = doc.copy(id = "page-1", type = DocumentType.PAGE)
        val pageDoc2 = doc.copy(id = "page-2", type = DocumentType.PAGE)

        every { librarian.getContent("doc-1") } returns content.toByteArray()
        every { librarian.findById("doc-1") } returns doc
        every { librarian.createChildDocument(any(), any(), any(), any(), any()) } returnsMany listOf(pageDoc1, pageDoc2)

        service.decode("doc-1")

        verify(exactly = 2) { librarian.createChildDocument(parentId = "doc-1", type = DocumentType.PAGE, any(), any(), mimeType = "text/plain") }
        verify(exactly = 2) { producer.send(any()) }
        verify { librarian.updateState("doc-1", DocumentState.EXTRACTED) }
    }

    @Test
    fun `decode on empty content marks as extracted without pages`() {
        val doc = Document(id = "doc-2", collectionId = "col-1", title = "x", mimeType = "text/plain", type = DocumentType.SOURCE)
        every { librarian.getContent("doc-2") } returns "".toByteArray()
        every { librarian.findById("doc-2") } returns doc

        service.decode("doc-2")

        verify(exactly = 0) { producer.send(any()) }
        verify { librarian.updateState("doc-2", DocumentState.EXTRACTED) }
    }

    @Test
    fun `decode sets FAILED on error`() {
        every { librarian.getContent("doc-3") } throws RuntimeException("boom")

        val ex = runCatching { service.decode("doc-3") }.exceptionOrNull()

        assertEquals(true, ex is TextDecodingException)
        verify { librarian.updateState("doc-3", DocumentState.FAILED) }
    }
}
