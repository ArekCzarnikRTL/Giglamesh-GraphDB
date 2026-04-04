package com.agentwork.graphmesh.api

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
