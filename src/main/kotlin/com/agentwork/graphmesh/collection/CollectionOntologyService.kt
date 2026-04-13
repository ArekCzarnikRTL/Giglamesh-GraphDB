package com.agentwork.graphmesh.collection

import com.datastax.oss.driver.api.core.CqlSession
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class CollectionOntologyService(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun assign(collectionId: String, ontologyKey: String, role: String, assignedBy: String): CollectionOntologyRecord {
        val now = Instant.now()
        session.execute(
            "INSERT INTO $keyspace.collection_ontologies (collection_id, ontology_key, role, assigned_at, assigned_by) " +
            "VALUES ('$collectionId', '$ontologyKey', '$role', '$now', '$assignedBy')"
        )
        logger.info("Assigned ontology '{}' to collection '{}' with role '{}'", ontologyKey, collectionId, role)
        return CollectionOntologyRecord(collectionId, ontologyKey, role, now, assignedBy)
    }

    fun unassign(collectionId: String, ontologyKey: String) {
        session.execute(
            "DELETE FROM $keyspace.collection_ontologies WHERE collection_id = '$collectionId' AND ontology_key = '$ontologyKey'"
        )
        logger.info("Unassigned ontology '{}' from collection '{}'", ontologyKey, collectionId)
    }

    fun listForCollection(collectionId: String): List<CollectionOntologyRecord> {
        val rs = session.execute(
            "SELECT ontology_key, role, assigned_at, assigned_by FROM $keyspace.collection_ontologies WHERE collection_id = '$collectionId'"
        )
        val result = mutableListOf<CollectionOntologyRecord>()
        for (row in rs) {
            result.add(CollectionOntologyRecord(
                collectionId = collectionId,
                ontologyKey = row.getString("ontology_key")!!,
                role = row.getString("role") ?: "",
                assignedAt = row.getInstant("assigned_at") ?: Instant.now(),
                assignedBy = row.getString("assigned_by") ?: ""
            ))
        }
        return result
    }
}

data class CollectionOntologyRecord(
    val collectionId: String,
    val ontologyKey: String,
    val role: String,
    val assignedAt: Instant,
    val assignedBy: String
)
