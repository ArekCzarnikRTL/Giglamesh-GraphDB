package com.agentwork.graphmesh.messaging.internal

import com.agentwork.graphmesh.messaging.MessageHeaders
import com.agentwork.graphmesh.messaging.MessageProducer
import tools.jackson.databind.ObjectMapper
import kotlinx.coroutines.future.await
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.kafka.core.KafkaTemplate
import java.time.Instant

class KafkaMessageProducer<T : Any>(
    override val topic: String,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val objectMapper: ObjectMapper
) : MessageProducer<T> {

    override suspend fun send(message: T, headers: Map<String, String>) {
        sendWithKey(key = "", message = message, headers = headers)
    }

    override suspend fun sendWithKey(key: String, message: T, headers: Map<String, String>) {
        val record = ProducerRecord<String, Any>(topic, null, key.ifEmpty { null }, message)
        addHeaders(record, message, headers)
        kafkaTemplate.send(record).await()
    }

    override fun close() {
        kafkaTemplate.flush()
    }

    private fun addHeaders(record: ProducerRecord<String, Any>, message: T, customHeaders: Map<String, String>) {
        record.headers().add(RecordHeader(MessageHeaders.TIMESTAMP, Instant.now().toString().toByteArray()))
        record.headers().add(RecordHeader(MessageHeaders.MESSAGE_TYPE, message::class.simpleName.orEmpty().toByteArray()))
        customHeaders.forEach { (k, v) ->
            record.headers().add(RecordHeader(k, v.toByteArray()))
        }
    }
}
