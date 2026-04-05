package com.agentwork.graphmesh.structured

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BatchType
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service

@Service
@DependsOn("cassandraRowSchemaInitializer")
class CassandraRowStore(
    private val session: CqlSession,
    private val schemaStore: SchemaStore,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private lateinit var insertRow: PreparedStatement
    private lateinit var insertPartition: PreparedStatement
    private lateinit var selectRows: PreparedStatement
    private lateinit var selectPartitions: PreparedStatement
    private lateinit var selectPartitionsBySchema: PreparedStatement
    private lateinit var deletePartition: PreparedStatement

    @PostConstruct
    fun prepareStatements() {
        insertRow = session.prepare("""
            INSERT INTO $keyspace.rows (collection, schema_name, index_name, index_value, data, source)
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent())

        insertPartition = session.prepare("""
            INSERT INTO $keyspace.row_partitions (collection, schema_name, index_name)
            VALUES (?, ?, ?)
        """.trimIndent())

        selectRows = session.prepare("""
            SELECT data, source FROM $keyspace.rows
            WHERE collection = ? AND schema_name = ? AND index_name = ? AND index_value = ?
            LIMIT ?
        """.trimIndent())

        selectPartitions = session.prepare("""
            SELECT schema_name, index_name FROM $keyspace.row_partitions
            WHERE collection = ?
        """.trimIndent())

        selectPartitionsBySchema = session.prepare("""
            SELECT index_name FROM $keyspace.row_partitions
            WHERE collection = ? AND schema_name = ?
        """.trimIndent())

        deletePartition = session.prepare("""
            DELETE FROM $keyspace.row_partitions
            WHERE collection = ? AND schema_name = ? AND index_name = ?
        """.trimIndent())
    }

    fun insert(row: DataRow) {
        val schema = schemaStore.load(row.schemaName)
            ?: throw IllegalArgumentException("Schema '${row.schemaName}' not found")

        val batch = BatchStatement.builder(BatchType.LOGGED)

        for (indexName in schema.allIndexNames) {
            val indexValue = extractIndexValue(indexName, row.values)
            batch.addStatement(insertRow.bind(
                row.collection, row.schemaName, indexName, indexValue, row.values, row.source
            ))
            batch.addStatement(insertPartition.bind(row.collection, row.schemaName, indexName))
        }

        session.execute(batch.build())
        logger.debug("Inserted row into {}/{} with {} indexes", row.collection, row.schemaName, schema.allIndexNames.size)
    }

    fun insertBatch(rows: List<DataRow>) {
        if (rows.isEmpty()) return
        val batch = BatchStatement.builder(BatchType.LOGGED)

        for (row in rows) {
            val schema = schemaStore.load(row.schemaName)
                ?: throw IllegalArgumentException("Schema '${row.schemaName}' not found")

            for (indexName in schema.allIndexNames) {
                val indexValue = extractIndexValue(indexName, row.values)
                batch.addStatement(insertRow.bind(
                    row.collection, row.schemaName, indexName, indexValue, row.values, row.source
                ))
                batch.addStatement(insertPartition.bind(row.collection, row.schemaName, indexName))
            }
        }

        session.execute(batch.build())
        logger.debug("Batch inserted {} rows", rows.size)
    }

    fun query(query: StructuredQuery): QueryResult {
        val resultSet = session.execute(selectRows.bind(
            query.collection, query.schemaName, query.indexName, query.indexValue, query.limit + 1
        ))

        val rows = mutableListOf<DataRow>()
        for (cassandraRow in resultSet) {
            if (rows.size >= query.limit) break
            rows.add(DataRow(
                collection = query.collection,
                schemaName = query.schemaName,
                values = cassandraRow.getMap("data", String::class.java, String::class.java) ?: emptyMap(),
                source = cassandraRow.getString("source")
            ))
        }

        val hasMore = resultSet.availableWithoutFetching > 0 || rows.size == query.limit
        return QueryResult(rows = rows, totalCount = rows.size, hasMore = hasMore)
    }

    fun deleteByCollection(collection: String) {
        val partitions = session.execute(selectPartitions.bind(collection))
        val batch = BatchStatement.builder(BatchType.LOGGED)
        var count = 0

        for (row in partitions) {
            val schemaName = row.getString("schema_name")!!
            val indexName = row.getString("index_name")!!

            session.execute("DELETE FROM $keyspace.rows WHERE collection = '${collection}' AND schema_name = '${schemaName}' AND index_name = '${indexName}'")
            batch.addStatement(deletePartition.bind(collection, schemaName, indexName))
            count++
        }

        if (count > 0) {
            session.execute(batch.build())
        }
        logger.info("Deleted all rows for collection {}", collection)
    }

    fun deleteBySchema(collection: String, schemaName: String) {
        val partitions = session.execute(selectPartitionsBySchema.bind(collection, schemaName))
        val batch = BatchStatement.builder(BatchType.LOGGED)
        var count = 0

        for (row in partitions) {
            val indexName = row.getString("index_name")!!
            session.execute("DELETE FROM $keyspace.rows WHERE collection = '${collection}' AND schema_name = '${schemaName}' AND index_name = '${indexName}'")
            batch.addStatement(deletePartition.bind(collection, schemaName, indexName))
            count++
        }

        if (count > 0) {
            session.execute(batch.build())
        }
        logger.info("Deleted rows for collection {}, schema {}", collection, schemaName)
    }
}

internal fun extractIndexValue(indexName: String, values: Map<String, String>): List<String> {
    val fields = indexName.split(",")
    return fields.map { field -> values[field] ?: "" }
}
