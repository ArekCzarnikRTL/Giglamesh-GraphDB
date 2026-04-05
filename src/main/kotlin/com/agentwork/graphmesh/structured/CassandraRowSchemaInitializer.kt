package com.agentwork.graphmesh.structured

import com.datastax.oss.driver.api.core.CqlSession
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class CassandraRowSchemaInitializer(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun initializeSchema() {
        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.rows (
                collection text,
                schema_name text,
                index_name text,
                index_value frozen<list<text>>,
                data map<text, text>,
                source text,
                PRIMARY KEY ((collection, schema_name, index_name), index_value)
            )
        """.trimIndent())

        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.row_partitions (
                collection text,
                schema_name text,
                index_name text,
                PRIMARY KEY ((collection), schema_name, index_name)
            )
        """.trimIndent())

        logger.info("Cassandra row tables initialized for keyspace '{}'", keyspace)
    }
}
