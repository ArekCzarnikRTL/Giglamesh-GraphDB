package com.agentwork.graphmesh.storage.vector

data class VectorPoint(
    val id: String,
    val vector: FloatArray,
    val payload: Map<String, Any> = emptyMap()
) {
    val dimension: Int get() = vector.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorPoint) return false
        return id == other.id && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int = id.hashCode()
}

data class SearchResult(
    val id: String,
    val score: Float,
    val payload: Map<String, Any> = emptyMap()
)

sealed class VectorFilter {
    data class Equals(val field: String, val value: Any) : VectorFilter()
    data class In(val field: String, val values: List<Any>) : VectorFilter()
    data class And(val filters: List<VectorFilter>) : VectorFilter()
    data class Or(val filters: List<VectorFilter>) : VectorFilter()
    data class Not(val filter: VectorFilter) : VectorFilter()
}

interface VectorStore {
    fun upsert(collection: String, points: List<VectorPoint>)
    fun search(collection: String, queryVector: FloatArray, limit: Int = 10, filter: VectorFilter? = null, scoreThreshold: Float? = null): List<SearchResult>
    fun delete(collection: String, dimension: Int, ids: List<String>)
    fun deleteCollection(collection: String)
    fun collectionExists(collection: String, dimension: Int): Boolean

    /** Scrolls all points from [collection]. Use for export only. */
    fun scroll(collection: String): List<VectorPoint>
}
