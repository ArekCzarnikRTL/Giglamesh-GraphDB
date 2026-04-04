package com.agentwork.graphmesh.librarian

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration," +
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    ]
)
@ActiveProfiles("test")
class CassandraDocumentStoreIntegrationTest {

    @Autowired
    lateinit var store: CassandraDocumentStore

    @Test
    fun `save and findById roundtrip`() {
        val doc = Document(
            id = "doc-${UUID.randomUUID()}",
            collectionId = "coll-test",
            title = "Test PDF",
            mimeType = "application/pdf",
            contentUri = "doc/${UUID.randomUUID()}"
        )
        store.save(doc)

        val found = store.findById(doc.id)
        assertNotNull(found)
        assertEquals(doc.title, found.title)
        assertEquals(doc.mimeType, found.mimeType)
        assertEquals(doc.collectionId, found.collectionId)
    }

    @Test
    fun `findByCollection returns source documents`() {
        val collId = "coll-${UUID.randomUUID()}"
        store.save(Document(id = "doc-${UUID.randomUUID()}", collectionId = collId, type = DocumentType.SOURCE))
        store.save(Document(id = "doc-${UUID.randomUUID()}", collectionId = collId, type = DocumentType.SOURCE))

        val sources = store.findByCollection(collId, DocumentType.SOURCE)
        assertEquals(2, sources.size)
    }

    @Test
    fun `findChildren returns direct children`() {
        val parentId = "doc-${UUID.randomUUID()}"
        val childId1 = "$parentId/p1"
        val childId2 = "$parentId/p2"

        store.save(Document(id = parentId, collectionId = "coll-test"))
        store.save(Document(id = childId1, collectionId = "coll-test", parentId = parentId, type = DocumentType.PAGE))
        store.save(Document(id = childId2, collectionId = "coll-test", parentId = parentId, type = DocumentType.PAGE))

        val children = store.findChildren(parentId)
        assertEquals(2, children.size)
    }

    @Test
    fun `updateState changes state`() {
        val doc = Document(id = "doc-${UUID.randomUUID()}", collectionId = "coll-test", state = DocumentState.UPLOADED)
        store.save(doc)

        store.updateState(doc.id, DocumentState.PROCESSING)
        assertEquals(DocumentState.PROCESSING, store.findById(doc.id)?.state)
    }

    @Test
    fun `delete removes document`() {
        val doc = Document(id = "doc-${UUID.randomUUID()}", collectionId = "coll-test")
        store.save(doc)

        store.delete(doc.id)
        assertNull(store.findById(doc.id))
    }

    @Test
    fun `deleteWithChildren removes parent and descendants`() {
        val parentId = "doc-${UUID.randomUUID()}"
        val childId = "$parentId/p1"
        val grandchildId = "$childId/c1"

        store.save(Document(id = parentId, collectionId = "coll-test"))
        store.save(Document(id = childId, collectionId = "coll-test", parentId = parentId, type = DocumentType.PAGE))
        store.save(Document(id = grandchildId, collectionId = "coll-test", parentId = childId, type = DocumentType.CHUNK))

        store.deleteWithChildren(parentId)

        assertNull(store.findById(parentId))
        assertNull(store.findById(childId))
        assertNull(store.findById(grandchildId))
    }
}
