package com.agentwork.graphmesh.storage

interface QuadStore {
    fun insert(collection: String, quad: StoredQuad)
    fun insertBatch(collection: String, quads: List<StoredQuad>)
    fun delete(collection: String, quad: StoredQuad)
    fun deleteCollection(collection: String)
    fun query(collection: String, query: QuadQuery): List<StoredQuad>

    fun findByEntities(collection: String, entityIds: List<String>): List<StoredQuad> {
        return entityIds.flatMap { id ->
            query(collection, QuadQuery(subject = id)) +
            query(collection, QuadQuery(objectValue = id))
        }.distinct()
    }
}
