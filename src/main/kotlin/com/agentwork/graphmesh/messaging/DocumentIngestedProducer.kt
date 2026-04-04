package com.agentwork.graphmesh.messaging

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class DocumentIngestedProducer(private val kafka: KafkaTemplate<String, GenericRecord>) {

    private val schema: Schema = Schema.Parser().parse(
        javaClass.getResourceAsStream("/avro/document-ingested.avsc")
    )

    companion object {
        const val TOPIC = "graphmesh.document.ingested"
        const val SOURCE = "graphmesh/document-service"
        const val TYPE = "graphmesh.document.ingested.v1"
    }

    fun send(documentId: String, fileName: String, mimeType: String, sizeBytes: Long) {
        val record = GenericData.Record(schema).apply {
            put("documentId", documentId)
            put("fileName", fileName)
            put("mimeType", mimeType)
            put("sizeBytes", sizeBytes)
        }
        val headers = CloudEventHeaders.build(
            source = SOURCE,
            type = TYPE,
            subject = documentId
        )
        val kafkaHeaders = headers.map { (k, v) ->
            RecordHeader(k, v.toByteArray()) as org.apache.kafka.common.header.Header
        }
        val producerRecord = ProducerRecord<String, GenericRecord>(TOPIC, null, documentId, record, kafkaHeaders)
        kafka.send(producerRecord)
    }
}
