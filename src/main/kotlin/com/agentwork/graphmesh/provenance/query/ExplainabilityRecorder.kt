package com.agentwork.graphmesh.provenance.query

import com.agentwork.graphmesh.provenance.ProvenanceNamespaces
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.Quad
import com.agentwork.graphmesh.rdf.RdfTerm
import com.agentwork.graphmesh.rdf.Triple
import org.springframework.stereotype.Component

@Component
class ExplainabilityRecorder {

    fun questionQuads(question: Question): List<Quad> {
        val s = RdfTerm.Uri(question.uri)
        return listOf(
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ExplainabilityNamespaces.TG_QUESTION)),
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ProvenanceNamespaces.PROV_ACTIVITY)),
            quad(s, ExplainabilityNamespaces.TG_QUERY_TEXT, RdfTerm.Literal(question.queryText)),
            quad(s, ExplainabilityNamespaces.TG_TIMESTAMP, RdfTerm.Literal(question.timestamp.toString())),
            quad(s, ExplainabilityNamespaces.TG_MECHANISM, RdfTerm.Literal(question.mechanism.name)),
        )
    }

    fun explorationQuads(exploration: Exploration): List<Quad> {
        val s = RdfTerm.Uri(exploration.uri)
        return listOf(
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ExplainabilityNamespaces.TG_EXPLORATION)),
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ProvenanceNamespaces.PROV_ENTITY)),
            quad(s, ProvenanceNamespaces.PROV_WAS_GENERATED_BY, RdfTerm.Uri(exploration.questionUri)),
            quad(s, ExplainabilityNamespaces.TG_EDGE_COUNT, RdfTerm.Literal(exploration.edgeCount.toString())),
        )
    }

    fun focusQuads(focus: Focus): List<Quad> {
        val s = RdfTerm.Uri(focus.uri)
        val out = mutableListOf<Quad>()
        out += quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ExplainabilityNamespaces.TG_FOCUS))
        out += quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ProvenanceNamespaces.PROV_ENTITY))
        out += quad(s, ProvenanceNamespaces.PROV_WAS_DERIVED_FROM, RdfTerm.Uri(focus.explorationUri))

        for (edge in focus.selectedEdges) {
            val quoted = RdfTerm.QuotedTriple(
                Triple(
                    subject = RdfTerm.Uri(edge.subject),
                    predicate = RdfTerm.Uri(edge.predicate),
                    objectTerm = RdfTerm.Uri(edge.objectValue),
                )
            )
            out += quad(s, ExplainabilityNamespaces.TG_HAS_SELECTED_EDGE, quoted)
            out += quad(quoted, ExplainabilityNamespaces.TG_REASONING, RdfTerm.Literal(edge.reasoning))
        }
        return out
    }

    fun synthesisQuads(synthesis: Synthesis): List<Quad> {
        val s = RdfTerm.Uri(synthesis.uri)
        return listOf(
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ExplainabilityNamespaces.TG_SYNTHESIS)),
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ProvenanceNamespaces.PROV_ENTITY)),
            quad(s, ProvenanceNamespaces.PROV_WAS_DERIVED_FROM, RdfTerm.Uri(synthesis.derivedFromUri)),
            quad(s, ExplainabilityNamespaces.TG_ANSWER_TEXT, RdfTerm.Literal(synthesis.answerText)),
        )
    }

    fun analysisQuads(analysis: Analysis): List<Quad> {
        val s = RdfTerm.Uri(analysis.uri)
        val out = mutableListOf<Quad>()
        out += quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ExplainabilityNamespaces.TG_ANALYSIS))
        out += quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ProvenanceNamespaces.PROV_ENTITY))
        out += quad(s, ProvenanceNamespaces.PROV_WAS_DERIVED_FROM, RdfTerm.Uri(analysis.parentUri))
        out += quad(s, ExplainabilityNamespaces.TG_THOUGHT, RdfTerm.Literal(analysis.thought))
        out += quad(s, ExplainabilityNamespaces.TG_ITERATION_INDEX,
            RdfTerm.Literal(analysis.iterationIndex.toString()))

        analysis.action?.let {
            out += quad(s, ExplainabilityNamespaces.TG_ACTION, RdfTerm.Literal(it))
        }
        analysis.observation?.let {
            out += quad(s, ExplainabilityNamespaces.TG_OBSERVATION, RdfTerm.Literal(it))
        }
        analysis.arguments?.forEach { (k, v) ->
            out += quad(s, ExplainabilityNamespaces.TG_ARG_KEY, RdfTerm.Literal(k))
            out += quad(s, ExplainabilityNamespaces.TG_ARG_VALUE, RdfTerm.Literal("$k=$v"))
        }
        return out
    }

    fun conclusionQuads(conclusion: Conclusion): List<Quad> {
        val s = RdfTerm.Uri(conclusion.uri)
        return listOf(
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ExplainabilityNamespaces.TG_CONCLUSION)),
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ProvenanceNamespaces.PROV_ENTITY)),
            quad(s, ProvenanceNamespaces.PROV_WAS_DERIVED_FROM, RdfTerm.Uri(conclusion.parentUri)),
            quad(s, ExplainabilityNamespaces.TG_ANSWER_TEXT, RdfTerm.Literal(conclusion.answerText)),
        )
    }

    fun graphRagSessionQuads(
        question: Question,
        exploration: Exploration,
        focus: Focus,
        synthesis: Synthesis,
    ): List<Quad> = questionQuads(question) + explorationQuads(exploration) +
            focusQuads(focus) + synthesisQuads(synthesis)

    fun docRagSessionQuads(
        question: Question,
        exploration: Exploration,
        synthesis: Synthesis,
    ): List<Quad> = questionQuads(question) + explorationQuads(exploration) + synthesisQuads(synthesis)

    fun agentSessionQuads(
        question: Question,
        analyses: List<Analysis>,
        conclusion: Conclusion,
    ): List<Quad> = questionQuads(question) + analyses.flatMap { analysisQuads(it) } +
            conclusionQuads(conclusion)

    private fun quad(subject: RdfTerm, predicate: String, objectTerm: RdfTerm): Quad =
        Quad(
            subject = subject,
            predicate = RdfTerm.Uri(predicate),
            objectTerm = objectTerm,
            graph = NamedGraph.RETRIEVAL,
        )
}
