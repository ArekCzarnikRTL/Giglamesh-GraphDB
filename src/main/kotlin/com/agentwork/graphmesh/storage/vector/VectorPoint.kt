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
