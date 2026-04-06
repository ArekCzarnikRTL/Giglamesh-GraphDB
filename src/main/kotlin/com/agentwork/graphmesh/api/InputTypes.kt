package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.librarian.DocumentState
import com.agentwork.graphmesh.librarian.DocumentType

data class DocumentFilterInput(
    val type: DocumentType? = null,
    val state: DocumentState? = null,
    val search: String? = null
)

data class DocumentPagePayload(
    val items: List<com.agentwork.graphmesh.librarian.Document>,
    val totalCount: Int,
    val hasNextPage: Boolean
)

data class CreateCollectionInput(
    val name: String,
    val description: String?,
    val tags: List<String>?,
    val metadata: List<KeyValueInput>?
)

data class UpdateCollectionInput(
    val name: String?,
    val description: String?,
    val tags: List<String>?,
    val metadata: List<KeyValueInput>?
)

data class UploadDocumentInput(
    val collectionId: String,
    val title: String,
    val mimeType: String,
    val content: String,
    val metadata: List<KeyValueInput>?
)

data class KeyValueInput(val key: String, val value: String)
