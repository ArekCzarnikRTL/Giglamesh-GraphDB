package com.agentwork.graphmesh.storage

import com.datastax.oss.driver.api.core.CqlSession
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class CassandraSchemaInitializer(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun initializeSchema() {
        createKeyspace()
        createTables()
        logger.info("Cassandra schema initialized for keyspace '{}'", keyspace)
    }

    private fun createKeyspace() {
        session.execute("""
            CREATE KEYSPACE IF NOT EXISTS $keyspace
            WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
        """.trimIndent())
    }

    private fun createTables() {
        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.quads_by_entity (
                collection text,
                entity     text,
                role       text,
                p          text,
                otype      text,
                s          text,
                o          text,
                d          text,
                dtype      text,
                lang       text,
                PRIMARY KEY ((collection, entity), role, p, otype, s, o, d, dtype, lang)
            )
        """.trimIndent())

        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.quads_by_collection (
                collection text,
                d          text,
                s          text,
                p          text,
                o          text,
                otype      text,
                dtype      text,
                lang       text,
                PRIMARY KEY (collection, d, s, p, o, otype, dtype, lang)
            )
        """.trimIndent())
    }
}
