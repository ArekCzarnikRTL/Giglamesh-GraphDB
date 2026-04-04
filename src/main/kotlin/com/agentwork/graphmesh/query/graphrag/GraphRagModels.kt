package com.agentwork.graphmesh.query.graphrag

import com.agentwork.graphmesh.storage.StoredQuad

data class GraphRagQuery(
    val question: String,
    val collectionId: String,
    val maxEdges: Int = 150,
    val maxDepth: Int = 2,
    val maxSelectedEdges: Int = 30
)

data class GraphRagResult(
    val answer: String,
    val selectedEdges: List<SelectedEdge>,
    val retrievedEdgeCount: Int,
    val durationMs: Long
)

data class SelectedEdge(
    val subject: String,
    val predicate: String,
    val objectValue: String,
    val dataset: String,
    val reasoning: String,
    val relevanceScore: Double
)
