package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.collection.CollectionService
import com.agentwork.graphmesh.librarian.DocumentStore
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.storage.OrphanSweepService
import org.apache.kafka.clients.admin.AdminClient
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.stereotype.Service

@Service
class PurgeService(
    private val collectionService: CollectionService,
    private val documentStore: DocumentStore,
    private val ontologyService: OntologyService,
    private val kafkaAdmin: KafkaAdmin,
    private val orphanSweepService: OrphanSweepService,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    data class PurgeResult(
        val collectionsDeleted: Int,
        val documentsDeleted: Int,
        val ontologiesDeleted: Int,
        val kafkaTopicsDeleted: Int,
        val orphanQuadTablesTruncated: Int,
        val orphanVectorCollectionsDeleted: Int,
        val orphanBlobsDeleted: Int,
        val durationMs: Long,
    )

    fun purgeAll(): PurgeResult {
        val start = System.currentTimeMillis()
        logger.warn("PURGE ALL DATA requested — deleting all content")

        val collections = collectionService.findAll()
        var documentsDeleted = 0
        for (collection in collections) {
            val docs = documentStore.findByCollection(collection.id, DocumentType.SOURCE)
            for (doc in docs) {
                documentStore.deleteWithChildren(doc.id)
                documentsDeleted++
            }
            collectionService.delete(collection.id)
        }
        logger.info("Purge: {} collections deleted, {} source documents deleted", collections.size, documentsDeleted)

        val ontologyKeys = ontologyService.list()
        for (key in ontologyKeys) {
            ontologyService.delete(key)
        }
        logger.info("Purge: {} ontologies deleted", ontologyKeys.size)

        val kafkaTopicsDeleted = deleteGraphmeshKafkaTopics()

        // Safety net: drops anything the cascade above may have missed.
        val orphan = orphanSweepService.sweep()

        val duration = System.currentTimeMillis() - start
        logger.warn("PURGE ALL DATA completed in {}ms", duration)

        return PurgeResult(
            collectionsDeleted = collections.size,
            documentsDeleted = documentsDeleted,
            ontologiesDeleted = ontologyKeys.size,
            kafkaTopicsDeleted = kafkaTopicsDeleted,
            orphanQuadTablesTruncated = orphan.quadTablesTruncated,
            orphanVectorCollectionsDeleted = orphan.vectorCollectionsDeleted,
            orphanBlobsDeleted = orphan.blobsDeleted,
            durationMs = duration,
        )
    }

    fun purgeOrphansOnly(): OrphanSweepService.Result = orphanSweepService.sweep()

    private fun deleteGraphmeshKafkaTopics(): Int {
        return try {
            AdminClient.create(kafkaAdmin.configurationProperties).use { client ->
                val allTopics = client.listTopics().names().get()
                val graphmeshTopics = allTopics.filter { it.startsWith("graphmesh.") }
                if (graphmeshTopics.isNotEmpty()) {
                    client.deleteTopics(graphmeshTopics).all().get()
                    logger.info("Purge: {} Kafka topics deleted: {}", graphmeshTopics.size, graphmeshTopics)
                    graphmeshTopics.size
                } else 0
            }
        } catch (e: Exception) {
            logger.warn("Purge: Kafka topic deletion failed (non-fatal): {}", e.message)
            0
        }
    }
}
