package com.agentwork.graphmesh.librarian

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LibrarianServiceTest {

    private lateinit var store: InMemoryDocumentStore

    @BeforeEach
    fun setup() {
        store = InMemoryDocumentStore()
    }

    @Test
    fun `save and findById roundtrip`() {
        val doc = Document(
            id = "doc-1",
            collectionId = "coll-1",
            title = "Test Document",
            mimeType = "application/pdf"
        )
        store.save(doc)
        val found = store.findById("doc-1")
        assertNotNull(found)
        assertEquals("Test Document", found.title)
    }

    @Test
    fun `findByCollection returns only matching type`() {
        store.save(Document(id = "doc-1", collectionId = "coll-1", type = DocumentType.SOURCE))
        store.save(Document(id = "doc-1/p1", collectionId = "coll-1", type = DocumentType.PAGE, parentId = "doc-1"))
        store.save(Document(id = "doc-2", collectionId = "coll-1", type = DocumentType.SOURCE))

        val sources = store.findByCollection("coll-1", DocumentType.SOURCE)
        assertEquals(2, sources.size)

        val pages = store.findByCollection("coll-1", DocumentType.PAGE)
        assertEquals(1, pages.size)
    }

    @Test
    fun `findChildren returns direct children`() {
        store.save(Document(id = "doc-1", collectionId = "coll-1"))
        store.save(Document(id = "doc-1/p1", collectionId = "coll-1", parentId = "doc-1", type = DocumentType.PAGE))
        store.save(Document(id = "doc-1/p2", collectionId = "coll-1", parentId = "doc-1", type = DocumentType.PAGE))
        store.save(Document(id = "doc-1/p1/c1", collectionId = "coll-1", parentId = "doc-1/p1", type = DocumentType.CHUNK))

        val children = store.findChildren("doc-1")
        assertEquals(2, children.size)

        val grandchildren = store.findChildren("doc-1/p1")
        assertEquals(1, grandchildren.size)
    }

    @Test
    fun `updateState changes document state`() {
        store.save(Document(id = "doc-1", collectionId = "coll-1", state = DocumentState.UPLOADED))
        store.updateState("doc-1", DocumentState.PROCESSING)

        assertEquals(DocumentState.PROCESSING, store.findById("doc-1")?.state)
    }

    @Test
    fun `deleteWithChildren removes parent and all descendants`() {
        store.save(Document(id = "doc-1", collectionId = "coll-1"))
        store.save(Document(id = "doc-1/p1", collectionId = "coll-1", parentId = "doc-1", type = DocumentType.PAGE))
        store.save(Document(id = "doc-1/p1/c1", collectionId = "coll-1", parentId = "doc-1/p1", type = DocumentType.CHUNK))

        store.deleteWithChildren("doc-1")

        assertNull(store.findById("doc-1"))
        assertNull(store.findById("doc-1/p1"))
        assertNull(store.findById("doc-1/p1/c1"))
    }

    @Test
    fun `document ID hierarchy follows convention`() {
        val sourceId = "doc-123"
        val pageId = "$sourceId/p1"
        val chunkId = "$pageId/c1"

        store.save(Document(id = sourceId, collectionId = "coll-1"))
        store.save(Document(id = pageId, collectionId = "coll-1", parentId = sourceId, type = DocumentType.PAGE))
        store.save(Document(id = chunkId, collectionId = "coll-1", parentId = pageId, type = DocumentType.CHUNK))

        assertEquals("doc-123", store.findById(sourceId)?.id)
        assertEquals("doc-123/p1", store.findById(pageId)?.id)
        assertEquals("doc-123/p1/c1", store.findById(chunkId)?.id)
    }
}

class InMemoryDocumentStore : DocumentStore {
    private val docs = mutableMapOf<String, Document>()

    override fun save(document: Document) {
        docs[document.id] = document
    }

    override fun findById(id: String): Document? = docs[id]

    override fun findByCollection(collectionId: String, type: DocumentType?): List<Document> {
        val effectiveType = type ?: DocumentType.SOURCE
        return docs.values.filter { it.collectionId == collectionId && it.type == effectiveType }
    }

    override fun findChildren(parentId: String): List<Document> =
        docs.values.filter { it.parentId == parentId }

    override fun updateState(id: String, state: DocumentState) {
        val doc = docs[id] ?: return
        docs[id] = doc.copy(state = state, updatedAt = java.time.Instant.now())
    }

    override fun delete(id: String) {
        docs.remove(id)
    }

    override fun deleteWithChildren(id: String) {
        val children = findChildren(id)
        for (child in children) {
            deleteWithChildren(child.id)
        }
        delete(id)
    }
}
