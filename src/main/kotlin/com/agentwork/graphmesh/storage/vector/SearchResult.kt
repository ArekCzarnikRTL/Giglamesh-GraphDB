package com.agentwork.graphmesh.storage.vector

data class SearchResult(
    val id: String,
    val score: Float,
    val payload: Map<String, Any> = emptyMap()
)
