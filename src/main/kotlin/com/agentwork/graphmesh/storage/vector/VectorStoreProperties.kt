package com.agentwork.graphmesh.storage.vector

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "graphmesh.storage.vector")
data class VectorStoreProperties(
    val host: String = "localhost",
    val grpcPort: Int = 6334,
    val apiKey: String? = null,
    val useTls: Boolean = false
)
