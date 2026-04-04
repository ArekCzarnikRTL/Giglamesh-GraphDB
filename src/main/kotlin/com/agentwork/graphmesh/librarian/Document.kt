package com.agentwork.graphmesh.librarian

import java.time.Instant

enum class DocumentState { UPLOADED, PROCESSING, EXTRACTED, FAILED }

enum class DocumentType { SOURCE, PAGE, CHUNK }

data class Document(
    val id: String,
    val collectionId: String,
    val parentId: String? = null,
    val type: DocumentType = DocumentType.SOURCE,
    val state: DocumentState = DocumentState.UPLOADED,
    val title: String = "",
    val mimeType: String = "application/octet-stream",
    val contentUri: String = "",
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
