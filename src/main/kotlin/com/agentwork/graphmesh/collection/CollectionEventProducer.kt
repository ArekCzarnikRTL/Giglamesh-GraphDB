package com.agentwork.graphmesh.collection

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
class CollectionEventProducer(private val kafka: KafkaTemplate<String, GenericRecord>) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val schema: Schema = Schema.Parser().parse(
        javaClass.getResourceAsStream("/avro/collection-lifecycle.avsc")
    )

    companion object {
        const val TOPIC = "graphmesh.collection.lifecycle"
        const val SOURCE = "graphmesh/collection-service"
        const val TYPE = "graphmesh.collection.lifecycle.v1"
    }

    fun send(event: CollectionEvent) {
        val record = GenericData.Record(schema).apply {
            put("type", event.type.name)
            put("collectionId", event.collectionId)
            put("collectionName", event.collectionName)
        }
        val headers = CloudEventHeaders.build(
            source = SOURCE,
            type = TYPE,
            subject = event.collectionId
        )
        val kafkaHeaders = headers.map { (k, v) ->
            RecordHeader(k, v.toByteArray()) as org.apache.kafka.common.header.Header
        }
        val producerRecord = ProducerRecord<String, GenericRecord>(TOPIC, null, event.collectionId, record, kafkaHeaders)
        kafka.send(producerRecord).whenComplete { _, ex ->
            if (ex != null) logger.error("Failed to send collection lifecycle event for {}", event.collectionId, ex)
        }
    }
}
