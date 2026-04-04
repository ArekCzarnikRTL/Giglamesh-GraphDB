package com.agentwork.graphmesh.extraction.chunker

import com.agentwork.graphmesh.messaging.CloudEventHeaders
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class ChunkCreatedProducer(private val kafka: KafkaTemplate<String, GenericRecord>) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val schema: Schema = Schema.Parser().parse(
        javaClass.getResourceAsStream("/avro/chunk-created.avsc")
    )

    companion object {
        const val TOPIC = "graphmesh.chunk.created"
        const val SOURCE = "graphmesh/chunker"
        const val TYPE = "graphmesh.chunk.created.v1"
    }

    fun send(event: ChunkCreatedEvent) {
        val record = GenericData.Record(schema).apply {
            put("chunkId", event.chunkId)
            put("documentId", event.documentId)
            put("collectionId", event.collectionId)
            put("chunkIndex", event.chunkIndex)
            put("charOffset", event.charOffset)
            put("charLength", event.charLength)
        }
        val headers = CloudEventHeaders.build(
            source = SOURCE,
            type = TYPE,
            subject = event.chunkId
        )
        val kafkaHeaders = headers.map { (k, v) ->
            RecordHeader(k, v.toByteArray()) as org.apache.kafka.common.header.Header
        }
        val producerRecord = ProducerRecord<String, GenericRecord>(TOPIC, null, event.chunkId, record, kafkaHeaders)
        kafka.send(producerRecord).whenComplete { _, ex ->
            if (ex != null) logger.error("Failed to send chunk.created event for {}", event.chunkId, ex)
        }
    }
}
