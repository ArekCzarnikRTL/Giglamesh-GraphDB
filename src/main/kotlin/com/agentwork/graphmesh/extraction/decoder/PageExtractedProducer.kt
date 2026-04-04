package com.agentwork.graphmesh.extraction.decoder

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
class PageExtractedProducer(private val kafka: KafkaTemplate<String, GenericRecord>) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val schema: Schema = Schema.Parser().parse(
        javaClass.getResourceAsStream("/avro/page-extracted.avsc")
    )

    companion object {
        const val TOPIC = "graphmesh.page.extracted"
        const val SOURCE = "graphmesh/pdf-decoder"
        const val TYPE = "graphmesh.page.extracted.v1"
    }

    fun send(event: PageExtractedEvent) {
        val record = GenericData.Record(schema).apply {
            put("documentId", event.documentId)
            put("parentDocumentId", event.parentDocumentId)
            put("collectionId", event.collectionId)
            put("pageNumber", event.pageNumber)
            put("charCount", event.charCount)
        }
        val headers = CloudEventHeaders.build(
            source = SOURCE,
            type = TYPE,
            subject = event.documentId
        )
        val kafkaHeaders = headers.map { (k, v) ->
            RecordHeader(k, v.toByteArray()) as org.apache.kafka.common.header.Header
        }
        val producerRecord = ProducerRecord<String, GenericRecord>(TOPIC, null, event.documentId, record, kafkaHeaders)
        kafka.send(producerRecord).whenComplete { _, ex ->
            if (ex != null) logger.error("Failed to send page.extracted event for {}", event.documentId, ex)
        }
    }
}
