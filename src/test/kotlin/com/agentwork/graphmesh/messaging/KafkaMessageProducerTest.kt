package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.messaging.internal.KafkaMessageProducer
import tools.jackson.module.kotlin.jsonMapper
import kotlinx.coroutines.test.runTest
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.serializer.JsonSerializer
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

data class TestEvent(val id: String, val value: Int)

class KafkaMessageProducerTest : AbstractKafkaIntegrationTest() {

    private val objectMapper = jsonMapper {}
    private lateinit var kafkaTemplate: KafkaTemplate<String, Any>
    private lateinit var producer: KafkaMessageProducer<TestEvent>
    private lateinit var verificationConsumer: KafkaConsumer<String, String>
    private val topicName = "graphmesh.test.producer-${System.currentTimeMillis()}"

    @BeforeEach
    fun setUp() {
        val producerProps = mapOf(
            org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers,
            org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to org.apache.kafka.common.serialization.StringSerializer::class.java,
            org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
        )
        val producerFactory = DefaultKafkaProducerFactory<String, Any>(producerProps)
        kafkaTemplate = KafkaTemplate(producerFactory)

        producer = KafkaMessageProducer(
            topic = topicName,
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper
        )

        val consumerProps = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "test-verification-${System.currentTimeMillis()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java
        )
        verificationConsumer = KafkaConsumer(consumerProps)
        verificationConsumer.subscribe(listOf(topicName))
    }

    @AfterEach
    fun tearDown() {
        producer.close()
        verificationConsumer.close()
    }

    @Test
    fun `send publishes message to topic`() = runTest {
        producer.send(TestEvent(id = "evt-1", value = 42))

        val records = pollUntilRecords(1)
        assertEquals(1, records.size)
        val parsed = objectMapper.readTree(records[0].value())
        assertEquals("evt-1", parsed["id"].asText())
        assertEquals(42, parsed["value"].asInt())
    }

    @Test
    fun `sendWithKey sets the record key`() = runTest {
        producer.sendWithKey("my-key", TestEvent(id = "evt-2", value = 99))

        val records = pollUntilRecords(1)
        assertEquals(1, records.size)
        assertEquals("my-key", records[0].key())
    }

    @Test
    fun `send propagates custom headers`() = runTest {
        producer.send(
            TestEvent(id = "evt-3", value = 1),
            headers = mapOf("X-Custom" to "hello")
        )

        val records = pollUntilRecords(1)
        val header = records[0].headers().lastHeader("X-Custom")
        assertNotNull(header)
        assertEquals("hello", String(header.value()))
    }

    @Test
    fun `send sets automatic headers`() = runTest {
        producer.send(TestEvent(id = "evt-4", value = 0))

        val records = pollUntilRecords(1)
        val timestampHeader = records[0].headers().lastHeader(MessageHeaders.TIMESTAMP)
        val typeHeader = records[0].headers().lastHeader(MessageHeaders.MESSAGE_TYPE)
        assertNotNull(timestampHeader)
        assertNotNull(typeHeader)
        assertTrue(String(typeHeader.value()).contains("TestEvent"))
    }

    private fun pollUntilRecords(
        expected: Int,
        timeout: Duration = Duration.ofSeconds(10)
    ): List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        val collected = mutableListOf<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>()
        while (collected.size < expected && System.currentTimeMillis() < deadline) {
            val batch = verificationConsumer.poll(Duration.ofMillis(200))
            collected.addAll(batch)
        }
        return collected
    }
}
