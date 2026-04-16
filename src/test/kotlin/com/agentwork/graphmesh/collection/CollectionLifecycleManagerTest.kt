package com.agentwork.graphmesh.collection

import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentStore
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.blob.BlobInfo
import com.agentwork.graphmesh.storage.blob.BlobStore
import com.agentwork.graphmesh.storage.vector.VectorStore
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectionLifecycleManagerTest {

    private lateinit var collectionStore: CollectionStore
    private lateinit var quadStore: QuadStore
    private lateinit var vectorStore: VectorStore
    private lateinit var blobStore: BlobStore
    private lateinit var documentStore: DocumentStore
    private lateinit var manager: CollectionLifecycleManager

    private val bucket = "graphmesh"
    private val collectionId = "col-123"

    @BeforeEach
    fun setup() {
        collectionStore = mockk()
        quadStore = mockk()
        vectorStore = mockk()
        blobStore = mockk()
        documentStore = mockk()
        manager = CollectionLifecycleManager(
            collectionStore = collectionStore,
            quadStore = quadStore,
            vectorStore = vectorStore,
            blobStore = blobStore,
            documentStore = documentStore,
            defaultBucket = bucket
        )
    }

    @Test
    fun `all backends succeed — PurgeResult complete is true`() {
        val doc = testDocument("doc-1", "doc/obj-1")
        stubAllSuccessful(listOf(doc))

        val result = manager.purge(collectionId)

        assertTrue(result.complete)
        assertTrue(result.quadsDeleted)
        assertTrue(result.vectorsDeleted)
        assertEquals(2, result.blobsDeleted) // 1 doc blob + 1 collection blob
        assertEquals(1, result.documentsDeleted)
        assertTrue(result.collectionRowDeleted)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `quad store fails — other backends still cleaned up`() {
        val doc = testDocument("doc-1", "doc/obj-1")
        every { quadStore.deleteCollection(collectionId) } throws RuntimeException("Cassandra down")
        every { vectorStore.deleteCollection(collectionId) } just runs
        stubDocumentAndBlobCleanup(listOf(doc))
        every { collectionStore.delete(collectionId) } just runs

        val result = manager.purge(collectionId)

        assertFalse(result.complete)
        assertFalse(result.quadsDeleted)
        assertTrue(result.vectorsDeleted)
        assertTrue(result.collectionRowDeleted)
        assertEquals(1, result.failures.size)
        assertEquals("quads", result.failures[0].step)

        // Verify other backends were still called
        verify { vectorStore.deleteCollection(collectionId) }
        verify { collectionStore.delete(collectionId) }
    }

    @Test
    fun `vector store fails — other backends still cleaned up`() {
        val doc = testDocument("doc-1", "doc/obj-1")
        every { quadStore.deleteCollection(collectionId) } just runs
        every { vectorStore.deleteCollection(collectionId) } throws RuntimeException("Qdrant timeout")
        stubDocumentAndBlobCleanup(listOf(doc))
        every { collectionStore.delete(collectionId) } just runs

        val result = manager.purge(collectionId)

        assertFalse(result.complete)
        assertTrue(result.quadsDeleted)
        assertFalse(result.vectorsDeleted)
        assertTrue(result.collectionRowDeleted)
        assertEquals(1, result.failures.size)
        assertEquals("vectors", result.failures[0].step)
    }

    @Test
    fun `purge on non-existent collection — idempotent, no crash`() {
        every { quadStore.deleteCollection(collectionId) } just runs
        every { vectorStore.deleteCollection(collectionId) } just runs
        every { documentStore.findByCollection(collectionId, null) } returns emptyList()
        every { blobStore.list(bucket, "collections/$collectionId/") } returns emptyList()
        every { collectionStore.delete(collectionId) } just runs

        val result = manager.purge(collectionId)

        assertTrue(result.complete)
        assertEquals(0, result.blobsDeleted)
        assertEquals(0, result.documentsDeleted)
    }

    @Test
    fun `document blobs are cleaned up — bug fix verified`() {
        val doc1 = testDocument("doc-1", "doc/obj-1")
        val doc2 = testDocument("doc-2", "doc/obj-2")
        stubAllSuccessful(listOf(doc1, doc2))

        val result = manager.purge(collectionId)

        verify { blobStore.delete(bucket, "doc/obj-1") }
        verify { blobStore.delete(bucket, "doc/obj-2") }
        assertTrue(result.complete)
        // 2 doc blobs + 1 collection blob
        assertEquals(3, result.blobsDeleted)
    }

    @Test
    fun `collection row is deleted last — crash-safe ordering`() {
        stubAllSuccessful(emptyList())

        manager.purge(collectionId)

        verifyOrder {
            quadStore.deleteCollection(collectionId)
            vectorStore.deleteCollection(collectionId)
            collectionStore.delete(collectionId)
        }
    }

    @Test
    fun `multiple steps fail — all failures collected`() {
        every { quadStore.deleteCollection(collectionId) } throws RuntimeException("Cassandra down")
        every { vectorStore.deleteCollection(collectionId) } throws RuntimeException("Qdrant down")
        every { documentStore.findByCollection(collectionId, null) } returns emptyList()
        every { blobStore.list(bucket, "collections/$collectionId/") } returns emptyList()
        every { collectionStore.delete(collectionId) } just runs

        val result = manager.purge(collectionId)

        assertFalse(result.complete)
        assertEquals(2, result.failures.size)
        assertEquals(setOf("quads", "vectors"), result.failures.map { it.step }.toSet())
    }

    @Test
    fun `documents with empty contentUri are skipped for blob delete`() {
        val docWithBlob = testDocument("doc-1", "doc/obj-1")
        val docWithoutBlob = testDocument("doc-2", "")
        stubAllSuccessful(listOf(docWithBlob, docWithoutBlob))

        val result = manager.purge(collectionId)

        verify { blobStore.delete(bucket, "doc/obj-1") }
        verify(exactly = 0) { blobStore.delete(bucket, "") }
        assertTrue(result.complete)
        // 1 doc blob (not the empty one) + 1 collection blob
        assertEquals(2, result.blobsDeleted)
    }

    private fun testDocument(id: String, contentUri: String) = Document(
        id = id,
        collectionId = collectionId,
        contentUri = contentUri,
        title = "Test $id"
    )

    private fun stubAllSuccessful(documents: List<Document>) {
        every { quadStore.deleteCollection(collectionId) } just runs
        every { vectorStore.deleteCollection(collectionId) } just runs
        stubDocumentAndBlobCleanup(documents)
        every { collectionStore.delete(collectionId) } just runs
    }

    private fun stubDocumentAndBlobCleanup(documents: List<Document>) {
        every { documentStore.findByCollection(collectionId, null) } returns documents
        documents.forEach { doc ->
            if (doc.contentUri.isNotEmpty()) {
                every { blobStore.delete(bucket, doc.contentUri) } just runs
            }
        }
        val collectionBlob = BlobInfo(
            key = "collections/$collectionId/some-file",
            size = 100,
            contentType = "application/octet-stream",
            lastModified = Instant.now()
        )
        every { blobStore.list(bucket, "collections/$collectionId/") } returns listOf(collectionBlob)
        every { blobStore.deleteBatch(bucket, listOf(collectionBlob.key)) } just runs
        documents.forEach { doc ->
            every { documentStore.deleteWithChildren(doc.id) } just runs
        }
    }
}
