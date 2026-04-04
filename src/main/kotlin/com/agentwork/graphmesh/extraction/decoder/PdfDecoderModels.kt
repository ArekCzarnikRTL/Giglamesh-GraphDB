package com.agentwork.graphmesh.extraction.decoder

data class PageExtractionResult(
    val pageNumber: Int,
    val text: String,
    val totalPages: Int
)

data class PageExtractedEvent(
    val documentId: String,
    val parentDocumentId: String,
    val collectionId: String,
    val pageNumber: Int,
    val charCount: Int
)

class PdfDecodingException(
    documentId: String,
    cause: Throwable
) : RuntimeException("PDF decoding failed for document '$documentId': ${cause.message}", cause)
