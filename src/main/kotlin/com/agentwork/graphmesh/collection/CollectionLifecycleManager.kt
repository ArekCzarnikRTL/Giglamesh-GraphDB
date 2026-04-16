package com.agentwork.graphmesh.collection

import com.agentwork.graphmesh.librarian.DocumentStore
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.blob.BlobStore
import com.agentwork.graphmesh.storage.vector.VectorStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

data class PurgeStepFailure(val step: String, val error: String)

data class PurgeResult(
    val collectionId: String,
    val quadsDeleted: Boolean,
    val vectorsDeleted: Boolean,
    val blobsDeleted: Int,
    val documentsDeleted: Int,
    val collectionRowDeleted: Boolean,
    val failures: List<PurgeStepFailure>
) {
    val complete: Boolean get() = failures.isEmpty()
}

@Service
class CollectionLifecycleManager(
    private val collectionStore: CollectionStore,
    private val quadStore: QuadStore,
    private val vectorStore: VectorStore,
    private val blobStore: BlobStore,
    private val documentStore: DocumentStore,
    @Value("\${graphmesh.storage.blob.default-bucket:graphmesh}") private val defaultBucket: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun purge(collectionId: String): PurgeResult {
        val failures = mutableListOf<PurgeStepFailure>()

        val quadsDeleted = runStep("quads", failures) {
            quadStore.deleteCollection(collectionId)
            logger.info("Purge [{}]: quads deleted", collectionId)
        }

        val vectorsDeleted = runStep("vectors", failures) {
            vectorStore.deleteCollection(collectionId)
            logger.info("Purge [{}]: vectors deleted", collectionId)
        }

        val (blobsDeleted, documentsDeleted) = runBlobCleanup(collectionId, failures)

        val collectionRowDeleted = runStep("collectionRow", failures) {
            collectionStore.delete(collectionId)
            logger.info("Purge [{}]: collection row deleted", collectionId)
        }

        val result = PurgeResult(
            collectionId = collectionId,
            quadsDeleted = quadsDeleted,
            vectorsDeleted = vectorsDeleted,
            blobsDeleted = blobsDeleted,
            documentsDeleted = documentsDeleted,
            collectionRowDeleted = collectionRowDeleted,
            failures = failures
        )

        if (result.complete) {
            logger.info("Purge [{}]: completed successfully", collectionId)
        } else {
            logger.warn("Purge [{}]: partial failure — {}", collectionId, result.failures)
        }

        return result
    }

    private fun runBlobCleanup(
        collectionId: String,
        failures: MutableList<PurgeStepFailure>
    ): Pair<Int, Int> {
        var blobsDeleted = 0
        var documentsDeleted = 0

        // 1. Document blobs (doc/{uuid}) — currently missing from CollectionService.delete()
        runStep("documentBlobs", failures) {
            val documents = documentStore.findByCollection(collectionId)
            documents.forEach { doc ->
                if (doc.contentUri.isNotEmpty()) {
                    blobStore.delete(defaultBucket, doc.contentUri)
                }
            }
            blobsDeleted += documents.count { it.contentUri.isNotEmpty() }
            logger.info("Purge [{}]: {} document blobs deleted", collectionId, blobsDeleted)
        }

        // 2. Collection-scoped blobs (collections/{id}/)
        runStep("collectionBlobs", failures) {
            val collectionBlobs = blobStore.list(defaultBucket, "collections/$collectionId/")
            if (collectionBlobs.isNotEmpty()) {
                blobStore.deleteBatch(defaultBucket, collectionBlobs.map { it.key })
            }
            blobsDeleted += collectionBlobs.size
            logger.info("Purge [{}]: {} collection blobs deleted", collectionId, collectionBlobs.size)
        }

        // 3. Document metadata rows
        runStep("documentRows", failures) {
            val documents = documentStore.findByCollection(collectionId)
            documents.forEach { doc ->
                documentStore.deleteWithChildren(doc.id)
            }
            documentsDeleted = documents.size
            logger.info("Purge [{}]: {} document rows deleted", collectionId, documentsDeleted)
        }

        return Pair(blobsDeleted, documentsDeleted)
    }

    private fun runStep(
        step: String,
        failures: MutableList<PurgeStepFailure>,
        action: () -> Unit
    ): Boolean {
        return try {
            action()
            true
        } catch (e: Exception) {
            logger.error("Purge step '{}' failed: {}", step, e.message, e)
            failures.add(PurgeStepFailure(step, e.message ?: e.javaClass.simpleName))
            false
        }
    }
}
