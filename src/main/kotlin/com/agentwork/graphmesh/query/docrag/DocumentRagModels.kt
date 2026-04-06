package com.agentwork.graphmesh.query.docrag

import java.util.UUID

data class DocumentRagQuery(
    val question: String,
    val collectionId: String,
    val topK: Int = 10,
    val similarityThreshold: Float = 0.5f
)

data class DocumentRagResult(
    val sessionId: UUID,
    val answer: String,
    val sources: List<SourceAttribution>,
    val retrievedChunkCount: Int,
    val durationMs: Long
)

data class SourceAttribution(
    val chunkId: String,
    val documentId: String,
    val documentTitle: String,
    val pageNumber: Int?,
    val score: Float,
    val snippet: String
)

data class RetrievedChunk(
    val chunkId: String,
    val documentId: String,
    val text: String,
    val score: Float
)
