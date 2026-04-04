package com.agentwork.graphmesh.storage.blob

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "graphmesh.storage.blob")
data class BlobStoreProperties(
    val endpoint: String = "http://localhost:9000",
    val region: String = "us-east-1",
    val accessKey: String = "minioadmin",
    val secretKey: String = "minioadmin",
    val pathStyleAccess: Boolean = true,
    val defaultBucket: String = "graphmesh",
    val autoCreateBuckets: Boolean = true
)
