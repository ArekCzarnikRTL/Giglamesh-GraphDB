package com.agentwork.graphmesh.config

import com.datastax.oss.driver.api.core.CqlSession
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ConfigSchemaInitializer(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun initializeSchema() {
        createTables()
        logger.info("Config schema initialized in keyspace '{}'", keyspace)
    }

    private fun createTables() {
        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.config_items (
                id          text,
                type        text,
                key         text,
                value       text,
                version     int,
                created_at  timestamp,
                updated_at  timestamp,
                created_by  text,
                description text,
                PRIMARY KEY (id)
            )
        """.trimIndent())

        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.config_by_type (
                type        text,
                key         text,
                id          text,
                value       text,
                version     int,
                updated_at  timestamp,
                PRIMARY KEY (type, key)
            )
        """.trimIndent())

        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.config_history (
                id          text,
                version     int,
                value       text,
                updated_at  timestamp,
                updated_by  text,
                PRIMARY KEY (id, version)
            ) WITH CLUSTERING ORDER BY (version DESC)
        """.trimIndent())
    }
}
