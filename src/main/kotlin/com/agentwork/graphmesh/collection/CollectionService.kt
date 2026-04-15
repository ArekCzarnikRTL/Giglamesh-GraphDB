package com.agentwork.graphmesh.collection

import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.blob.BlobStore
import com.agentwork.graphmesh.storage.vector.VectorStore
import com.agentwork.graphmesh.tenant.AccessDeniedException
import com.agentwork.graphmesh.tenant.TenantContext
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

        val tenant = TenantContext.getOrNull()

        val collection = Collection(
            name = name,
            description = description,
            tags = tags,
            metadata = metadata,
            tenantId = tenant?.tenantId,
            ownerId = tenant?.userId
        )

        collectionStore.save(collection)

        val event = CollectionEvent(CollectionEventType.CREATED, collection.id, collection.name)
        eventPublisher.publishEvent(event)
        collectionEventProducer.send(event)

        logger.info("Collection created: id={}, name={}, tenantId={}", collection.id, collection.name, collection.tenantId)
        return collection
    }

    fun delete(id: String) {
        val collection = findByIdWithAccessCheck(id)

        // Cascade delete across all backends. Quads, vectors and blobs are all
        // keyed by collection ID (see RdfImportService, GraphController, etc.),
        // NOT by collection name.
        quadStore.deleteCollection(id)
        vectorStore.deleteCollection(id)
        deleteBlobsForCollection(id)

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
        val existing = findByIdWithAccessCheck(id)

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

    fun findById(id: String): Collection? {
        val collection = collectionStore.findById(id) ?: return null
        checkTenantAccess(collection)
        return collection
    }

    fun findByName(name: String): Collection? {
        val collection = collectionStore.findByName(name) ?: return null
        checkTenantAccess(collection)
        return collection
    }

    fun findAll(tags: Set<String> = emptySet()): List<Collection> {
        var all = collectionStore.findAll()

        val tenant = TenantContext.getOrNull()
        if (tenant != null) {
            all = all.filter { it.tenantId == null || it.tenantId == tenant.tenantId }
        }

        if (tags.isNotEmpty()) {
            all = all.filter { it.tags.containsAll(tags) }
        }
        return all
    }

    fun requireExists(id: String) {
        if (!collectionStore.exists(id)) {
            throw CollectionNotFoundException(id)
        }
    }

    private fun findByIdWithAccessCheck(id: String): Collection {
        val collection = collectionStore.findById(id)
            ?: throw CollectionNotFoundException(id)
        checkTenantAccess(collection)
        return collection
    }

    private fun checkTenantAccess(collection: Collection) {
        val tenant = TenantContext.getOrNull() ?: return
        if (collection.tenantId != null && collection.tenantId != tenant.tenantId) {
            throw AccessDeniedException(
                "Tenant '${tenant.tenantId}' has no access to collection '${collection.id}'."
            )
        }
    }

    private fun deleteBlobsForCollection(collectionId: String) {
        val prefix = "collections/$collectionId/"
        val blobs = blobStore.list(defaultBucket, prefix)
        if (blobs.isNotEmpty()) {
            blobStore.deleteBatch(defaultBucket, blobs.map { it.key })
        }
    }
}
