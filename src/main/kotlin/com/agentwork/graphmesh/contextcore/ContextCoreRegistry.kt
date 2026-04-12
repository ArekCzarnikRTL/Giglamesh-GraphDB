package com.agentwork.graphmesh.contextcore

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.Row
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant

data class CoreRecord(val manifest: CoreManifest, val blobKey: String)

@Component
class ContextCoreRegistry(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) {

    fun register(manifest: CoreManifest, blobKey: String) {
        session.execute(
            """
            INSERT INTO $keyspace.context_cores (
                core_id, version, parent_version, source_collection,
                created_at, created_by, description, tags,
                embedding_model, embedding_dim,
                quad_count, entity_count, chunk_embedding_count, ontology_axiom_count,
                checksum, blob_key
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            manifest.coreId, manifest.version, manifest.parentVersion, manifest.sourceCollection,
            Instant.from(manifest.createdAt), manifest.createdBy, manifest.description,
            manifest.tags,
            manifest.embeddingModel, manifest.embeddingDimension,
            manifest.stats.quadCount, manifest.stats.entityCount,
            manifest.stats.chunkEmbeddingCount, manifest.stats.ontologyAxiomCount,
            manifest.checksum, blobKey
        )
    }

    fun unregister(coreId: String, version: String) {
        session.execute(
            "DELETE FROM $keyspace.context_cores WHERE core_id = ? AND version = ?",
            coreId, version
        )
    }

    fun find(coreId: String, version: String): CoreRecord? {
        val row = session.execute(
            "SELECT * FROM $keyspace.context_cores WHERE core_id = ? AND version = ?",
            coreId, version
        ).one() ?: return null
        return rowToRecord(row)
    }

    fun findByTag(coreId: String, tag: String): CoreRecord? {
        val rows = session.execute(
            "SELECT * FROM $keyspace.context_cores WHERE core_id = ? ALLOW FILTERING",
            coreId
        )
        return rows.firstOrNull { row ->
            row.getSet("tags", String::class.java)?.contains(tag) == true
        }?.let { rowToRecord(it) }
    }

    fun listAll(): List<CoreRecord> {
        val rows = session.execute("SELECT * FROM $keyspace.context_cores")
        return rows.map { rowToRecord(it) }.toList()
    }

    fun addTag(coreId: String, version: String, tag: String) {
        session.execute(
            "UPDATE $keyspace.context_cores SET tags = tags + ? WHERE core_id = ? AND version = ?",
            setOf(tag), coreId, version
        )
    }

    private fun rowToRecord(row: Row): CoreRecord {
        val manifest = CoreManifest(
            coreId = row.getString("core_id")!!,
            version = row.getString("version")!!,
            parentVersion = row.getString("parent_version"),
            sourceCollection = row.getString("source_collection")!!,
            createdAt = row.getInstant("created_at")!!,
            createdBy = row.getString("created_by")!!,
            description = row.getString("description"),
            tags = row.getSet("tags", String::class.java)?.toSet() ?: emptySet(),
            stats = CoreStats(
                quadCount = row.getLong("quad_count"),
                entityCount = row.getLong("entity_count"),
                chunkEmbeddingCount = row.getLong("chunk_embedding_count"),
                ontologyAxiomCount = row.getLong("ontology_axiom_count")
            ),
            embeddingModel = row.getString("embedding_model")!!,
            embeddingDimension = row.getInt("embedding_dim"),
            checksum = row.getString("checksum")!!
        )
        return CoreRecord(manifest, row.getString("blob_key")!!)
    }
}
