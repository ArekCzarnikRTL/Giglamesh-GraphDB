package com.agentwork.graphmesh.messaging

data class Message<T>(
    val payload: T,
    val key: String?,
    val headers: Map<String, String>,
    val topic: String,
    val partition: Int,
    val offset: Long,
    val timestamp: Long
)
