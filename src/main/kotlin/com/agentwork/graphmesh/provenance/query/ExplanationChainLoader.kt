package com.agentwork.graphmesh.provenance.query

import com.agentwork.graphmesh.provenance.ProvenanceNamespaces
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ExplanationChainLoader(private val quadStore: QuadStore) {

    fun load(collection: String, sessionUri: String): ExplanationChain? {
        val questionQuads = quadStore.query(collection, QuadQuery(
            subject = sessionUri,
            dataset = NamedGraph.RETRIEVAL,
        ))
        if (questionQuads.isEmpty()) return null

        val mechanism = questionQuads
            .firstOrNull { it.predicate == ExplainabilityNamespaces.TG_MECHANISM }
            ?.objectValue
            ?.let { runCatching { QueryMechanism.valueOf(it) }.getOrNull() }
            ?: return null

        val question = Question(
            uri = sessionUri,
            queryText = questionQuads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_QUERY_TEXT }
                ?.objectValue ?: "",
            timestamp = questionQuads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_TIMESTAMP }
                ?.objectValue?.let { Instant.parse(it) } ?: Instant.EPOCH,
            mechanism = mechanism,
        )

        return when (mechanism) {
            QueryMechanism.GRAPH_RAG -> loadGraphRagChain(collection, question)
            QueryMechanism.DOC_RAG   -> loadDocRagChain(collection, question)
            QueryMechanism.AGENT     -> loadAgentChain(collection, question)
        }
    }

    fun listSessions(
        collection: String,
        mechanism: QueryMechanism?,
        limit: Int,
    ): List<Question> {
        val typeQuads = quadStore.query(collection, QuadQuery(
            predicate = ProvenanceNamespaces.RDF_TYPE,
            objectValue = ExplainabilityNamespaces.TG_QUESTION,
            dataset = NamedGraph.RETRIEVAL,
        ))
        return typeQuads.asSequence()
            .map { it.subject }
            .distinct()
            .mapNotNull { uri ->
                val quads = quadStore.query(collection, QuadQuery(
                    subject = uri,
                    dataset = NamedGraph.RETRIEVAL,
                ))
                buildQuestion(uri, quads)
            }
            .filter { mechanism == null || it.mechanism == mechanism }
            .sortedByDescending { it.timestamp }
            .take(limit)
            .toList()
    }

    private fun buildQuestion(uri: String, quads: List<StoredQuad>): Question? {
        val mech = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_MECHANISM }
            ?.objectValue
            ?.let { runCatching { QueryMechanism.valueOf(it) }.getOrNull() }
            ?: return null
        return Question(
            uri = uri,
            queryText = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_QUERY_TEXT }
                ?.objectValue ?: "",
            timestamp = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_TIMESTAMP }
                ?.objectValue?.let { Instant.parse(it) } ?: Instant.EPOCH,
            mechanism = mech,
        )
    }

    // --- mechanism-specific assembly ---

    private fun loadGraphRagChain(collection: String, question: Question): ExplanationChain {
        val explorationUri = findChildUri(collection, question.uri,
            ProvenanceNamespaces.PROV_WAS_GENERATED_BY, reversed = true)
        val exploration = explorationUri?.let { loadExploration(collection, it, question.uri) }

        val focusUri = exploration?.let {
            findChildUri(collection, it.uri, ProvenanceNamespaces.PROV_WAS_DERIVED_FROM, reversed = true)
        }
        val focus = focusUri?.let { loadFocus(collection, it, exploration.uri) }

        val synthesisUri = focus?.let {
            findChildUri(collection, it.uri, ProvenanceNamespaces.PROV_WAS_DERIVED_FROM, reversed = true)
        }
        val synthesis = synthesisUri?.let { loadSynthesis(collection, it, focus.uri) }

        return ExplanationChain(
            question = question,
            exploration = exploration,
            focus = focus,
            analyses = null,
            synthesis = synthesis,
            conclusion = null,
            mechanism = QueryMechanism.GRAPH_RAG,
        )
    }

    private fun loadDocRagChain(collection: String, question: Question): ExplanationChain {
        val explorationUri = findChildUri(collection, question.uri,
            ProvenanceNamespaces.PROV_WAS_GENERATED_BY, reversed = true)
        val exploration = explorationUri?.let { loadExploration(collection, it, question.uri) }

        val synthesisUri = exploration?.let {
            findChildUri(collection, it.uri, ProvenanceNamespaces.PROV_WAS_DERIVED_FROM, reversed = true)
        }
        val synthesis = synthesisUri?.let { loadSynthesis(collection, it, exploration.uri) }

        return ExplanationChain(
            question = question,
            exploration = exploration,
            focus = null,
            analyses = null,
            synthesis = synthesis,
            conclusion = null,
            mechanism = QueryMechanism.DOC_RAG,
        )
    }

    private fun loadAgentChain(collection: String, question: Question): ExplanationChain {
        // Walk forward: find Analysis nodes whose parent chain reaches questionUri
        val allAnalyses = quadStore.query(collection, QuadQuery(
            predicate = ProvenanceNamespaces.RDF_TYPE,
            objectValue = ExplainabilityNamespaces.TG_ANALYSIS,
            dataset = NamedGraph.RETRIEVAL,
        )).map { it.subject }.distinct()

        val analyses = allAnalyses.mapNotNull { loadAnalysis(collection, it) }
            .filter { reachesQuestion(collection, it, question.uri) }
            .sortedBy { it.iterationIndex }

        val conclusionUri = quadStore.query(collection, QuadQuery(
            predicate = ProvenanceNamespaces.RDF_TYPE,
            objectValue = ExplainabilityNamespaces.TG_CONCLUSION,
            dataset = NamedGraph.RETRIEVAL,
        )).map { it.subject }
         .firstOrNull { uri ->
             val parent = parentOf(collection, uri) ?: return@firstOrNull false
             analyses.any { it.uri == parent }
         }
        val conclusion = conclusionUri?.let { loadConclusion(collection, it) }

        return ExplanationChain(
            question = question,
            exploration = null,
            focus = null,
            analyses = analyses,
            synthesis = null,
            conclusion = conclusion,
            mechanism = QueryMechanism.AGENT,
        )
    }

    // --- entity loaders ---

    private fun loadExploration(collection: String, uri: String, questionUri: String): Exploration {
        val quads = quadStore.query(collection, QuadQuery(subject = uri, dataset = NamedGraph.RETRIEVAL))
        return Exploration(
            uri = uri,
            edgeCount = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_EDGE_COUNT }
                ?.objectValue?.toIntOrNull() ?: 0,
            questionUri = questionUri,
        )
    }

    private fun loadFocus(collection: String, uri: String, explorationUri: String): Focus {
        val reasoningQuads = quadStore.query(collection, QuadQuery(
            predicate = ExplainabilityNamespaces.TG_REASONING,
            dataset = NamedGraph.RETRIEVAL,
        ))
        val edges = reasoningQuads.mapNotNull { quad ->
            val sub = quad.subject
            if (!sub.startsWith("<<") || !sub.endsWith(">>")) return@mapNotNull null
            val inner = sub.removePrefix("<<").removeSuffix(">>")
            val parts = inner.split("|", limit = 3)
            if (parts.size != 3) return@mapNotNull null
            SelectedEdgeExplanation(
                subject = parts[0],
                predicate = parts[1],
                objectValue = parts[2],
                reasoning = quad.objectValue,
            )
        }
        return Focus(uri = uri, selectedEdges = edges, explorationUri = explorationUri)
    }

    private fun loadSynthesis(collection: String, uri: String, parentUri: String): Synthesis {
        val quads = quadStore.query(collection, QuadQuery(subject = uri, dataset = NamedGraph.RETRIEVAL))
        return Synthesis(
            uri = uri,
            answerText = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_ANSWER_TEXT }
                ?.objectValue ?: "",
            derivedFromUri = parentUri,
        )
    }

    private fun loadAnalysis(collection: String, uri: String): Analysis? {
        val quads = quadStore.query(collection, QuadQuery(subject = uri, dataset = NamedGraph.RETRIEVAL))
        if (quads.isEmpty()) return null
        val parent = quads.firstOrNull { it.predicate == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM }
            ?.objectValue ?: return null
        val args = quads.filter { it.predicate == ExplainabilityNamespaces.TG_ARG_VALUE }
            .mapNotNull {
                val parts = it.objectValue.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap().ifEmpty { null }
        return Analysis(
            uri = uri,
            iterationIndex = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_ITERATION_INDEX }
                ?.objectValue?.toIntOrNull() ?: 0,
            thought = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_THOUGHT }
                ?.objectValue ?: "",
            action = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_ACTION }
                ?.objectValue,
            arguments = args,
            observation = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_OBSERVATION }
                ?.objectValue,
            parentUri = parent,
        )
    }

    private fun loadConclusion(collection: String, uri: String): Conclusion {
        val quads = quadStore.query(collection, QuadQuery(subject = uri, dataset = NamedGraph.RETRIEVAL))
        return Conclusion(
            uri = uri,
            answerText = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_ANSWER_TEXT }
                ?.objectValue ?: "",
            parentUri = quads.firstOrNull { it.predicate == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM }
                ?.objectValue ?: "",
        )
    }

    // --- helpers ---

    /**
     * @param reversed when true, find a quad whose objectValue == parentUri (children pointing back)
     *                 when false, find a quad whose subject == parentUri (forward children)
     */
    private fun findChildUri(
        collection: String,
        parentUri: String,
        predicate: String,
        reversed: Boolean = false,
    ): String? {
        val q = if (reversed) {
            QuadQuery(predicate = predicate, objectValue = parentUri, dataset = NamedGraph.RETRIEVAL)
        } else {
            QuadQuery(subject = parentUri, predicate = predicate, dataset = NamedGraph.RETRIEVAL)
        }
        val rows = quadStore.query(collection, q)
        return if (reversed) rows.firstOrNull()?.subject else rows.firstOrNull()?.objectValue
    }

    private fun parentOf(collection: String, uri: String): String? =
        quadStore.query(collection, QuadQuery(
            subject = uri,
            predicate = ProvenanceNamespaces.PROV_WAS_DERIVED_FROM,
            dataset = NamedGraph.RETRIEVAL,
        )).firstOrNull()?.objectValue

    private fun reachesQuestion(collection: String, analysis: Analysis, questionUri: String): Boolean {
        var current: String? = analysis.parentUri
        var hops = 0
        while (current != null && hops < 100) {
            if (current == questionUri) return true
            current = parentOf(collection, current)
            hops++
        }
        return false
    }
}
