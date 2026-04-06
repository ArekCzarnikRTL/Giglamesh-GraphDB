package com.agentwork.graphmesh.librarian

import com.agentwork.graphmesh.collection.CollectionService
import com.agentwork.graphmesh.messaging.DocumentIngestedProducer
import com.agentwork.graphmesh.storage.blob.BlobStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class DocumentFilterCriteria(
    val type: DocumentType? = null,
    val state: DocumentState? = null,
    val search: String? = null
)

data class DocumentPageResult(
    val items: List<Document>,
    val totalCount: Int,
    val hasNextPage: Boolean
)

@Service
class LibrarianService(
    private val documentStore: DocumentStore,
    private val blobStore: BlobStore,
    private val collectionService: CollectionService,
    private val documentIngestedProducer: DocumentIngestedProducer,
    @Value("\${graphmesh.storage.blob.default-bucket:graphmesh}") private val defaultBucket: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun uploadDocument(
        collectionId: String,
        title: String,
        mimeType: String,
        content: ByteArray,
        metadata: Map<String, String> = emptyMap()
    ): Document {
        collectionService.requireExists(collectionId)

        val objectId = UUID.randomUUID().toString()
        val contentUri = "doc/$objectId"

        blobStore.put(defaultBucket, contentUri, content, mimeType)

        val document = Document(
            id = "doc-$objectId",
            collectionId = collectionId,
            title = title,
            mimeType = mimeType,
            contentUri = contentUri,
            metadata = metadata,
            state = DocumentState.UPLOADED
        )

        documentStore.save(document)

        documentIngestedProducer.send(
            documentId = document.id,
            fileName = title,
            mimeType = mimeType,
            sizeBytes = content.size.toLong()
        )

        logger.info("Document uploaded: id={}, title={}, collection={}", document.id, title, collectionId)
        return document
    }

    fun createChildDocument(
        parentId: String,
        type: DocumentType,
        title: String,
        content: ByteArray,
        mimeType: String = "text/plain"
    ): Document {
        require(type != DocumentType.SOURCE) { "Child document cannot be of type SOURCE" }

        val parent = documentStore.findById(parentId)
            ?: throw DocumentNotFoundException(parentId)

        val objectId = UUID.randomUUID().toString()
        val contentUri = "doc/$objectId"

        blobStore.put(defaultBucket, contentUri, content, mimeType)

        val childCount = documentStore.findChildren(parentId)
            .count { it.type == type }
        val suffix = when (type) {
            DocumentType.PAGE -> "p${childCount + 1}"
            DocumentType.CHUNK -> "c${childCount + 1}"
            DocumentType.SOURCE -> error("unreachable")
        }

        val child = Document(
            id = "$parentId/$suffix",
            collectionId = parent.collectionId,
            parentId = parentId,
            type = type,
            title = title,
            mimeType = mimeType,
            contentUri = contentUri,
            state = DocumentState.EXTRACTED
        )

        documentStore.save(child)
        logger.debug("Child document created: id={}, parent={}, type={}", child.id, parentId, type)
        return child
    }

    fun getContent(documentId: String): ByteArray {
        val document = documentStore.findById(documentId)
            ?: throw DocumentNotFoundException(documentId)
        return blobStore.get(defaultBucket, document.contentUri).data
    }

    fun findById(id: String): Document? = documentStore.findById(id)

    fun findByCollection(collectionId: String, type: DocumentType? = null): List<Document> =
        documentStore.findByCollection(collectionId, type)

    fun findByCollectionPaginated(
        collectionId: String,
        filter: DocumentFilterCriteria,
        page: Int,
        pageSize: Int
    ): DocumentPageResult {
        val all = documentStore.findByCollection(collectionId, filter.type)
        val filtered = all.asSequence()
            .let { seq ->
                val state = filter.state
                if (state != null) seq.filter { it.state == state } else seq
            }
            .let { seq ->
                val q = filter.search?.lowercase()?.takeIf { it.isNotBlank() }
                if (q != null) seq.filter { it.title.lowercase().contains(q) } else seq
            }
            .toList()

        val total = filtered.size
        val safePageSize = pageSize.coerceAtLeast(1)
        val safePage = page.coerceAtLeast(0)
        val from = (safePage * safePageSize).coerceAtMost(total)
        val to = (from + safePageSize).coerceAtMost(total)
        val items = filtered.subList(from, to)
        return DocumentPageResult(items = items, totalCount = total, hasNextPage = to < total)
    }

    fun findChunksOf(documentId: String): List<Document> =
        documentStore.findChildren(documentId).filter { it.type == DocumentType.CHUNK }

    fun findChildren(parentId: String): List<Document> =
        documentStore.findChildren(parentId)

    fun updateState(documentId: String, state: DocumentState) {
        documentStore.findById(documentId) ?: throw DocumentNotFoundException(documentId)
        documentStore.updateState(documentId, state)
        logger.info("Document state updated: id={}, state={}", documentId, state)
    }

    fun deleteDocument(documentId: String) {
        val document = documentStore.findById(documentId)
            ?: throw DocumentNotFoundException(documentId)

        // Recursively delete children and their blobs
        val children = documentStore.findChildren(documentId)
        for (child in children) {
            deleteDocument(child.id)
        }

        // Delete blob
        if (document.contentUri.isNotEmpty()) {
            blobStore.delete(defaultBucket, document.contentUri)
        }

        // Delete metadata
        documentStore.delete(documentId)
        logger.info("Document deleted: id={}", documentId)
    }
}
