package com.agentwork.graphmesh.messaging

interface MessageConsumer<T : Any> {
    val topic: String
    val groupId: String
    fun subscribe(handler: suspend (Message<T>) -> Unit)
    fun close()
}
