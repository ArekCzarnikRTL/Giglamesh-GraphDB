package com.agentwork.graphmesh.provenance.query

import com.agentwork.graphmesh.provenance.ProvenanceNamespaces
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.RdfTerm
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExplainabilityRecorderTest {

    private val recorder = ExplainabilityRecorder()

    private fun question(mechanism: QueryMechanism) = Question(
        uri = "urn:graphmesh:question:s1",
        queryText = "What is X?",
        timestamp = Instant.parse("2026-04-06T12:00:00Z"),
        mechanism = mechanism,
    )

    @Test
    fun `questionQuads emits rdf type Question and PROV Activity`() {
        val q = question(QueryMechanism.GRAPH_RAG)
        val quads = recorder.questionQuads(q)

        assertTrue(quads.all { it.graph == NamedGraph.RETRIEVAL })
        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.RDF_TYPE &&
            (it.objectTerm as? RdfTerm.Uri)?.value == ExplainabilityNamespaces.TG_QUESTION
        })
        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.RDF_TYPE &&
            (it.objectTerm as? RdfTerm.Uri)?.value == ProvenanceNamespaces.PROV_ACTIVITY
        })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_QUERY_TEXT &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "What is X?"
        })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_MECHANISM &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "GRAPH_RAG"
        })
    }

    @Test
    fun `explorationQuads links to question via prov wasGeneratedBy`() {
        val e = Exploration("urn:graphmesh:prov:retrieval:s1", 42, "urn:graphmesh:question:s1")
        val quads = recorder.explorationQuads(e)

        assertTrue(quads.all { it.graph == NamedGraph.RETRIEVAL })
        assertTrue(quads.any {
            it.subject == RdfTerm.Uri("urn:graphmesh:prov:retrieval:s1") &&
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_GENERATED_BY &&
            (it.objectTerm as? RdfTerm.Uri)?.value == "urn:graphmesh:question:s1"
        })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_EDGE_COUNT &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "42"
        })
    }

    @Test
    fun `focusQuads emits a quoted triple with reasoning literal per selected edge`() {
        val edges = listOf(
            SelectedEdgeExplanation("urn:s1", "urn:p1", "urn:o1", "because A"),
            SelectedEdgeExplanation("urn:s2", "urn:p2", "urn:o2", "because B"),
        )
        val f = Focus("urn:graphmesh:prov:selection:s1", edges, "urn:graphmesh:prov:retrieval:s1")
        val quads = recorder.focusQuads(f)

        assertTrue(quads.all { it.graph == NamedGraph.RETRIEVAL })

        // Focus -> wasDerivedFrom -> Exploration
        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM &&
            (it.objectTerm as? RdfTerm.Uri)?.value == "urn:graphmesh:prov:retrieval:s1"
        })

        // Two reasoning literals attached to QuotedTriple subjects
        val reasoningQuads = quads.filter {
            it.predicate.value == ExplainabilityNamespaces.TG_REASONING &&
            it.subject is RdfTerm.QuotedTriple
        }
        assertEquals(2, reasoningQuads.size)
        assertTrue(reasoningQuads.any { (it.objectTerm as RdfTerm.Literal).value == "because A" })
        assertTrue(reasoningQuads.any { (it.objectTerm as RdfTerm.Literal).value == "because B" })
    }

    @Test
    fun `synthesisQuads links to derivation source`() {
        val s = Synthesis("urn:graphmesh:prov:answer:s1", "the answer", "urn:graphmesh:prov:selection:s1")
        val quads = recorder.synthesisQuads(s)

        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM &&
            (it.objectTerm as? RdfTerm.Uri)?.value == "urn:graphmesh:prov:selection:s1"
        })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_ANSWER_TEXT &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "the answer"
        })
    }

    @Test
    fun `analysisQuads emits thought action observation and chains via wasDerivedFrom`() {
        val a = Analysis(
            uri = "urn:graphmesh:agent:s1/i2",
            iterationIndex = 2,
            thought = "I should look up X",
            action = "knowledge_query",
            arguments = mapOf("query" to "X"),
            observation = "X is foo",
            parentUri = "urn:graphmesh:agent:s1/i1",
        )
        val quads = recorder.analysisQuads(a)

        assertTrue(quads.all { it.graph == NamedGraph.RETRIEVAL })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_THOUGHT &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "I should look up X"
        })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_ACTION &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "knowledge_query"
        })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_OBSERVATION &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "X is foo"
        })
        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM &&
            (it.objectTerm as? RdfTerm.Uri)?.value == "urn:graphmesh:agent:s1/i1"
        })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_ITERATION_INDEX &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "2"
        })
    }

    @Test
    fun `analysisQuads omits null action observation and arguments`() {
        val a = Analysis(
            uri = "urn:graphmesh:agent:s1/i1",
            iterationIndex = 1,
            thought = "I have enough info",
            action = null,
            arguments = null,
            observation = null,
            parentUri = "urn:graphmesh:question:s1",
        )
        val quads = recorder.analysisQuads(a)

        assertTrue(quads.none { it.predicate.value == ExplainabilityNamespaces.TG_ACTION })
        assertTrue(quads.none { it.predicate.value == ExplainabilityNamespaces.TG_OBSERVATION })
        assertTrue(quads.none { it.predicate.value == ExplainabilityNamespaces.TG_ARG_KEY })
    }

    @Test
    fun `conclusionQuads emits answer text and chains to last analysis`() {
        val c = Conclusion("urn:graphmesh:agent:s1/final", "final answer", "urn:graphmesh:agent:s1/i3")
        val quads = recorder.conclusionQuads(c)

        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_ANSWER_TEXT &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "final answer"
        })
        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM &&
            (it.objectTerm as? RdfTerm.Uri)?.value == "urn:graphmesh:agent:s1/i3"
        })
    }

    @Test
    fun `graphRagSessionQuads contains all four entity types`() {
        val q = question(QueryMechanism.GRAPH_RAG)
        val e = Exploration("urn:graphmesh:prov:retrieval:s1", 5, q.uri)
        val f = Focus("urn:graphmesh:prov:selection:s1",
            listOf(SelectedEdgeExplanation("a","p","b","r")), e.uri)
        val s = Synthesis("urn:graphmesh:prov:answer:s1", "ans", f.uri)

        val quads = recorder.graphRagSessionQuads(q, e, f, s)

        assertTrue(quads.any { (it.objectTerm as? RdfTerm.Uri)?.value == ExplainabilityNamespaces.TG_QUESTION })
        assertTrue(quads.any { (it.objectTerm as? RdfTerm.Uri)?.value == ExplainabilityNamespaces.TG_EXPLORATION })
        assertTrue(quads.any { (it.objectTerm as? RdfTerm.Uri)?.value == ExplainabilityNamespaces.TG_FOCUS })
        assertTrue(quads.any { (it.objectTerm as? RdfTerm.Uri)?.value == ExplainabilityNamespaces.TG_SYNTHESIS })
    }

    @Test
    fun `docRagSessionQuads has no focus type`() {
        val q = question(QueryMechanism.DOC_RAG)
        val e = Exploration("urn:graphmesh:prov:retrieval:s1", 5, q.uri)
        val s = Synthesis("urn:graphmesh:prov:answer:s1", "ans", e.uri)

        val quads = recorder.docRagSessionQuads(q, e, s)

        assertTrue(quads.none { (it.objectTerm as? RdfTerm.Uri)?.value == ExplainabilityNamespaces.TG_FOCUS })
        assertTrue(quads.any { (it.objectTerm as? RdfTerm.Uri)?.value == ExplainabilityNamespaces.TG_SYNTHESIS })
    }

    @Test
    fun `agentSessionQuads chains analyses linearly`() {
        val q = question(QueryMechanism.AGENT)
        val a1 = Analysis("urn:graphmesh:agent:s1/i1", 1, "t1", null, null, null, q.uri)
        val a2 = Analysis("urn:graphmesh:agent:s1/i2", 2, "t2", null, null, null, a1.uri)
        val c  = Conclusion("urn:graphmesh:agent:s1/final", "ans", a2.uri)

        val quads = recorder.agentSessionQuads(q, listOf(a1, a2), c)

        // Each analysis chains to its parent
        assertTrue(quads.any {
            it.subject == RdfTerm.Uri(a2.uri) &&
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM &&
            (it.objectTerm as? RdfTerm.Uri)?.value == a1.uri
        })
        assertTrue(quads.any {
            it.subject == RdfTerm.Uri(a1.uri) &&
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM &&
            (it.objectTerm as? RdfTerm.Uri)?.value == q.uri
        })
        assertTrue(quads.any {
            it.subject == RdfTerm.Uri(c.uri) &&
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM &&
            (it.objectTerm as? RdfTerm.Uri)?.value == a2.uri
        })
    }
}
