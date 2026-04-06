package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.provenance.query.Analysis
import com.agentwork.graphmesh.provenance.query.Conclusion
import com.agentwork.graphmesh.provenance.query.ExplainabilityRecorder
import com.agentwork.graphmesh.provenance.query.ExplainabilityUris
import com.agentwork.graphmesh.provenance.query.Exploration
import com.agentwork.graphmesh.provenance.query.Focus
import com.agentwork.graphmesh.provenance.query.Question
import com.agentwork.graphmesh.provenance.query.QueryMechanism
import com.agentwork.graphmesh.provenance.query.SelectedEdgeExplanation
import com.agentwork.graphmesh.provenance.query.Synthesis
import com.agentwork.graphmesh.rdf.QuadConverter
import com.agentwork.graphmesh.storage.QuadStore
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class ExplainabilityEventConsumer(
    private val recorder: ExplainabilityRecorder,
    private val quadStore: QuadStore,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.query.explained"], groupId = "graphmesh")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val v = record.value()
        val sessionId = UUID.fromString(v["sessionId"].toString())
        val collectionId = v["collectionId"].toString()
        val mechanism = QueryMechanism.valueOf(v["mechanism"].toString())
        val queryText = v["queryText"].toString()
        val timestamp = Instant.parse(v["timestamp"].toString())
        val answerText = v["answerText"].toString()

        val question = Question(
            uri = ExplainabilityUris.question(sessionId, mechanism),
            queryText = queryText,
            timestamp = timestamp,
            mechanism = mechanism,
        )

        val quads = when (mechanism) {
            QueryMechanism.GRAPH_RAG -> buildGraphRagQuads(sessionId, question, v, answerText)
            QueryMechanism.DOC_RAG   -> buildDocRagQuads(sessionId, question, v, answerText)
            QueryMechanism.AGENT     -> buildAgentQuads(sessionId, question, v, answerText)
        }

        quadStore.insertBatch(collectionId, quads.map { QuadConverter.toStoredQuad(it) })
        logger.info(
            "Persisted {} explainability quads for session {} ({})",
            quads.size, sessionId, mechanism
        )
    }

    private fun buildGraphRagQuads(
        sessionId: UUID, question: Question, v: GenericRecord, answerText: String,
    ): List<com.agentwork.graphmesh.rdf.Quad> {
        val edgeCount = v["retrievedEdgeCount"] as? Int ?: 0
        @Suppress("UNCHECKED_CAST")
        val selected = (v["selectedEdges"] as? List<GenericRecord>).orEmpty().map {
            SelectedEdgeExplanation(
                subject = it["subject"].toString(),
                predicate = it["predicate"].toString(),
                objectValue = it["objectValue"].toString(),
                reasoning = it["reasoning"].toString(),
            )
        }
        val exploration = Exploration(ExplainabilityUris.exploration(sessionId), edgeCount, question.uri)
        val focus = Focus(ExplainabilityUris.focus(sessionId), selected, exploration.uri)
        val synthesis = Synthesis(ExplainabilityUris.synthesis(sessionId), answerText, focus.uri)
        return recorder.graphRagSessionQuads(question, exploration, focus, synthesis)
    }

    private fun buildDocRagQuads(
        sessionId: UUID, question: Question, v: GenericRecord, answerText: String,
    ): List<com.agentwork.graphmesh.rdf.Quad> {
        val chunkCount = v["retrievedChunkCount"] as? Int ?: 0
        val exploration = Exploration(ExplainabilityUris.exploration(sessionId), chunkCount, question.uri)
        val synthesis = Synthesis(ExplainabilityUris.synthesis(sessionId), answerText, exploration.uri)
        return recorder.docRagSessionQuads(question, exploration, synthesis)
    }

    private fun buildAgentQuads(
        sessionId: UUID, question: Question, v: GenericRecord, answerText: String,
    ): List<com.agentwork.graphmesh.rdf.Quad> {
        @Suppress("UNCHECKED_CAST")
        val iters = (v["iterations"] as? List<GenericRecord>).orEmpty()
        val analyses = iters.mapIndexed { idx, rec ->
            val iterIndex = idx + 1
            @Suppress("UNCHECKED_CAST")
            val args = (rec["arguments"] as? Map<CharSequence, CharSequence>)
                ?.entries?.associate { it.key.toString() to it.value.toString() }
            Analysis(
                uri = ExplainabilityUris.analysis(sessionId, iterIndex),
                iterationIndex = iterIndex,
                thought = rec["thought"].toString(),
                action = (rec["action"] as? CharSequence)?.toString(),
                arguments = args,
                observation = (rec["observation"] as? CharSequence)?.toString(),
                parentUri = if (idx == 0) question.uri
                            else ExplainabilityUris.analysis(sessionId, iterIndex - 1),
            )
        }
        val parentForConclusion = analyses.lastOrNull()?.uri ?: question.uri
        val conclusion = Conclusion(
            uri = ExplainabilityUris.conclusion(sessionId),
            answerText = answerText,
            parentUri = parentForConclusion,
        )
        return recorder.agentSessionQuads(question, analyses, conclusion)
    }
}
