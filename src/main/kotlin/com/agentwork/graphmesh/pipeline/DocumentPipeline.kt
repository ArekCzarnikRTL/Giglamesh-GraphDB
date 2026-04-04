package com.agentwork.graphmesh.pipeline

import java.io.InputStream

data class IngestionReceipt(
    val documentId: String,
    val collection: String,
    val blobKey: String,
    val quadCount: Int,
    val embeddingCount: Int,
)

data class RagAnswer(
    val answer: String,
    val sources: List<RagSource>,
)

data class RagSource(
    val documentId: String,
    val chunkId: String,
    val score: Float,
    val triples: List<TripleSummary>,
)

data class TripleSummary(
    val subject: String,
    val predicate: String,
    val objectValue: String,
)

interface DocumentPipeline {

    fun ingest(
        collection: String,
        fileName: String,
        contentType: String,
        data: ByteArray,
    ): IngestionReceipt

    fun ingest(
        collection: String,
        fileName: String,
        contentType: String,
        inputStream: InputStream,
        contentLength: Long,
    ): IngestionReceipt

    fun ask(
        collection: String,
        question: String,
        maxSources: Int = 5,
    ): RagAnswer

    fun reindex(collection: String, documentId: String): IngestionReceipt

    fun deleteDocument(collection: String, documentId: String)

    fun deleteCollection(collection: String)
}
