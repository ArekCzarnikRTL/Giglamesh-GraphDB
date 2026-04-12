package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.provenance.query.ExplainabilityNamespaces
import com.agentwork.graphmesh.provenance.query.ExplainabilityRecorder
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.storage.GraphMetadataView
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CapturingQuadStore : QuadStore {
    val byCollection = mutableMapOf<String, MutableList<StoredQuad>>()
    override fun insert(collection: String, quad: StoredQuad) {
        byCollection.getOrPut(collection) { mutableListOf() }.add(quad)
    }
    override fun insertBatch(collection: String, quads: List<StoredQuad>) {
        byCollection.getOrPut(collection) { mutableListOf() }.addAll(quads)
    }
    override fun delete(collection: String, quad: StoredQuad) {}
    override fun deleteCollection(collection: String) {}
    override fun query(collection: String, query: QuadQuery, limit: Int?): List<StoredQuad> = emptyList()
    override fun findSubjects(collection: String, substringMatch: String, limit: Int): List<String> = emptyList()
    override fun aggregateMetadata(collection: String): GraphMetadataView =
        GraphMetadataView(emptyList(), emptyList(), emptyList())
    override fun scrollAll(collection: String): List<StoredQuad> =
        byCollection[collection]?.toList() ?: emptyList()
    override fun isEmpty(collection: String): Boolean =
        byCollection[collection]?.isEmpty() ?: true
}

class ExplainabilityEventConsumerTest {

    private val store = CapturingQuadStore()
    private val recorder = ExplainabilityRecorder()
    private val consumer = ExplainabilityEventConsumer(recorder, store)

    private val schema = org.apache.avro.Schema.Parser().parse(
        javaClass.getResourceAsStream("/avro/query-explained.avsc")
    )
    private val edgeSchema = schema.getField("selectedEdges").schema()
        .types.first { it.type == org.apache.avro.Schema.Type.ARRAY }.elementType
    private val iterSchema = schema.getField("iterations").schema()
        .types.first { it.type == org.apache.avro.Schema.Type.ARRAY }.elementType
    private val mechSchema = schema.getField("mechanism").schema()

    private fun consumerRecord(value: GenericRecord) =
        ConsumerRecord("graphmesh.query.explained", 0, 0L, "key", value)

    @Test
    fun `consumes graphRag event and persists quads in retrieval graph`() {
        val sessionId = UUID.randomUUID()
        val record = encodeGraphRag(sessionId)

        consumer.handle(consumerRecord(record))

        val quads = store.byCollection["col1"] ?: error("nothing persisted")
        assertTrue(quads.isNotEmpty())
        assertTrue(quads.all { it.dataset == NamedGraph.RETRIEVAL })
        assertTrue(quads.any {
            it.predicate == ExplainabilityNamespaces.TG_QUERY_TEXT && it.objectValue == "What is X?"
        })
        assertTrue(quads.any {
            it.predicate == ExplainabilityNamespaces.TG_REASONING && it.objectValue == "because"
        })
    }

    @Test
    fun `consumes docRag event without focus quads`() {
        val sessionId = UUID.randomUUID()
        val record = encodeDocRag(sessionId)

        consumer.handle(consumerRecord(record))

        val quads = store.byCollection["col1"]!!
        assertTrue(quads.none {
            it.predicate == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" &&
            it.objectValue == ExplainabilityNamespaces.TG_FOCUS
        })
        assertTrue(quads.any {
            it.predicate == ExplainabilityNamespaces.TG_ANSWER_TEXT && it.objectValue == "doc-ans"
        })
    }

    @Test
    fun `consumes agent event with iterations`() {
        val sessionId = UUID.randomUUID()
        val record = encodeAgent(sessionId)

        consumer.handle(consumerRecord(record))

        val quads = store.byCollection["col1"]!!
        val analysisCount = quads.count {
            it.predicate == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" &&
            it.objectValue == ExplainabilityNamespaces.TG_ANALYSIS
        }
        assertEquals(2, analysisCount)
        assertTrue(quads.any {
            it.predicate == ExplainabilityNamespaces.TG_THOUGHT && it.objectValue == "t1"
        })
    }

    // --- Avro encoding helpers ---

    private fun encodeGraphRag(sessionId: UUID): GenericRecord =
        org.apache.avro.generic.GenericData.Record(schema).apply {
            put("sessionId", sessionId.toString())
            put("collectionId", "col1")
            put("mechanism", org.apache.avro.generic.GenericData.EnumSymbol(mechSchema, "GRAPH_RAG"))
            put("queryText", "What is X?")
            put("timestamp", "2026-04-06T12:00:00Z")
            put("answerText", "ans")
            put("retrievedEdgeCount", 5)
            put("selectedEdges", listOf(
                org.apache.avro.generic.GenericData.Record(edgeSchema).apply {
                    put("subject", "urn:s")
                    put("predicate", "urn:p")
                    put("objectValue", "urn:o")
                    put("reasoning", "because")
                }
            ))
        }

    private fun encodeDocRag(sessionId: UUID): GenericRecord =
        org.apache.avro.generic.GenericData.Record(schema).apply {
            put("sessionId", sessionId.toString())
            put("collectionId", "col1")
            put("mechanism", org.apache.avro.generic.GenericData.EnumSymbol(mechSchema, "DOC_RAG"))
            put("queryText", "doc question")
            put("timestamp", "2026-04-06T12:00:00Z")
            put("answerText", "doc-ans")
            put("retrievedChunkCount", 3)
            put("selectedChunkIds", listOf("c1","c2","c3"))
        }

    private fun encodeAgent(sessionId: UUID): GenericRecord =
        org.apache.avro.generic.GenericData.Record(schema).apply {
            put("sessionId", sessionId.toString())
            put("collectionId", "col1")
            put("mechanism", org.apache.avro.generic.GenericData.EnumSymbol(mechSchema, "AGENT"))
            put("queryText", "agent q")
            put("timestamp", "2026-04-06T12:00:00Z")
            put("answerText", "final")
            put("iterations", listOf(
                org.apache.avro.generic.GenericData.Record(iterSchema).apply {
                    put("thought", "t1")
                    put("action", "knowledge_query")
                    put("arguments", mapOf("query" to "X"))
                    put("observation", "obs1")
                },
                org.apache.avro.generic.GenericData.Record(iterSchema).apply {
                    put("thought", "t2")
                    put("action", null)
                    put("arguments", null)
                    put("observation", null)
                }
            ))
        }
}
