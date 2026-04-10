package com.agentwork.graphmesh.query

import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.storage.InMemoryQuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectionContentTypeServiceTest {

    private val quadStore = InMemoryQuadStore()
    private val librarianService = mockk<LibrarianService>()
    private val service = CollectionContentTypeService(quadStore, librarianService)

    @Test
    fun `hasTriples returns true when quads exist`() {
        every { librarianService.findByCollection(any(), any()) } returns emptyList()
        quadStore.insert("col1", StoredQuad("s", "p", "o", ""))
        assertTrue(service.hasTriples("col1"))
    }

    @Test
    fun `hasTriples returns false for empty collection`() {
        every { librarianService.findByCollection(any(), any()) } returns emptyList()
        assertFalse(service.hasTriples("empty"))
    }

    @Test
    fun `hasDocuments returns true when documents exist`() {
        every { librarianService.findByCollection("col1", any()) } returns listOf(mockk())
        assertTrue(service.hasDocuments("col1"))
    }

    @Test
    fun `hasDocuments returns false for empty collection`() {
        every { librarianService.findByCollection("empty", any()) } returns emptyList()
        assertFalse(service.hasDocuments("empty"))
    }

    @Test
    fun `isMixed returns true when both exist`() {
        quadStore.insert("mixed", StoredQuad("s", "p", "o", ""))
        every { librarianService.findByCollection("mixed", any()) } returns listOf(mockk())
        assertTrue(service.isMixed("mixed"))
    }

    @Test
    fun `invalidate clears cache so next call re-checks`() {
        every { librarianService.findByCollection(any(), any()) } returns emptyList()
        assertFalse(service.hasTriples("col2"))
        quadStore.insert("col2", StoredQuad("s", "p", "o", ""))
        assertFalse(service.hasTriples("col2"))  // still cached
        service.invalidate("col2")
        assertTrue(service.hasTriples("col2"))  // re-checked
    }
}
