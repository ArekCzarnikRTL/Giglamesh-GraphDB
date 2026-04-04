package com.agentwork.graphmesh.messaging

interface TopicRegistry {
    fun register(config: KafkaTopicConfig)
    fun allTopics(): List<KafkaTopicConfig>
    fun ensureTopicsExist()
}
