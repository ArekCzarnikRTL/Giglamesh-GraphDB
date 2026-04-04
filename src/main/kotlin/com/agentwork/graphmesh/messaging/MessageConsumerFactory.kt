package com.agentwork.graphmesh.messaging

import kotlin.reflect.KClass

interface MessageConsumerFactory {
    fun <T : Any> create(topic: String, groupId: String, messageType: KClass<T>): MessageConsumer<T>
}
