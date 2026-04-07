package com.agentwork.graphmesh.storage

/**
 * In-memory test fake for [QuadStore]. Stores quads in a per-collection list
 * and answers [query] by linear scan, matching the same semantics
 * (`field == query.field` for each non-null query field).
 *
 * Only used by unit tests.
 */
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
}
