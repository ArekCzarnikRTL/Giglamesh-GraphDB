package com.agentwork.graphmesh.extraction.chunker

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "graphmesh.chunker")
data class ChunkConfig(
    val chunkSize: Int = 2000,
    val overlapSize: Int = 200
) {
    init {
        require(chunkSize > 0) { "chunkSize must be positive: $chunkSize" }
        require(overlapSize >= 0) { "overlapSize must not be negative: $overlapSize" }
        require(overlapSize < chunkSize) { "overlapSize ($overlapSize) must be less than chunkSize ($chunkSize)" }
    }
}

data class ChunkResult(
    val text: String,
    val charOffset: Int,
    val chunkIndex: Int
)

data class ChunkCreatedEvent(
    val chunkId: String,
    val documentId: String,
    val collectionId: String,
    val chunkIndex: Int,
    val charOffset: Int,
    val charLength: Int
)
