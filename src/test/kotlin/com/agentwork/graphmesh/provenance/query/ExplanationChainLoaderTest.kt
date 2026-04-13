package com.agentwork.graphmesh.provenance.query

import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.QuadConverter
import com.agentwork.graphmesh.storage.GraphMetadataView
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class InMemoryQuadStore : QuadStore {
    private val store = mutableMapOf<String, MutableList<StoredQuad>>()

    override fun insert(collection: String, quad: StoredQuad) {
        store.getOrPut(collection) { mutableListOf() }.add(quad)
    }
    override fun insertBatch(collection: String, quads: List<StoredQuad>) {
        store.getOrPut(collection) { mutableListOf() }.addAll(quads)
    }
    override fun delete(collection: String, quad: StoredQuad) {
        store[collection]?.remove(quad)
    }
    override fun deleteCollection(collection: String) { store.remove(collection) }
    override fun query(collection: String, query: QuadQuery, limit: Int?): List<StoredQuad> {
        val filtered = (store[collection] ?: emptyList()).filter {
            (query.subject == null || it.subject == query.subject) &&
            (query.predicate == null || it.predicate == query.predicate) &&
            (query.objectValue == null || it.objectValue == query.objectValue) &&
            (query.dataset == null || it.dataset == query.dataset)
        }
        return if (limit != null) filtered.take(limit) else filtered
    }
    override fun findSubjects(collection: String, substringMatch: String, limit: Int): List<String> = emptyList()
    override fun aggregateMetadata(collection: String): GraphMetadataView =
        GraphMetadataView(emptyList(), emptyList(), emptyList())
    override fun scrollAll(collection: String): List<StoredQuad> =
        (store[collection] ?: emptyList()).toList()
    override fun isEmpty(collection: String): Boolean =
        store[collection]?.isEmpty() ?: true
    override fun deleteByDataset(collection: String, dataset: String): Long = 0L
    override fun stats(collection: String): com.agentwork.graphmesh.storage.QuadStoreStats =
        com.agentwork.graphmesh.storage.QuadStoreStats(0L, 0L, 0L, emptyList())
}

class ExplanationChainLoaderTest {

    private val recorder = ExplainabilityRecorder()
    private val store = InMemoryQuadStore()
    private val loader = ExplanationChainLoader(store)
    private val collection = "col1"

    private fun persist(quads: List<com.agentwork.graphmesh.rdf.Quad>) {
        store.insertBatch(collection, quads.map { QuadConverter.toStoredQuad(it) })
    }

    @Test
    fun `load graphRag chain reconstructs question exploration focus synthesis`() {
        val sessionId = UUID.randomUUID()
        val q = Question(
            uri = ExplainabilityUris.question(sessionId, QueryMechanism.GRAPH_RAG),
            queryText = "What is X?",
            timestamp = Instant.parse("2026-04-06T12:00:00Z"),
            mechanism = QueryMechanism.GRAPH_RAG,
        )
        val e = Exploration(ExplainabilityUris.exploration(sessionId), 5, q.uri)
        val f = Focus(
            uri = ExplainabilityUris.focus(sessionId),
            selectedEdges = listOf(SelectedEdgeExplanation("urn:s","urn:p","urn:o","because")),
            explorationUri = e.uri,
        )
        val s = Synthesis(ExplainabilityUris.synthesis(sessionId), "answer", f.uri)
        persist(recorder.graphRagSessionQuads(q, e, f, s))

        val chain = loader.load(collection, q.uri)
        assertNotNull(chain)
        assertEquals(QueryMechanism.GRAPH_RAG, chain.mechanism)
        assertEquals("What is X?", chain.question.queryText)
        assertEquals(5, chain.exploration?.edgeCount)
        assertEquals(1, chain.focus?.selectedEdges?.size)
        assertEquals("because", chain.focus?.selectedEdges?.first()?.reasoning)
        assertEquals("answer", chain.synthesis?.answerText)
        assertNull(chain.analyses)
        assertNull(chain.conclusion)
    }

    @Test
    fun `load docRag chain has no focus`() {
        val sessionId = UUID.randomUUID()
        val q = Question(
            uri = ExplainabilityUris.question(sessionId, QueryMechanism.DOC_RAG),
            queryText = "What does the doc say?",
            timestamp = Instant.parse("2026-04-06T12:00:00Z"),
            mechanism = QueryMechanism.DOC_RAG,
        )
        val e = Exploration(ExplainabilityUris.exploration(sessionId), 3, q.uri)
        val s = Synthesis(ExplainabilityUris.synthesis(sessionId), "doc-answer", e.uri)
        persist(recorder.docRagSessionQuads(q, e, s))

        val chain = loader.load(collection, q.uri)
        assertNotNull(chain)
        assertEquals(QueryMechanism.DOC_RAG, chain.mechanism)
        assertNull(chain.focus)
        assertEquals("doc-answer", chain.synthesis?.answerText)
    }

    @Test
    fun `load agent chain reconstructs analyses in iteration order`() {
        val sessionId = UUID.randomUUID()
        val q = Question(
            uri = ExplainabilityUris.question(sessionId, QueryMechanism.AGENT),
            queryText = "agent question",
            timestamp = Instant.parse("2026-04-06T12:00:00Z"),
            mechanism = QueryMechanism.AGENT,
        )
        val a1 = Analysis(
            uri = ExplainabilityUris.analysis(sessionId, 1),
            iterationIndex = 1, thought = "t1", action = "knowledge_query",
            arguments = mapOf("query" to "X"), observation = "obs1",
            parentUri = q.uri,
        )
        val a2 = Analysis(
            uri = ExplainabilityUris.analysis(sessionId, 2),
            iterationIndex = 2, thought = "t2", action = null,
            arguments = null, observation = null, parentUri = a1.uri,
        )
        val c = Conclusion(ExplainabilityUris.conclusion(sessionId), "final", a2.uri)
        persist(recorder.agentSessionQuads(q, listOf(a1, a2), c))

        val chain = loader.load(collection, q.uri)
        assertNotNull(chain)
        assertEquals(QueryMechanism.AGENT, chain.mechanism)
        assertEquals(2, chain.analyses?.size)
        assertEquals(1, chain.analyses?.get(0)?.iterationIndex)
        assertEquals(2, chain.analyses?.get(1)?.iterationIndex)
        assertEquals("t1", chain.analyses?.get(0)?.thought)
        assertEquals("knowledge_query", chain.analyses?.get(0)?.action)
        assertEquals("final", chain.conclusion?.answerText)
        assertNull(chain.synthesis)
    }

    @Test
    fun `load returns null when session uri unknown`() {
        assertNull(loader.load(collection, "urn:graphmesh:question:does-not-exist"))
    }

    @Test
    fun `load graphRag chain only returns edges from this session even when multiple sessions exist`() {
        val sessionA = UUID.randomUUID()
        val sessionB = UUID.randomUUID()

        val qa = Question(
            uri = ExplainabilityUris.question(sessionA, QueryMechanism.GRAPH_RAG),
            queryText = "qa",
            timestamp = Instant.parse("2026-04-06T12:00:00Z"),
            mechanism = QueryMechanism.GRAPH_RAG,
        )
        val ea = Exploration(ExplainabilityUris.exploration(sessionA), 1, qa.uri)
        val fa = Focus(
            uri = ExplainabilityUris.focus(sessionA),
            selectedEdges = listOf(SelectedEdgeExplanation("urn:a", "urn:p", "urn:b", "reason A")),
            explorationUri = ea.uri,
        )
        val sa = Synthesis(ExplainabilityUris.synthesis(sessionA), "answer A", fa.uri)

        val qb = Question(
            uri = ExplainabilityUris.question(sessionB, QueryMechanism.GRAPH_RAG),
            queryText = "qb",
            timestamp = Instant.parse("2026-04-06T12:01:00Z"),
            mechanism = QueryMechanism.GRAPH_RAG,
        )
        val eb = Exploration(ExplainabilityUris.exploration(sessionB), 1, qb.uri)
        val fb = Focus(
            uri = ExplainabilityUris.focus(sessionB),
            selectedEdges = listOf(SelectedEdgeExplanation("urn:c", "urn:p", "urn:d", "reason B")),
            explorationUri = eb.uri,
        )
        val sb = Synthesis(ExplainabilityUris.synthesis(sessionB), "answer B", fb.uri)

        persist(recorder.graphRagSessionQuads(qa, ea, fa, sa))
        persist(recorder.graphRagSessionQuads(qb, eb, fb, sb))

        val chainA = loader.load(collection, qa.uri)
        assertNotNull(chainA)
        assertEquals(1, chainA.focus?.selectedEdges?.size)
        assertEquals("reason A", chainA.focus?.selectedEdges?.first()?.reasoning)
        assertEquals("urn:a", chainA.focus?.selectedEdges?.first()?.subject)

        val chainB = loader.load(collection, qb.uri)
        assertNotNull(chainB)
        assertEquals(1, chainB.focus?.selectedEdges?.size)
        assertEquals("reason B", chainB.focus?.selectedEdges?.first()?.reasoning)
        assertEquals("urn:c", chainB.focus?.selectedEdges?.first()?.subject)
    }

    @Test
    fun `listSessions filters by mechanism`() {
        val sg = UUID.randomUUID()
        val sd = UUID.randomUUID()
        val sa = UUID.randomUUID()

        val qg = Question(ExplainabilityUris.question(sg, QueryMechanism.GRAPH_RAG),
            "g", Instant.parse("2026-04-06T12:00:00Z"), QueryMechanism.GRAPH_RAG)
        val qd = Question(ExplainabilityUris.question(sd, QueryMechanism.DOC_RAG),
            "d", Instant.parse("2026-04-06T12:01:00Z"), QueryMechanism.DOC_RAG)
        val qa = Question(ExplainabilityUris.question(sa, QueryMechanism.AGENT),
            "a", Instant.parse("2026-04-06T12:02:00Z"), QueryMechanism.AGENT)

        persist(recorder.questionQuads(qg))
        persist(recorder.questionQuads(qd))
        persist(recorder.questionQuads(qa))

        val all = loader.listSessions(collection, mechanism = null, limit = 10)
        assertEquals(3, all.size)

        val onlyAgent = loader.listSessions(collection, mechanism = QueryMechanism.AGENT, limit = 10)
        assertEquals(1, onlyAgent.size)
        assertEquals("a", onlyAgent.first().queryText)
    }
}
