package com.agentwork.graphmesh.storage

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BatchStatementBuilder
import com.datastax.oss.driver.api.core.cql.BatchType
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service

@Service
@DependsOn("cassandraSchemaInitializer")
class CassandraQuadStore(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) : QuadStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Each quad produces ~5 CQL rows; Cassandra default batch limit is 50KB. */
        private const val BATCH_CHUNK_SIZE = 20
    }

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

    override fun insert(collection: String, quad: StoredQuad) {
        val batch = BatchStatement.builder(BatchType.LOGGED)
        addInsertToBatch(batch, collection, quad)
        session.execute(batch.build())
    }

    override fun insertBatch(collection: String, quads: List<StoredQuad>) {
        quads.chunked(BATCH_CHUNK_SIZE).forEach { chunk ->
            val batch = BatchStatement.builder(BatchType.LOGGED)
            chunk.forEach { addInsertToBatch(batch, collection, it) }
            session.execute(batch.build())
        }
    }

    override fun delete(collection: String, quad: StoredQuad) {
        val batch = BatchStatement.builder(BatchType.LOGGED)
        addDeleteToBatch(batch, collection, quad)
        session.execute(batch.build())
    }

    override fun deleteCollection(collection: String) {
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

    override fun query(collection: String, query: QuadQuery, limit: Int?): List<StoredQuad> {
        val s = query.subject
        val p = query.predicate
        val o = query.objectValue
        val d = query.dataset
        val pattern = resolvePattern(s, p, o, d)
        val stmt = queryStatements.getValue(pattern)

        val bound = when (pattern) {
            1  -> stmt.bind(collection, s, "S", p)
            2  -> stmt.bind(collection, s, "S", p)
            3  -> stmt.bind(collection, s, "S", p)
            4  -> stmt.bind(collection, s, "S", p)
            5  -> stmt.bind(collection, s, "S")
            6  -> stmt.bind(collection, s, "S")
            7  -> stmt.bind(collection, s, "S")
            8  -> stmt.bind(collection, s, "S")
            9  -> stmt.bind(collection, o, "O", p)
            10 -> stmt.bind(collection, o, "O", p)
            11 -> stmt.bind(collection, p, "P")
            12 -> stmt.bind(collection, p, "P")
            13 -> stmt.bind(collection, o, "O")
            14 -> stmt.bind(collection, o, "O")
            15 -> stmt.bind(collection, d, "G")
            16 -> stmt.bind(collection)
            else -> error("Unknown pattern: $pattern")
        }

        val rows = session.execute(bound)
        val result = rows.mapNotNull { row ->
            val quad = StoredQuad(
                subject = row.getString("s")!!,
                predicate = row.getString("p")!!,
                objectValue = row.getString("o")!!,
                dataset = row.getString("d")!!,
                objectType = ObjectType.fromCode(row.getString("otype")!!),
                datatype = row.getString("dtype") ?: "",
                language = row.getString("lang") ?: ""
            )
            // In-memory filter for fields not covered by CQL WHERE clause
            if (matchesFilter(quad, s, p, o, d)) quad else null
        }
        return if (limit != null) result.take(limit) else result
    }

    override fun findSubjects(collection: String, substringMatch: String, limit: Int): List<String> {
        // MVP: full scan via query(QuadQuery()), then in-memory filter+distinct.
        // TODO: replace with CQL prefix index or materialized view when needed.
        val needle = substringMatch.lowercase()
        return query(collection, QuadQuery(), limit = null)
            .asSequence()
            .map { it.subject }
            .filter { it.lowercase().contains(needle) }
            .distinct()
            .take(limit)
            .toList()
    }

    override fun scrollAll(collection: String): List<StoredQuad> {
        return query(collection, QuadQuery())
    }

    override fun isEmpty(collection: String): Boolean {
        return query(collection, QuadQuery(), limit = 1).isEmpty()
    }

    override fun aggregateMetadata(collection: String): GraphMetadataView {
        val all = query(collection, QuadQuery(), limit = null)
        val datasets = all.map { it.dataset }.distinct().sorted().take(200)
        val predicates = all.map { it.predicate }.distinct().sorted().take(200)
        val entityTypes = all.asSequence()
            .filter { it.predicate == RDF_TYPE_URI }
            .map { it.objectValue }
            .distinct()
            .sorted()
            .take(200)
            .toList()
        return GraphMetadataView(datasets, predicates, entityTypes)
    }

    override fun deleteByDataset(collection: String, dataset: String): Long {
        val quads = query(collection, QuadQuery(dataset = dataset))
        quads.forEach { delete(collection, it) }
        return quads.size.toLong()
    }

    override fun stats(collection: String): QuadStoreStats {
        val meta = aggregateMetadata(collection)
        val allQuads = scrollAll(collection)
        return QuadStoreStats(
            tripleCount = allQuads.size.toLong(),
            entityCount = allQuads.map { it.subject }.distinct().size.toLong(),
            predicateCount = meta.predicates.size.toLong(),
            datasets = meta.datasets
        )
    }

    private fun resolvePattern(s: String?, p: String?, o: String?, d: String?): Int {
        val known = listOf(s != null, p != null, o != null, d != null)
        return when (known) {
            listOf(true, true, true, true)    -> 1
            listOf(true, true, true, false)   -> 2
            listOf(true, true, false, true)   -> 3
            listOf(true, true, false, false)  -> 4
            listOf(true, false, true, true)   -> 5
            listOf(true, false, true, false)  -> 6
            listOf(true, false, false, true)  -> 7
            listOf(true, false, false, false) -> 8
            listOf(false, true, true, true)   -> 9
            listOf(false, true, true, false)  -> 10
            listOf(false, true, false, true)  -> 11
            listOf(false, true, false, false) -> 12
            listOf(false, false, true, true)  -> 13
            listOf(false, false, true, false) -> 14
            listOf(false, false, false, true) -> 15
            listOf(false, false, false, false) -> 16
            else -> error("Invalid pattern")
        }
    }

    private fun matchesFilter(quad: StoredQuad, s: String?, p: String?, o: String?, d: String?): Boolean {
        if (s != null && quad.subject != s) return false
        if (p != null && quad.predicate != p) return false
        if (o != null && quad.objectValue != o) return false
        if (d != null && quad.dataset != d) return false
        return true
    }

    private fun prepareQueryStatements() {
        val base = "SELECT s, p, o, d, otype, dtype, lang FROM $keyspace.quads_by_entity WHERE collection = ? AND entity = ? AND role = ?"

        queryStatements = mapOf(
            // Patterns 1-4: S known, P known -> entity=S, role='S', AND p = ?
            1  to session.prepare("$base AND p = ?"),  // S,P,O,D — filter o,d in memory
            2  to session.prepare("$base AND p = ?"),  // S,P,O,? — filter o in memory
            3  to session.prepare("$base AND p = ?"),  // S,P,?,D — filter d in memory
            4  to session.prepare("$base AND p = ?"),  // S,P,?,?
            // Patterns 5-8: S known, P unknown -> entity=S, role='S'
            5  to session.prepare("$base"),             // S,?,O,D — filter o,d in memory
            6  to session.prepare("$base"),             // S,?,O,? — filter o in memory
            7  to session.prepare("$base"),             // S,?,?,D — filter d in memory
            8  to session.prepare("$base"),             // S,?,?,?
            // Patterns 9-10: O known, P known -> entity=O, role='O', AND p = ?
            9  to session.prepare("$base AND p = ?"),   // ?,P,O,D — filter d in memory
            10 to session.prepare("$base AND p = ?"),   // ?,P,O,?
            // Patterns 11-12: P known (S,O unknown) -> entity=P, role='P'
            11 to session.prepare("$base"),             // ?,P,?,D — filter d in memory
            12 to session.prepare("$base"),             // ?,P,?,?
            // Patterns 13-14: O known (S,P unknown) -> entity=O, role='O'
            13 to session.prepare("$base"),             // ?,?,O,D — filter d in memory
            14 to session.prepare("$base"),             // ?,?,O,?
            // Pattern 15: D known -> entity=D, role='G'
            15 to session.prepare("$base"),             // ?,?,?,D
            // Pattern 16: all wildcard -> quads_by_collection
            16 to session.prepare("SELECT s, p, o, d, otype, dtype, lang FROM $keyspace.quads_by_collection WHERE collection = ?")
        )
    }
}
