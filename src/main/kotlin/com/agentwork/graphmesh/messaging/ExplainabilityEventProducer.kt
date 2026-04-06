package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.provenance.query.AgentIterationRecord
import com.agentwork.graphmesh.provenance.query.SelectedEdgeExplanation
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class ExplainabilityEventProducer(
    private val kafka: KafkaTemplate<String, GenericRecord>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val schema: Schema = Schema.Parser().parse(
        javaClass.getResourceAsStream("/avro/query-explained.avsc")
    )
    private val edgeSchema: Schema = schema.getField("selectedEdges").schema()
        .types.first { it.type == Schema.Type.ARRAY }.elementType
    private val iterationSchema: Schema = schema.getField("iterations").schema()
        .types.first { it.type == Schema.Type.ARRAY }.elementType
    private val mechanismSchema: Schema = schema.getField("mechanism").schema()

    companion object {
        const val TOPIC = "graphmesh.query.explained"
        const val SOURCE = "graphmesh/explainability-service"
        const val TYPE = "graphmesh.query.explained.v1"
    }

    fun sendGraphRagEvent(
        sessionId: UUID,
        collectionId: String,
        queryText: String,
        retrievedEdgeCount: Int,
        selectedEdges: List<SelectedEdgeExplanation>,
        answerText: String,
    ) {
        val record = baseRecord(sessionId, collectionId, queryText, "GRAPH_RAG", answerText).apply {
            put("retrievedEdgeCount", retrievedEdgeCount)
            put("selectedEdges", selectedEdges.map { e ->
                GenericData.Record(edgeSchema).apply {
                    put("subject", e.subject)
                    put("predicate", e.predicate)
                    put("objectValue", e.objectValue)
                    put("reasoning", e.reasoning)
                }
            })
        }
        dispatch(sessionId, record)
    }

    fun sendDocRagEvent(
        sessionId: UUID,
        collectionId: String,
        queryText: String,
        retrievedChunkCount: Int,
        selectedChunkIds: List<String>,
        answerText: String,
    ) {
        val record = baseRecord(sessionId, collectionId, queryText, "DOC_RAG", answerText).apply {
            put("retrievedChunkCount", retrievedChunkCount)
            put("selectedChunkIds", selectedChunkIds)
        }
        dispatch(sessionId, record)
    }

    fun sendAgentEvent(
        sessionId: UUID,
        collectionId: String,
        queryText: String,
        iterations: List<AgentIterationRecord>,
        answerText: String,
    ) {
        val record = baseRecord(sessionId, collectionId, queryText, "AGENT", answerText).apply {
            put("iterations", iterations.map { it ->
                GenericData.Record(iterationSchema).apply {
                    put("thought", it.thought)
                    put("action", it.action)
                    put("arguments", it.arguments)
                    put("observation", it.observation)
                }
            })
        }
        dispatch(sessionId, record)
    }

    private fun baseRecord(
        sessionId: UUID,
        collectionId: String,
        queryText: String,
        mechanism: String,
        answerText: String,
    ): GenericData.Record = GenericData.Record(schema).apply {
        put("sessionId", sessionId.toString())
        put("collectionId", collectionId)
        put("mechanism", GenericData.EnumSymbol(mechanismSchema, mechanism))
        put("queryText", queryText)
        put("timestamp", Instant.now().toString())
        put("answerText", answerText)
    }

    private fun dispatch(sessionId: UUID, record: GenericRecord) {
        val headers = CloudEventHeaders.build(
            source = SOURCE,
            type = TYPE,
            subject = sessionId.toString(),
        )
        val kafkaHeaders = headers.map { (k, v) ->
            RecordHeader(k, v.toByteArray()) as org.apache.kafka.common.header.Header
        }
        val producerRecord = ProducerRecord<String, GenericRecord>(
            TOPIC, null, sessionId.toString(), record, kafkaHeaders,
        )
        kafka.send(producerRecord).whenComplete { _, ex ->
            if (ex != null) logger.error("Failed to send query.explained event for {}", sessionId, ex)
        }
    }
}
