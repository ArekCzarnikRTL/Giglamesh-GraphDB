package com.agentwork.graphmesh.storage

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BatchStatementBuilder
import com.datastax.oss.driver.api.core.cql.BatchType
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class QuadStoreService(
    private val session: CqlSession,
    @Value("\${spring.cassandra.keyspace-name:graphmesh}") private val keyspace: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private lateinit var insertEntity: PreparedStatement
    private lateinit var insertCollection: PreparedStatement
    private lateinit var deleteEntity: PreparedStatement
    private lateinit var deleteCollectionRow: PreparedStatement
    private lateinit var selectCollection: PreparedStatement
    private lateinit var deleteEntityPartition: PreparedStatement

    // Query prepared statements: indexed by pattern number (1-16)
    private lateinit var queryStatements: Map<Int, PreparedStatement>

    @jakarta.annotation.PostConstruct
    fun prepareStatements() {
        insertEntity = session.prepare("""
            INSERT INTO $keyspace.quads_by_entity
                (collection, entity, role, p, otype, s, o, d, dtype, lang)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())

        insertCollection = session.prepare("""
            INSERT INTO $keyspace.quads_by_collection
                (collection, d, s, p, o, otype, dtype, lang)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())

        deleteEntity = session.prepare("""
            DELETE FROM $keyspace.quads_by_entity
            WHERE collection = ? AND entity = ? AND role = ?
                AND p = ? AND otype = ? AND s = ? AND o = ? AND d = ? AND dtype = ? AND lang = ?
        """.trimIndent())

        deleteCollectionRow = session.prepare("""
            DELETE FROM $keyspace.quads_by_collection
            WHERE collection = ? AND d = ? AND s = ? AND p = ? AND o = ? AND otype = ? AND dtype = ? AND lang = ?
        """.trimIndent())

        selectCollection = session.prepare("""
            SELECT d, s, p, o, otype, dtype, lang FROM $keyspace.quads_by_collection
            WHERE collection = ?
        """.trimIndent())

        deleteEntityPartition = session.prepare("""
            DELETE FROM $keyspace.quads_by_entity
            WHERE collection = ? AND entity = ?
        """.trimIndent())

        prepareQueryStatements()
    }

    fun insert(collection: String, quad: StoredQuad) {
        val batch = BatchStatement.builder(BatchType.LOGGED)
        addInsertToBatch(batch, collection, quad)
        session.execute(batch.build())
    }

    fun insertBatch(collection: String, quads: List<StoredQuad>) {
        val batch = BatchStatement.builder(BatchType.LOGGED)
        quads.forEach { addInsertToBatch(batch, collection, it) }
        session.execute(batch.build())
    }

    fun delete(collection: String, quad: StoredQuad) {
        val batch = BatchStatement.builder(BatchType.LOGGED)
        addDeleteToBatch(batch, collection, quad)
        session.execute(batch.build())
    }

    fun deleteCollection(collection: String) {
        // 1. Read all quads from collection manifest
        val rows = session.execute(selectCollection.bind(collection))
        val entities = mutableSetOf<String>()

        for (row in rows) {
            entities.add(row.getString("s")!!)
            entities.add(row.getString("p")!!)
            entities.add(row.getString("o")!!)
            entities.add(row.getString("d")!!)
        }

        // 2. Delete all entity partitions
        for (entity in entities) {
            session.execute(deleteEntityPartition.bind(collection, entity))
        }

        // 3. Delete collection partition from quads_by_collection
        session.execute("DELETE FROM $keyspace.quads_by_collection WHERE collection = ?", collection)
    }

    private fun addInsertToBatch(
        batch: BatchStatementBuilder,
        collection: String,
        quad: StoredQuad
    ) {
        val s = quad.subject
        val p = quad.predicate
        val o = quad.objectValue
        val d = quad.dataset
        val otype = quad.objectType.code
        val dtype = quad.datatype
        val lang = quad.language

        // 4 rows in quads_by_entity (one per entity role)
        batch.addStatement(insertEntity.bind(collection, s, "S", p, otype, s, o, d, dtype, lang))
        batch.addStatement(insertEntity.bind(collection, p, "P", p, otype, s, o, d, dtype, lang))
        batch.addStatement(insertEntity.bind(collection, o, "O", p, otype, s, o, d, dtype, lang))
        batch.addStatement(insertEntity.bind(collection, d, "G", p, otype, s, o, d, dtype, lang))

        // 1 row in quads_by_collection
        batch.addStatement(insertCollection.bind(collection, d, s, p, o, otype, dtype, lang))
    }

    private fun addDeleteToBatch(
        batch: BatchStatementBuilder,
        collection: String,
        quad: StoredQuad
    ) {
        val s = quad.subject
        val p = quad.predicate
        val o = quad.objectValue
        val d = quad.dataset
        val otype = quad.objectType.code
        val dtype = quad.datatype
        val lang = quad.language

        batch.addStatement(deleteEntity.bind(collection, s, "S", p, otype, s, o, d, dtype, lang))
        batch.addStatement(deleteEntity.bind(collection, p, "P", p, otype, s, o, d, dtype, lang))
        batch.addStatement(deleteEntity.bind(collection, o, "O", p, otype, s, o, d, dtype, lang))
        batch.addStatement(deleteEntity.bind(collection, d, "G", p, otype, s, o, d, dtype, lang))

        batch.addStatement(deleteCollectionRow.bind(collection, d, s, p, o, otype, dtype, lang))
    }

    // Query methods — placeholder, implemented in Task 5
    fun query(collection: String, query: QuadQuery): List<StoredQuad> {
        return emptyList()
    }

    private fun prepareQueryStatements() {
        queryStatements = emptyMap()
    }
}
