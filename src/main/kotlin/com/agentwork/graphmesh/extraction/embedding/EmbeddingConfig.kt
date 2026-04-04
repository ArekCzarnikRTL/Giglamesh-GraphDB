package com.agentwork.graphmesh.extraction.embedding

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "graphmesh.embedding")
data class EmbeddingConfig(
    val model: String = "text-embedding-3-small"
)
