package com.agentwork.graphmesh.messaging

import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration," +
                "org.springframework.boot.autoconfigure.cassandra.CassandraAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.cassandra.CassandraDataAutoConfiguration"
    ]
)
@ActiveProfiles("test")
class DocumentIngestedIntegrationTest {

    @Autowired
    lateinit var producer: DocumentIngestedProducer

    @Test
    fun `producer sends message that can be consumed with correct Avro payload`() {
        val documentId = UUID.randomUUID().toString()
        val fileName = "test-document.pdf"
        val mimeType = "application/pdf"
        val sizeBytes = 1024L

        producer.send(documentId, fileName, mimeType, sizeBytes)

        val consumerProps = mapOf(
            "bootstrap.servers" to "localhost:9092",
            "group.id" to "test-${UUID.randomUUID()}",
            "auto.offset.reset" to "earliest",
            "schema.registry.url" to "http://localhost:8081",
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "io.confluent.kafka.serializers.KafkaAvroDeserializer"
        )
        val consumerFactory = org.springframework.kafka.core.DefaultKafkaConsumerFactory<String, GenericRecord>(consumerProps)
        val consumer = consumerFactory.createConsumer()
        consumer.subscribe(listOf(DocumentIngestedProducer.TOPIC))

        val records = mutableListOf<ConsumerRecord<String, GenericRecord>>()
        val deadline = Instant.now().plusSeconds(30)
        while (records.isEmpty() && Instant.now().isBefore(deadline)) {
            val polled = consumer.poll(Duration.ofSeconds(1))
            polled.forEach { records.add(it) }
        }
        consumer.close()

        assertTrue(records.isNotEmpty(), "Expected at least one message")
        val record = records.first()

        assertEquals(documentId, record.value()["documentId"].toString())
        assertEquals(fileName, record.value()["fileName"].toString())
        assertEquals(mimeType, record.value()["mimeType"].toString())
        assertEquals(sizeBytes, record.value()["sizeBytes"] as Long)
        assertEquals(documentId, record.key())
    }

    @Test
    fun `producer sends correct CloudEvent headers`() {
        val documentId = UUID.randomUUID().toString()

        producer.send(documentId, "test.pdf", "application/pdf", 512L)

        val consumerProps = mapOf(
            "bootstrap.servers" to "localhost:9092",
            "group.id" to "test-headers-${UUID.randomUUID()}",
            "auto.offset.reset" to "earliest",
            "schema.registry.url" to "http://localhost:8081",
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "io.confluent.kafka.serializers.KafkaAvroDeserializer"
        )
        val consumerFactory = org.springframework.kafka.core.DefaultKafkaConsumerFactory<String, GenericRecord>(consumerProps)
        val consumer = consumerFactory.createConsumer()
        consumer.subscribe(listOf(DocumentIngestedProducer.TOPIC))

        val records = mutableListOf<ConsumerRecord<String, GenericRecord>>()
        val deadline = Instant.now().plusSeconds(30)
        while (records.isEmpty() && Instant.now().isBefore(deadline)) {
            val polled = consumer.poll(Duration.ofSeconds(1))
            polled.forEach { records.add(it) }
        }
        consumer.close()

        assertTrue(records.isNotEmpty(), "Expected at least one message")
        val ceHeaders = CloudEventHeaders.extract(records.first().headers())

        assertNotNull(ceHeaders[CloudEventHeaders.ID])
        assertEquals(DocumentIngestedProducer.SOURCE, ceHeaders[CloudEventHeaders.SOURCE])
        assertEquals("1.0", ceHeaders[CloudEventHeaders.SPEC_VERSION])
        assertEquals(DocumentIngestedProducer.TYPE, ceHeaders[CloudEventHeaders.TYPE])
        assertNotNull(ceHeaders[CloudEventHeaders.TIME])
        assertNotNull(ceHeaders[CloudEventHeaders.TRACEPARENT])
        assertEquals(documentId, ceHeaders[CloudEventHeaders.SUBJECT])
        assertEquals("application/avro", ceHeaders[CloudEventHeaders.CONTENT_TYPE])
    }
}
