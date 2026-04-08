package com.agentwork.graphmesh.query.docrag

import java.util.UUID

data class DocumentRagQuery(
    val question: String,
    val collectionId: String,
    val topK: Int = 10,
    /**
     * Minimum cosine similarity for a chunk to be considered a hit.
     *
     * The default of `0.3f` is a compromise that works for both Ollama
     * (`nomic-embed-text`, scores ~0.5–0.8 for related content) and OpenAI
     * (`text-embedding-3-small`, scores ~0.25–0.5 for related content). Tune
     * via `graphmesh.docrag.similarity-threshold` in `application.yml` or by
     * passing a per-query value.
     */
    val similarityThreshold: Float = 0.3f
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
