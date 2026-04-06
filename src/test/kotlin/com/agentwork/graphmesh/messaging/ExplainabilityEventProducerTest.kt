package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.provenance.query.AgentIterationRecord
import com.agentwork.graphmesh.provenance.query.SelectedEdgeExplanation
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExplainabilityEventProducerTest {

    private val captured = mutableListOf<ProducerRecord<String, GenericRecord>>()

    private val fakeTemplate = object : KafkaTemplate<String, GenericRecord>(
        org.springframework.kafka.core.DefaultKafkaProducerFactory(emptyMap())
    ) {
        override fun send(record: ProducerRecord<String, GenericRecord>):
            CompletableFuture<org.springframework.kafka.support.SendResult<String, GenericRecord>> {
            captured.add(record)
            return CompletableFuture.completedFuture(null)
        }
    }

    private val producer = ExplainabilityEventProducer(fakeTemplate)
    private val sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `sendGraphRagEvent encodes mechanism queryText and selectedEdges`() {
        producer.sendGraphRagEvent(
            sessionId = sessionId,
            collectionId = "col1",
            queryText = "What is X?",
            retrievedEdgeCount = 5,
            selectedEdges = listOf(
                SelectedEdgeExplanation("urn:s","urn:p","urn:o","because")
            ),
            answerText = "ans",
        )

        assertEquals(1, captured.size)
        val rec = captured.first().value()
        assertEquals("11111111-1111-1111-1111-111111111111", rec["sessionId"].toString())
        assertEquals("col1", rec["collectionId"].toString())
        assertEquals("GRAPH_RAG", rec["mechanism"].toString())
        assertEquals("What is X?", rec["queryText"].toString())
        assertEquals(5, rec["retrievedEdgeCount"])
        assertEquals("ans", rec["answerText"].toString())

        @Suppress("UNCHECKED_CAST")
        val edges = rec["selectedEdges"] as List<GenericRecord>
        assertEquals(1, edges.size)
        assertEquals("urn:s", edges[0]["subject"].toString())
        assertEquals("because", edges[0]["reasoning"].toString())

        assertNull(rec["retrievedChunkCount"])
        assertNull(rec["iterations"])
    }

    @Test
    fun `sendDocRagEvent encodes chunk fields and leaves edges null`() {
        producer.sendDocRagEvent(
            sessionId = sessionId,
            collectionId = "col1",
            queryText = "doc query",
            retrievedChunkCount = 3,
            selectedChunkIds = listOf("c1", "c2"),
            answerText = "doc-ans",
        )

        assertEquals(1, captured.size)
        val rec = captured.first().value()
        assertEquals("DOC_RAG", rec["mechanism"].toString())
        assertEquals(3, rec["retrievedChunkCount"])

        @Suppress("UNCHECKED_CAST")
        val chunks = rec["selectedChunkIds"] as List<CharSequence>
        assertEquals(listOf("c1","c2"), chunks.map { it.toString() })
        assertNull(rec["retrievedEdgeCount"])
        assertNull(rec["selectedEdges"])
    }

    @Test
    fun `sendAgentEvent encodes iterations`() {
        producer.sendAgentEvent(
            sessionId = sessionId,
            collectionId = "col1",
            queryText = "agent question",
            iterations = listOf(
                AgentIterationRecord("t1","knowledge_query", mapOf("query" to "X"), "obs"),
                AgentIterationRecord("t2", null, null, null),
            ),
            answerText = "final",
        )

        assertEquals(1, captured.size)
        val rec = captured.first().value()
        assertEquals("AGENT", rec["mechanism"].toString())

        @Suppress("UNCHECKED_CAST")
        val iters = rec["iterations"] as List<GenericRecord>
        assertEquals(2, iters.size)
        assertEquals("t1", iters[0]["thought"].toString())
        assertEquals("knowledge_query", iters[0]["action"].toString())
        assertNotNull(iters[0]["arguments"])
        assertNull(iters[1]["action"])
    }

    @Test
    fun `producer attaches CloudEvent headers`() {
        producer.sendGraphRagEvent(sessionId, "col1", "q", 0, emptyList(), "a")

        val headers = captured.first().headers().toList().associate { it.key() to String(it.value()) }
        assertEquals("graphmesh.query.explained.v1", headers[CloudEventHeaders.TYPE])
        assertEquals("graphmesh/explainability-service", headers[CloudEventHeaders.SOURCE])
        assertEquals("11111111-1111-1111-1111-111111111111", headers[CloudEventHeaders.SUBJECT])
    }
}
