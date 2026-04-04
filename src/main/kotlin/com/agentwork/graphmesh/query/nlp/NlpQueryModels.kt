package com.agentwork.graphmesh.query.nlp

data class NlpQuery(
    val question: String,
    val collectionId: String,
    val forceIntent: QueryIntent? = null
)

enum class QueryIntent {
    GRAPH_QUERY,
    DOCUMENT_QUERY,
    STRUCTURED_QUERY,
    HYBRID
}

data class DetectedIntent(
    val intent: QueryIntent,
    val confidence: Double,
    val reasoning: String,
    val reformulatedQuestion: String? = null
)

data class NlpQueryResult(
    val answer: String,
    val detectedIntent: DetectedIntent,
    val wasReformulated: Boolean,
    val effectiveQuestion: String,
    val durationMs: Long,
    val sources: List<String>
)
