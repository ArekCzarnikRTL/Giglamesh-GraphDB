package com.agentwork.graphmesh.librarian

import com.datastax.oss.driver.api.core.CqlSession
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class DocumentSchemaInitializer(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun initializeSchema() {
        createTables()
        logger.info("Document schema initialized in keyspace '{}'", keyspace)
    }

    private fun createTables() {
        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.documents (
                id          text,
                collection_id text,
                parent_id   text,
                type        text,
                state       text,
                title       text,
                mime_type   text,
                content_uri text,
                metadata    map<text, text>,
                created_at  timestamp,
                updated_at  timestamp,
                PRIMARY KEY (id)
            )
        """.trimIndent())

        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.documents_by_collection (
                collection_id text,
                type        text,
                id          text,
                parent_id   text,
                state       text,
                title       text,
                mime_type   text,
                content_uri text,
                metadata    map<text, text>,
                created_at  timestamp,
                updated_at  timestamp,
                PRIMARY KEY ((collection_id, type), id)
            )
        """.trimIndent())

        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.documents_by_parent (
                parent_id   text,
                id          text,
                collection_id text,
                type        text,
                state       text,
                title       text,
                mime_type   text,
                content_uri text,
                metadata    map<text, text>,
                created_at  timestamp,
                updated_at  timestamp,
                PRIMARY KEY (parent_id, id)
            )
        """.trimIndent())
    }
}
