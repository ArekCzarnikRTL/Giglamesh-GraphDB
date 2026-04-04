package com.agentwork.graphmesh.extraction.relationship

data class ExtractionResult(
    val chunkId: String,
    val triplesExtracted: Int,
    val entitiesFound: Int
)
