package com.agentwork.graphmesh.provenance.query

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class ExplainabilityUrisTest {

    private val sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `graphRag question uri`() {
        assertEquals(
            "urn:graphmesh:question:11111111-1111-1111-1111-111111111111",
            ExplainabilityUris.question(sessionId, QueryMechanism.GRAPH_RAG)
        )
    }

    @Test
    fun `docRag question uri uses docrag prefix`() {
        assertEquals(
            "urn:graphmesh:docrag:11111111-1111-1111-1111-111111111111",
            ExplainabilityUris.question(sessionId, QueryMechanism.DOC_RAG)
        )
    }

    @Test
    fun `agent question uri uses agent prefix`() {
        assertEquals(
            "urn:graphmesh:agent:11111111-1111-1111-1111-111111111111",
            ExplainabilityUris.question(sessionId, QueryMechanism.AGENT)
        )
    }

    @Test
    fun `exploration uri`() {
        assertEquals(
            "urn:graphmesh:prov:retrieval:11111111-1111-1111-1111-111111111111",
            ExplainabilityUris.exploration(sessionId)
        )
    }

    @Test
    fun `focus uri`() {
        assertEquals(
            "urn:graphmesh:prov:selection:11111111-1111-1111-1111-111111111111",
            ExplainabilityUris.focus(sessionId)
        )
    }

    @Test
    fun `synthesis uri`() {
        assertEquals(
            "urn:graphmesh:prov:answer:11111111-1111-1111-1111-111111111111",
            ExplainabilityUris.synthesis(sessionId)
        )
    }

    @Test
    fun `analysis uri includes iteration number`() {
        assertEquals(
            "urn:graphmesh:agent:11111111-1111-1111-1111-111111111111/i3",
            ExplainabilityUris.analysis(sessionId, 3)
        )
    }

    @Test
    fun `conclusion uri`() {
        assertEquals(
            "urn:graphmesh:agent:11111111-1111-1111-1111-111111111111/final",
            ExplainabilityUris.conclusion(sessionId)
        )
    }
}
