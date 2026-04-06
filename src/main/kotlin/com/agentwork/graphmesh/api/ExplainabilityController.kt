package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.provenance.query.Analysis
import com.agentwork.graphmesh.provenance.query.Conclusion
import com.agentwork.graphmesh.provenance.query.ExplanationChain
import com.agentwork.graphmesh.provenance.query.ExplanationChainLoader
import com.agentwork.graphmesh.provenance.query.Exploration
import com.agentwork.graphmesh.provenance.query.Focus
import com.agentwork.graphmesh.provenance.query.Question
import com.agentwork.graphmesh.provenance.query.QueryMechanism
import com.agentwork.graphmesh.provenance.query.SelectedEdgeExplanation
import com.agentwork.graphmesh.provenance.query.Synthesis
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class ExplainabilityController(
    private val chainLoader: ExplanationChainLoader,
) {

    @QueryMapping
    fun explanationChain(
        @Argument collectionId: String,
        @Argument sessionUri: String,
    ): ExplanationChainView? {
        val chain = chainLoader.load(collectionId, sessionUri) ?: return null
        return ExplanationChainView.from(chain)
    }

    @QueryMapping
    fun explanationSessions(
        @Argument collectionId: String,
        @Argument mechanism: QueryMechanism?,
        @Argument limit: Int,
    ): List<QuestionExplanationView> =
        chainLoader.listSessions(collectionId, mechanism, limit)
            .map { QuestionExplanationView.from(it) }
}

// --- View DTOs that the GraphQL layer reads via property names matching the schema ---

data class ExplanationChainView(
    val question: QuestionExplanationView,
    val exploration: ExplorationExplanationView?,
    val focus: FocusExplanationView?,
    val analyses: List<AnalysisExplanationView>?,
    val synthesis: SynthesisExplanationView?,
    val conclusion: ConclusionExplanationView?,
    val mechanism: QueryMechanism,
) {
    companion object {
        fun from(c: ExplanationChain) = ExplanationChainView(
            question = QuestionExplanationView.from(c.question),
            exploration = c.exploration?.let { ExplorationExplanationView.from(it) },
            focus = c.focus?.let { FocusExplanationView.from(it) },
            analyses = c.analyses?.map { AnalysisExplanationView.from(it) },
            synthesis = c.synthesis?.let { SynthesisExplanationView.from(it) },
            conclusion = c.conclusion?.let { ConclusionExplanationView.from(it) },
            mechanism = c.mechanism,
        )
    }
}

data class QuestionExplanationView(
    val uri: String,
    val queryText: String,
    val timestamp: String,
    val mechanism: QueryMechanism,
) {
    companion object {
        fun from(q: Question) = QuestionExplanationView(
            uri = q.uri,
            queryText = q.queryText,
            timestamp = q.timestamp.toString(),
            mechanism = q.mechanism,
        )
    }
}

data class ExplorationExplanationView(val uri: String, val edgeCount: Int) {
    companion object {
        fun from(e: Exploration) = ExplorationExplanationView(e.uri, e.edgeCount)
    }
}

data class FocusExplanationView(val uri: String, val selectedEdges: List<SelectedEdgeDetailView>) {
    companion object {
        fun from(f: Focus) = FocusExplanationView(
            uri = f.uri,
            selectedEdges = f.selectedEdges.map { SelectedEdgeDetailView.from(it) },
        )
    }
}

data class SelectedEdgeDetailView(
    val subject: String, val predicate: String, val objectValue: String, val reasoning: String,
) {
    companion object {
        fun from(e: SelectedEdgeExplanation) = SelectedEdgeDetailView(
            e.subject, e.predicate, e.objectValue, e.reasoning,
        )
    }
}

data class AnalysisExplanationView(
    val uri: String,
    val iterationIndex: Int,
    val thought: String,
    val action: String?,
    val arguments: List<ArgumentEntryView>?,
    val observation: String?,
) {
    companion object {
        fun from(a: Analysis) = AnalysisExplanationView(
            uri = a.uri,
            iterationIndex = a.iterationIndex,
            thought = a.thought,
            action = a.action,
            arguments = a.arguments?.map { (k, v) -> ArgumentEntryView(k, v) },
            observation = a.observation,
        )
    }
}

data class ArgumentEntryView(val key: String, val value: String)

data class SynthesisExplanationView(val uri: String, val answerText: String) {
    companion object {
        fun from(s: Synthesis) = SynthesisExplanationView(s.uri, s.answerText)
    }
}

data class ConclusionExplanationView(val uri: String, val answerText: String) {
    companion object {
        fun from(c: Conclusion) = ConclusionExplanationView(c.uri, c.answerText)
    }
}
