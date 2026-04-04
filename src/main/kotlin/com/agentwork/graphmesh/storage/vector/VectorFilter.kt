package com.agentwork.graphmesh.storage.vector

sealed class VectorFilter {
    data class Equals(val field: String, val value: Any) : VectorFilter()
    data class In(val field: String, val values: List<Any>) : VectorFilter()
    data class And(val filters: List<VectorFilter>) : VectorFilter()
    data class Or(val filters: List<VectorFilter>) : VectorFilter()
    data class Not(val filter: VectorFilter) : VectorFilter()
}
