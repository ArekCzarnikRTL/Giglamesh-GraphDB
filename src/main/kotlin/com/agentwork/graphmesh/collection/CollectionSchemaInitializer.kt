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

    private fun addTenantColumns() {
        try {
            session.execute("ALTER TABLE $keyspace.collections ADD tenant_id text")
        } catch (_: Exception) { /* column already exists */ }
        try {
            session.execute("ALTER TABLE $keyspace.collections ADD owner_id text")
        } catch (_: Exception) { /* column already exists */ }
        try {
            session.execute("ALTER TABLE $keyspace.collections_by_name ADD tenant_id text")
        } catch (_: Exception) { /* column already exists */ }
        try {
            session.execute("ALTER TABLE $keyspace.collections_by_name ADD owner_id text")
        } catch (_: Exception) { /* column already exists */ }
    }
}
