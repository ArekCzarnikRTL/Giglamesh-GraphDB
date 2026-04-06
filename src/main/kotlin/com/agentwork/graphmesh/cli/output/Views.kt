package com.agentwork.graphmesh.cli.output

data class CollectionView(
    val id: String,
    val name: String,
    val description: String?,
    val tags: List<String>,
    val createdAt: String
)

data class DocumentView(
    val id: String,
    val title: String,
    val mimeType: String,
    val type: String,
    val state: String,
    val createdAt: String
)

data class DocumentInfoView(
    val id: String,
    val collectionId: String,
    val title: String,
    val mimeType: String,
    val type: String,
    val state: String,
    val parentId: String?,
    val createdAt: String,
    val metadata: List<Pair<String, String>>
)

data class GraphRagResponseView(
    val sessionId: String,
    val answer: String,
    val retrievedEdgeCount: Int,
    val durationMs: Int,
    val selectedEdges: List<SelectedEdgeView>
)

data class SelectedEdgeView(
    val subject: String,
    val predicate: String,
    val objectValue: String,
    val reasoning: String,
    val relevanceScore: Double
)

data class DocRagResponseView(
    val sessionId: String,
    val answer: String,
    val retrievedChunkCount: Int,
    val durationMs: Int,
    val sources: List<DocRagSourceView>
)

data class DocRagSourceView(
    val chunkId: String,
    val documentId: String,
    val documentTitle: String,
    val pageNumber: Int?,
    val score: Double,
    val snippet: String
)

data class NlpResponseView(
    val answer: String,
    val detectedIntent: String,
    val intentConfidence: Double,
    val wasReformulated: Boolean,
    val effectiveQuestion: String,
    val durationMs: Int,
    val sources: List<String>
)

data class ConfigEntryView(
    val id: String,
    val type: String,
    val key: String,
    val value: String,
    val version: Int
)

data class QuestionExplanationView(
    val uri: String,
    val queryText: String,
    val timestamp: String,
    val mechanism: String
)

data class ExplanationChainView(
    val question: QuestionExplanationView,
    val mechanism: String,
    val exploration: ExplorationView?,
    val focus: FocusView?,
    val analyses: List<AnalysisView>?,
    val synthesis: SynthesisView?,
    val conclusion: ConclusionView?
)

data class ExplorationView(val uri: String, val edgeCount: Int)

data class FocusView(val uri: String, val selectedEdges: List<FocusEdgeView>)

data class FocusEdgeView(
    val subject: String,
    val predicate: String,
    val objectValue: String,
    val reasoning: String
)

data class AnalysisView(
    val uri: String,
    val iterationIndex: Int,
    val thought: String,
    val action: String?,
    val arguments: List<Pair<String, String>>,
    val observation: String?
)

data class SynthesisView(val uri: String, val answerText: String)
data class ConclusionView(val uri: String, val answerText: String)

data class DocumentNodeView(
    val id: String,
    val title: String,
    val type: String,
    val children: List<DocumentNodeView>
)
