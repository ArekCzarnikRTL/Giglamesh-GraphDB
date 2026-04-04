package com.agentwork.graphmesh.messaging

import org.apache.kafka.clients.admin.NewTopic

data class KafkaTopicConfig(
    val name: String,
    val partitions: Int = 3,
    val replicationFactor: Short = 1,
    val configs: Map<String, String> = emptyMap()
) {
    init {
        require(name.startsWith("graphmesh.")) {
            "Topic name must follow 'graphmesh.<domain>.<action>' convention, got: $name"
        }
    }

    fun toNewTopic(): NewTopic =
        NewTopic(name, partitions, replicationFactor).configs(configs)
}
