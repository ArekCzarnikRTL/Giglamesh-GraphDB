package com.agentwork.graphmesh.config

import java.time.Instant

enum class ConfigType {
    ONTOLOGY, FLOW, TOOL, PARAMETER, COLLECTION_SETTINGS, LLM_SETTINGS
}

enum class ConfigAction { CREATED, UPDATED, DELETED }

data class ConfigItem(
    val id: String,
    val type: ConfigType,
    val key: String,
    val value: String,
    val version: Int = 1,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val createdBy: String = "system",
    val description: String = ""
)

data class ConfigChangedEvent(
    val configId: String,
    val configType: ConfigType,
    val key: String,
    val action: ConfigAction,
    val version: Int
)
