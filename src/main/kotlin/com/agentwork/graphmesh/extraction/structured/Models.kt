package com.agentwork.graphmesh.extraction.structured

import com.agentwork.graphmesh.structured.TableSchema

data class DetectionResult(
    val hasTable: Boolean,
    val confidence: Double,
    val tableDescription: String? = null
)

data class InferredSchema(
    val schema: TableSchema,
    val matchesExisting: String?
)

data class StructuredExtractionResult(
    val chunkId: String,
    val tableDetected: Boolean,
    val schemaName: String? = null,
    val rowsExtracted: Int
)
