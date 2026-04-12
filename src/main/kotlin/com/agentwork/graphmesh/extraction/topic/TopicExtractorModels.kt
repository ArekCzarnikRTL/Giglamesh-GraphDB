package com.agentwork.graphmesh.extraction.topic

data class TopicResult(
    val topic: String,
    val confidence: Double,
    val rationale: String? = null
)

data class TopicExtractionResult(
    val chunkId: String,
    val topicsExtracted: Int,
    val topics: List<String>
)
