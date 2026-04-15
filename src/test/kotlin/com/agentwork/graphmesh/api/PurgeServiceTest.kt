package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.collection.Collection
import com.agentwork.graphmesh.collection.CollectionService
import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentState
import com.agentwork.graphmesh.librarian.DocumentStore
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.storage.OrphanSweepService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.DeleteTopicsResult
import org.apache.kafka.clients.admin.ListTopicsResult
import org.apache.kafka.common.KafkaFuture
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaAdmin
import java.time.Instant
import kotlin.test.assertEquals

class PurgeServiceTest {

    private val collectionService = mockk<CollectionService>(relaxed = true)
    private val documentStore = mockk<DocumentStore>(relaxed = true)
    private val ontologyService = mockk<OntologyService>(relaxed = true)
    private val kafkaAdmin = mockk<KafkaAdmin>()
    private val orphanSweepService = mockk<OrphanSweepService>(relaxed = true)

    private val service = PurgeService(collectionService, documentStore, ontologyService, kafkaAdmin, orphanSweepService)

    private fun collection(id: String, name: String) = Collection(
        id = id, name = name, description = "", tags = emptySet(),
        metadata = emptyMap(), createdAt = Instant.now(), updatedAt = Instant.now()
    )

    private fun document(id: String, collectionId: String) = Document(
        id = id, collectionId = collectionId, parentId = null,
        type = DocumentType.SOURCE, state = DocumentState.EXTRACTED,
        title = "doc", mimeType = "application/pdf",
        metadata = emptyMap(), createdAt = Instant.now()
    )

    @Test
    fun `purgeAll deletes collections, documents, ontologies`() {
        val coll1 = collection("c1", "coll-1")
        val doc1 = document("d1", "c1")

        every { collectionService.findAll() } returns listOf(coll1)
        every { documentStore.findByCollection("c1", DocumentType.SOURCE) } returns listOf(doc1)
        every { ontologyService.list() } returns listOf("ont-1", "ont-2")
        every { kafkaAdmin.configurationProperties } returns mapOf(
            "bootstrap.servers" to "localhost:19999"  // unreachable, Kafka deletion will fail gracefully
        )

        val result = service.purgeAll()

        assertEquals(1, result.collectionsDeleted)
        assertEquals(1, result.documentsDeleted)
        assertEquals(2, result.ontologiesDeleted)
        // Kafka deletion fails gracefully since no broker is available
        assertEquals(0, result.kafkaTopicsDeleted)

        verifyOrder {
            documentStore.deleteWithChildren("d1")
            collectionService.delete("c1")
        }
        verify { ontologyService.delete("ont-1") }
        verify { ontologyService.delete("ont-2") }
    }

    @Test
    fun `purgeAll with no data returns zeros`() {
        every { collectionService.findAll() } returns emptyList()
        every { ontologyService.list() } returns emptyList()
        every { kafkaAdmin.configurationProperties } returns mapOf(
            "bootstrap.servers" to "localhost:19999"
        )

        val result = service.purgeAll()

        assertEquals(0, result.collectionsDeleted)
        assertEquals(0, result.documentsDeleted)
        assertEquals(0, result.ontologiesDeleted)
    }
}
