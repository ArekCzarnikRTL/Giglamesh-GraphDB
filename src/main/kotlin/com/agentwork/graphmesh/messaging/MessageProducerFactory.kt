package com.agentwork.graphmesh.messaging

import kotlin.reflect.KClass

interface MessageProducerFactory {
    fun <T : Any> create(topic: String, messageType: KClass<T>): MessageProducer<T>
}
