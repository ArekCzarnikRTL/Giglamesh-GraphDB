package com.agentwork.graphmesh.extraction.agent

data class ExtractionStrategy(
    val name: String,
    val systemPrompt: String,
    val maxIterations: Int = 5,
    val outputTypes: List<OutputType> = listOf(OutputType.RELATIONSHIP, OutputType.DEFINITION)
)

enum class OutputType { DEFINITION, RELATIONSHIP, ENTITY, ATTRIBUTE }

sealed class ExtractedItem {
    data class Definition(val entity: String, val definition: String) : ExtractedItem()
    data class Relationship(
        val subject: String,
        val predicate: String,
        val objectValue: String,
        val objectIsEntity: Boolean = true
    ) : ExtractedItem()
    data class Entity(val name: String, val entityType: String? = null) : ExtractedItem()
    data class Attribute(val entity: String, val attribute: String, val value: String) : ExtractedItem()
}

data class AgentExtractionResult(
    val chunkId: String,
    val extractedItems: List<ExtractedItem>,
    val strategy: String
)
