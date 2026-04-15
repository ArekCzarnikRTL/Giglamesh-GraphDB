package com.agentwork.graphmesh.storage

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service

@Service
@DependsOn("cassandraSchemaInitializer")
class CassandraQuadStore(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String,
    private val asyncCqlWriter: AsyncCqlWriter,
) : QuadStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    private lateinit var insertEntity: PreparedStatement
    private lateinit var insertCollection: PreparedStatement
    private lateinit var deleteEntity: PreparedStatement
    private lateinit var deleteCollectionRow: PreparedStatement
    private lateinit var selectCollection: PreparedStatement
    private lateinit var deleteEntityPartition: PreparedStatement

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
        asyncCqlWriter.executeAll(buildInsertStatements(collection, quad))
    }

    override fun insertBatch(collection: String, quads: List<StoredQuad>) {
        val statements = quads.flatMap { buildInsertStatements(collection, it) }
        asyncCqlWriter.executeAll(statements)
    }

    override fun delete(collection: String, quad: StoredQuad) {
        asyncCqlWriter.executeAll(buildDeleteStatements(collection, quad))
    }

    override fun deleteCollection(collection: String) {
        val rows = session.execute(selectCollection.bind(collection))
        val entities = mutableSetOf<String>()
        for (row in rows) {
            entities.add(row.getString("s")!!)
            entities.add(row.getString("p")!!)
            entities.add(row.getString("o")!!)
            entities.add(row.getString("d")!!)
        }

        val partitionDeletes = entities.map { entity ->
            deleteEntityPartition.bind(collection, entity)
        }
        asyncCqlWriter.executeAll(partitionDeletes)

        session.execute("DELETE FROM $keyspace.quads_by_collection WHERE collection = ?", collection)
    }

    private fun buildInsertStatements(collection: String, quad: StoredQuad): List<BoundStatement> {
        val s = quad.subject
        val p = quad.predicate
        val o = quad.objectValue
        val d = quad.dataset
        val otype = quad.objectType.code
        val dtype = quad.datatype
        val lang = quad.language
        return listOf(
            insertEntity.bind(collection, s, "S", p, otype, s, o, d, dtype, lang),
            insertEntity.bind(collection, p, "P", p, otype, s, o, d, dtype, lang),
            insertEntity.bind(collection, o, "O", p, otype, s, o, d, dtype, lang),
            insertEntity.bind(collection, d, "G", p, otype, s, o, d, dtype, lang),
            insertCollection.bind(collection, d, s, p, o, otype, dtype, lang),
        )
    }

    private fun buildDeleteStatements(collection: String, quad: StoredQuad): List<BoundStatement> {
        val s = quad.subject
        val p = quad.predicate
        val o = quad.objectValue
        val d = quad.dataset
        val otype = quad.objectType.code
        val dtype = quad.datatype
        val lang = quad.language
        return listOf(
            deleteEntity.bind(collection, s, "S", p, otype, s, o, d, dtype, lang),
            deleteEntity.bind(collection, p, "P", p, otype, s, o, d, dtype, lang),
            deleteEntity.bind(collection, o, "O", p, otype, s, o, d, dtype, lang),
            deleteEntity.bind(collection, d, "G", p, otype, s, o, d, dtype, lang),
            deleteCollectionRow.bind(collection, d, s, p, o, otype, dtype, lang),
        )
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
            if (matchesFilter(quad, s, p, o, d)) quad else null
        }
        return if (limit != null) result.take(limit) else result
    }

    override fun findSubjects(collection: String, substringMatch: String, limit: Int): List<String> {
        val needle = substringMatch.lowercase()
        return query(collection, QuadQuery(), limit = null)
            .asSequence()
            .map { it.subject }
            .filter { it.lowercase().contains(needle) }
            .distinct()
            .take(limit)
            .toList()
    }

    override fun scrollAll(collection: String): List<StoredQuad> = query(collection, QuadQuery())

    override fun isEmpty(collection: String): Boolean =
        query(collection, QuadQuery(), limit = 1).isEmpty()

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
            listOf(true, true, true, true)     -> 1
            listOf(true, true, true, false)    -> 2
            listOf(true, true, false, true)    -> 3
            listOf(true, true, false, false)   -> 4
            listOf(true, false, true, true)    -> 5
            listOf(true, false, true, false)   -> 6
            listOf(true, false, false, true)   -> 7
            listOf(true, false, false, false)  -> 8
            listOf(false, true, true, true)    -> 9
            listOf(false, true, true, false)   -> 10
            listOf(false, true, false, true)   -> 11
            listOf(false, true, false, false)  -> 12
            listOf(false, false, true, true)   -> 13
            listOf(false, false, true, false)  -> 14
            listOf(false, false, false, true)  -> 15
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
            1  to session.prepare("$base AND p = ?"),
            2  to session.prepare("$base AND p = ?"),
            3  to session.prepare("$base AND p = ?"),
            4  to session.prepare("$base AND p = ?"),
            5  to session.prepare("$base"),
            6  to session.prepare("$base"),
            7  to session.prepare("$base"),
            8  to session.prepare("$base"),
            9  to session.prepare("$base AND p = ?"),
            10 to session.prepare("$base AND p = ?"),
            11 to session.prepare("$base"),
            12 to session.prepare("$base"),
            13 to session.prepare("$base"),
            14 to session.prepare("$base"),
            15 to session.prepare("$base"),
            16 to session.prepare("SELECT s, p, o, d, otype, dtype, lang FROM $keyspace.quads_by_collection WHERE collection = ?")
        )
    }
}
