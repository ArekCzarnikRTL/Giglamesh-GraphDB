package com.agentwork.graphmesh.collection

import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.blob.BlobStore
import com.agentwork.graphmesh.storage.vector.VectorStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class CollectionService(
    private val collectionStore: CollectionStore,
    private val quadStore: QuadStore,
    private val vectorStore: VectorStore,
    private val blobStore: BlobStore,
    private val eventPublisher: ApplicationEventPublisher,
    private val collectionEventProducer: CollectionEventProducer,
    @Value("\${graphmesh.storage.blob.default-bucket:graphmesh}") private val defaultBucket: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun create(
        name: String,
        description: String = "",
        tags: Set<String> = emptySet(),
        metadata: Map<String, String> = emptyMap()
    ): Collection {
        require(collectionStore.findByName(name) == null) {
            "Collection with name '$name' already exists"
        }

        val collection = Collection(
            name = name,
            description = description,
            tags = tags,
            metadata = metadata
        )

        collectionStore.save(collection)

        val event = CollectionEvent(CollectionEventType.CREATED, collection.id, collection.name)
        eventPublisher.publishEvent(event)
        collectionEventProducer.send(event)

        logger.info("Collection created: id={}, name={}", collection.id, collection.name)
        return collection
    }

    fun delete(id: String) {
        val collection = collectionStore.findById(id)
            ?: throw CollectionNotFoundException(id)

        // Cascade delete across all backends
        quadStore.deleteCollection(collection.name)
        vectorStore.deleteCollection(collection.name)
        deleteBlobsForCollection(collection.name)

        collectionStore.delete(id)

        val event = CollectionEvent(CollectionEventType.DELETED, id, collection.name)
        eventPublisher.publishEvent(event)
        collectionEventProducer.send(event)

        logger.info("Collection deleted (cascade): id={}, name={}", id, collection.name)
    }

    fun update(
        id: String,
        name: String? = null,
        description: String? = null,
        tags: Set<String>? = null,
        metadata: Map<String, String>? = null
    ): Collection {
        val existing = collectionStore.findById(id)
            ?: throw CollectionNotFoundException(id)

        if (name != null && name != existing.name) {
            require(collectionStore.findByName(name) == null) {
                "Collection with name '$name' already exists"
            }
        }

        val updated = existing.copy(
            name = name ?: existing.name,
            description = description ?: existing.description,
            tags = tags ?: existing.tags,
            metadata = metadata ?: existing.metadata,
            updatedAt = Instant.now()
        )

        collectionStore.save(updated)

        val event = CollectionEvent(CollectionEventType.UPDATED, id, updated.name)
        eventPublisher.publishEvent(event)
        collectionEventProducer.send(event)

        logger.info("Collection updated: id={}, name={}", id, updated.name)
        return updated
    }

    fun findById(id: String): Collection? = collectionStore.findById(id)

    fun findByName(name: String): Collection? = collectionStore.findByName(name)

    fun findAll(tags: Set<String> = emptySet()): List<Collection> {
        val all = collectionStore.findAll()
        if (tags.isEmpty()) return all
        return all.filter { it.tags.containsAll(tags) }
    }

    fun requireExists(id: String) {
        if (!collectionStore.exists(id)) {
            throw CollectionNotFoundException(id)
        }
    }

    private fun deleteBlobsForCollection(collectionName: String) {
        val prefix = "collections/$collectionName/"
        val blobs = blobStore.list(defaultBucket, prefix)
        if (blobs.isNotEmpty()) {
            blobStore.deleteBatch(defaultBucket, blobs.map { it.key })
        }
    }
}
