package com.agentwork.graphmesh.config

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
class ConfigChangeProducer(private val kafka: KafkaTemplate<String, GenericRecord>) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val schema: Schema = Schema.Parser().parse(
        javaClass.getResourceAsStream("/avro/config-changed.avsc")
    )

    companion object {
        const val TOPIC = "graphmesh.config.changed"
        const val SOURCE = "graphmesh/config-service"
        const val TYPE = "graphmesh.config.changed.v1"
    }

    fun send(event: ConfigChangedEvent) {
        val record = GenericData.Record(schema).apply {
            put("configId", event.configId)
            put("configType", event.configType.name)
            put("key", event.key)
            put("action", event.action.name)
            put("version", event.version)
        }
        val headers = CloudEventHeaders.build(
            source = SOURCE,
            type = TYPE,
            subject = event.configId
        )
        val kafkaHeaders = headers.map { (k, v) ->
            RecordHeader(k, v.toByteArray()) as org.apache.kafka.common.header.Header
        }
        val producerRecord = ProducerRecord<String, GenericRecord>(TOPIC, null, event.configId, record, kafkaHeaders)
        kafka.send(producerRecord).whenComplete { _, ex ->
            if (ex != null) logger.error("Failed to send config.changed event for {}", event.configId, ex)
        }
    }
}
