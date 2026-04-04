package com.agentwork.graphmesh.messaging.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "graphmesh.messaging.kafka")
data class GraphMeshKafkaProperties(
    val enabled: Boolean = true,
    val bootstrapServers: String = "localhost:9092",
    val groupIdPrefix: String = "graphmesh",
    val autoCreateTopics: Boolean = true,
    val defaultPartitions: Int = 3,
    val defaultReplicationFactor: Short = 1,
    val gracefulShutdown: GracefulShutdownProperties = GracefulShutdownProperties()
)

data class GracefulShutdownProperties(
    val enabled: Boolean = true,
    val drainTimeoutMs: Long = 5000,
    val awaitTerminationMs: Long = 10000
)
