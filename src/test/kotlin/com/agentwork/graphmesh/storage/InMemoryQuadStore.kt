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

    override fun query(collection: String, query: QuadQuery): List<StoredQuad> {
        val rows = byCollection[collection] ?: return emptyList()
        return rows.filter { q ->
            (query.subject == null || q.subject == query.subject) &&
            (query.predicate == null || q.predicate == query.predicate) &&
            (query.objectValue == null || q.objectValue == query.objectValue) &&
            (query.dataset == null || q.dataset == query.dataset)
        }
    }
}
