package com.agentwork.graphmesh.storage.blob

import java.time.Instant

data class BlobInfo(
    val key: String,
    val size: Long,
    val contentType: String?,
    val lastModified: Instant,
    val metadata: Map<String, String> = emptyMap()
)
