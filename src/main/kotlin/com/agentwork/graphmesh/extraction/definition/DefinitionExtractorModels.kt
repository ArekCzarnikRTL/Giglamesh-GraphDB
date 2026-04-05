package com.agentwork.graphmesh.extraction.definition

data class DefinitionResult(
    val entity: String,
    val definition: String
)

data class DefinitionExtractionResult(
    val chunkId: String,
    val definitionsExtracted: Int,
    val entitiesFound: List<String>
)
