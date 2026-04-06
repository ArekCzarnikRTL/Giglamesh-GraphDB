package com.agentwork.graphmesh.provenance.query

import java.util.UUID

object ExplainabilityUris {
    private const val BASE = "urn:graphmesh"

    fun question(sessionId: UUID, mechanism: QueryMechanism): String =
        when (mechanism) {
            QueryMechanism.GRAPH_RAG -> "$BASE:question:$sessionId"
            QueryMechanism.DOC_RAG   -> "$BASE:docrag:$sessionId"
            QueryMechanism.AGENT     -> "$BASE:agent:$sessionId"
        }

    fun exploration(sessionId: UUID): String = "$BASE:prov:retrieval:$sessionId"
    fun focus(sessionId: UUID): String       = "$BASE:prov:selection:$sessionId"
    fun synthesis(sessionId: UUID): String   = "$BASE:prov:answer:$sessionId"
    fun analysis(sessionId: UUID, iterationIndex: Int): String =
        "$BASE:agent:$sessionId/i$iterationIndex"
    fun conclusion(sessionId: UUID): String  = "$BASE:agent:$sessionId/final"
}
