package com.agentwork.graphmesh.provenance.query

import java.time.Instant

enum class QueryMechanism { GRAPH_RAG, DOC_RAG, AGENT }

data class Question(
    val uri: String,
    val queryText: String,
    val timestamp: Instant,
    val mechanism: QueryMechanism,
)

data class Exploration(
    val uri: String,
    val edgeCount: Int,
    val questionUri: String,
)

data class SelectedEdgeExplanation(
    val subject: String,
    val predicate: String,
    val objectValue: String,
    val reasoning: String,
)

data class Focus(
    val uri: String,
    val selectedEdges: List<SelectedEdgeExplanation>,
    val explorationUri: String,
)

data class Synthesis(
    val uri: String,
    val answerText: String,
    val derivedFromUri: String,
)

data class AgentIterationRecord(
    val thought: String,
    val action: String?,
    val arguments: Map<String, String>?,
    val observation: String?,
)

data class Analysis(
    val uri: String,
    val iterationIndex: Int,
    val thought: String,
    val action: String?,
    val arguments: Map<String, String>?,
    val observation: String?,
    val parentUri: String,
)

data class Conclusion(
    val uri: String,
    val answerText: String,
    val parentUri: String,
)

data class ExplanationChain(
    val question: Question,
    val exploration: Exploration?,
    val focus: Focus?,
    val analyses: List<Analysis>?,
    val synthesis: Synthesis?,
    val conclusion: Conclusion?,
    val mechanism: QueryMechanism,
)
