package com.agentwork.graphmesh.storage

import com.agentwork.graphmesh.storage.blob.BlobStore
import com.datastax.oss.driver.api.core.CqlSession
import io.qdrant.client.QdrantClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Drops data directly from Cassandra, Qdrant and blob storage without going
 * through any registry. Used as a safety net after purges and to clean up
 * data that previous bugs may have orphaned.
 */
@Service
class OrphanSweepService(
    private val cqlSession: CqlSession,
    private val qdrantClient: QdrantClient,
    private val blobStore: BlobStore,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String,
    @Value("\${graphmesh.storage.blob.default-bucket:graphmesh}") private val defaultBucket: String,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    data class Result(
        val quadTablesTruncated: Int,
        val vectorCollectionsDeleted: Int,
        val blobsDeleted: Int,
    )

    fun sweep(): Result {
        var quadTables = 0
        try {
            cqlSession.execute("TRUNCATE $keyspace.quads_by_entity")
            quadTables++
            cqlSession.execute("TRUNCATE $keyspace.quads_by_collection")
            quadTables++
            logger.info("Orphan sweep: truncated {} quad tables", quadTables)
        } catch (e: Exception) {
            logger.warn("Orphan sweep: Cassandra truncate failed: {}", e.message)
        }

        var vectorColls = 0
        try {
            val all = qdrantClient.listCollectionsAsync().get()
            for (name in all) {
                qdrantClient.deleteCollectionAsync(name).get()
                vectorColls++
            }
            logger.info("Orphan sweep: deleted {} Qdrant collection(s)", vectorColls)
        } catch (e: Exception) {
            logger.warn("Orphan sweep: Qdrant delete failed: {}", e.message)
        }

        var orphanBlobs = 0
        try {
            val blobs = blobStore.list(defaultBucket, "collections/")
            if (blobs.isNotEmpty()) {
                blobStore.deleteBatch(defaultBucket, blobs.map { it.key })
                orphanBlobs = blobs.size
            }
            logger.info("Orphan sweep: deleted {} orphan blob(s)", orphanBlobs)
        } catch (e: Exception) {
            logger.warn("Orphan sweep: blob delete failed: {}", e.message)
        }

        return Result(quadTables, vectorColls, orphanBlobs)
    }
}
