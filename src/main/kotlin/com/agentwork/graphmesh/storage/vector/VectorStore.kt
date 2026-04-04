package com.agentwork.graphmesh.storage.vector

interface VectorStore {
    fun upsert(collection: String, points: List<VectorPoint>)
    fun search(collection: String, queryVector: FloatArray, limit: Int = 10, filter: VectorFilter? = null, scoreThreshold: Float? = null): List<SearchResult>
    fun delete(collection: String, dimension: Int, ids: List<String>)
    fun deleteCollection(collection: String)
    fun collectionExists(collection: String, dimension: Int): Boolean
}
