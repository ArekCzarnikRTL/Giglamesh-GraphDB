package com.agentwork.graphmesh.collection

import java.time.Instant
import java.util.UUID

data class Collection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
    val tenantId: String? = null,
    val ownerId: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

enum class CollectionEventType { CREATED, UPDATED, DELETED }

data class CollectionEvent(
    val type: CollectionEventType,
    val collectionId: String,
    val collectionName: String
)
