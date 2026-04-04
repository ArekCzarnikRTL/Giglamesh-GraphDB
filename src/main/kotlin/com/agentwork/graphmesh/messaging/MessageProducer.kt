package com.agentwork.graphmesh.messaging

interface MessageProducer<T : Any> {
    val topic: String
    suspend fun send(message: T, headers: Map<String, String> = emptyMap())
    suspend fun sendWithKey(key: String, message: T, headers: Map<String, String> = emptyMap())
    fun close()
}
