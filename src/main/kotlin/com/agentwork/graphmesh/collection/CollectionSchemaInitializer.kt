package com.agentwork.graphmesh.collection

import com.datastax.oss.driver.api.core.CqlSession
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class CollectionSchemaInitializer(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun initializeSchema() {
        createTables()
        addTenantColumns()
        createOntologyAssignmentTable()
        logger.info("Collection schema initialized in keyspace '{}'", keyspace)
    }

    private fun createTables() {
        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.collections (
                id          text,
                name        text,
                description text,
                tags        set<text>,
                metadata    map<text, text>,
                created_at  timestamp,
                updated_at  timestamp,
                PRIMARY KEY (id)
            )
        """.trimIndent())

        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.collections_by_name (
                name        text,
                id          text,
                description text,
                tags        set<text>,
                metadata    map<text, text>,
                created_at  timestamp,
                updated_at  timestamp,
                PRIMARY KEY (name)
            )
        """.trimIndent())
    }

    private fun createOntologyAssignmentTable() {
        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.collection_ontologies (
                collection_id text,
                ontology_key  text,
                role          text,
                assigned_at   timestamp,
                assigned_by   text,
                PRIMARY KEY (collection_id, ontology_key)
            )
        """.trimIndent())
    }

    private fun addTenantColumns() {
        addColumnIfMissing("collections", "tenant_id", "text")
        addColumnIfMissing("collections", "owner_id", "text")
        addColumnIfMissing("collections_by_name", "tenant_id", "text")
        addColumnIfMissing("collections_by_name", "owner_id", "text")
    }

    private fun addColumnIfMissing(table: String, column: String, type: String) {
        val existing = session.metadata
            .getKeyspace(keyspace).orElse(null)
            ?.getTable(table)?.orElse(null)
            ?.columns
            ?.keys
            ?.map { it.asInternal() }
            ?.toSet()
            ?: emptySet()
        if (column in existing) {
            logger.debug("Column {}.{} already present — skipping ALTER", table, column)
            return
        }
        try {
            session.execute("ALTER TABLE $keyspace.$table ADD $column $type")
            logger.info("Added column {}.{} ({})", table, column, type)
        } catch (e: Exception) {
            // Race: zweite Instanz hat die Spalte gerade angelegt. Harmlos, loggen und weitermachen.
            logger.warn("ALTER TABLE {} ADD {} failed (likely race): {}", table, column, e.message)
        }
    }
}
