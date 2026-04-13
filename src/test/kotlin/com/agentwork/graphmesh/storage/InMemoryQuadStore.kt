package com.agentwork.graphmesh.storage

/**
 * In-memory test fake for [QuadStore]. Stores quads in a per-collection list
 * and answers [query] by linear scan, matching the same semantics
 * (`field == query.field` for each non-null query field).
 *
 * Only used by unit tests.
 */
@Suppress("TooManyFunctions")
class InMemoryQuadStore : QuadStore {

    private val byCollection: MutableMap<String, MutableList<StoredQuad>> = mutableMapOf()

    override fun insert(collection: String, quad: StoredQuad) {
        byCollection.getOrPut(collection) { mutableListOf() }.add(quad)
    }

    override fun insertBatch(collection: String, quads: List<StoredQuad>) {
        quads.forEach { insert(collection, it) }
    }

    override fun delete(collection: String, quad: StoredQuad) {
        byCollection[collection]?.remove(quad)
    }

    override fun deleteCollection(collection: String) {
        byCollection.remove(collection)
    }

    override fun query(collection: String, query: QuadQuery, limit: Int?): List<StoredQuad> {
        val rows = byCollection[collection] ?: return emptyList()
        val filtered = rows.filter { q ->
            (query.subject == null || q.subject == query.subject) &&
            (query.predicate == null || q.predicate == query.predicate) &&
            (query.objectValue == null || q.objectValue == query.objectValue) &&
            (query.dataset == null || q.dataset == query.dataset)
        }
        return if (limit != null) filtered.take(limit) else filtered
    }

    override fun findSubjects(collection: String, substringMatch: String, limit: Int): List<String> {
        val rows = byCollection[collection] ?: return emptyList()
        val needle = substringMatch.lowercase()
        return rows.asSequence()
            .map { it.subject }
            .filter { it.lowercase().contains(needle) }
            .distinct()
            .take(limit)
            .toList()
    }

    override fun aggregateMetadata(collection: String): GraphMetadataView {
        val rows = byCollection[collection] ?: return GraphMetadataView(emptyList(), emptyList(), emptyList())
        val datasets = rows.map { it.dataset }.distinct().sorted().take(200)
        val predicates = rows.map { it.predicate }.distinct().sorted().take(200)
        val entityTypes = rows.asSequence()
            .filter { it.predicate == RDF_TYPE_URI }
            .map { it.objectValue }
            .distinct()
            .sorted()
            .take(200)
            .toList()
        return GraphMetadataView(datasets, predicates, entityTypes)
    }

    override fun scrollAll(collection: String): List<StoredQuad> {
        return byCollection[collection]?.toList() ?: emptyList()
    }

    override fun isEmpty(collection: String): Boolean {
        return byCollection[collection]?.isEmpty() ?: true
    }

    override fun deleteByDataset(collection: String, dataset: String): Long {
        val quads = byCollection[collection] ?: return 0L
        val before = quads.size
        quads.removeIf { it.dataset == dataset }
        return (before - quads.size).toLong()
    }

    override fun stats(collection: String): QuadStoreStats {
        val quads = byCollection[collection] ?: emptyList()
        return QuadStoreStats(
            tripleCount = quads.size.toLong(),
            entityCount = quads.map { it.subject }.distinct().size.toLong(),
            predicateCount = quads.map { it.predicate }.distinct().size.toLong(),
            datasets = quads.map { it.dataset }.distinct().sorted()
        )
    }
}
