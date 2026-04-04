package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.messaging.internal.KafkaMessageConsumer
import tools.jackson.module.kotlin.jsonMapper
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.kafka.support.serializer.JsonSerializer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

data class ConsumerTestEvent(val id: String, val value: Int)

class KafkaMessageConsumerTest : AbstractKafkaIntegrationTest() {

    private val objectMapper = jsonMapper {}
    private lateinit var rawProducer: KafkaProducer<String, Any>
    private lateinit var consumer: KafkaMessageConsumer<ConsumerTestEvent>
    private val topicName = "graphmesh.test.consumer-${System.currentTimeMillis()}"

    @BeforeEach
    fun setUp() {
        val producerProps = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
        )
        rawProducer = KafkaProducer(producerProps)

        consumer = KafkaMessageConsumer(
            topic = topicName,
            groupId = "test-consumer-${System.currentTimeMillis()}",
            messageType = ConsumerTestEvent::class,
            bootstrapServers = kafkaContainer.bootstrapServers,
            objectMapper = objectMapper
        )
    }

    @AfterEach
    fun tearDown() {
        consumer.close()
        rawProducer.close()
    }

    @Test
    fun `subscribe receives and deserializes messages`() {
        val received = CopyOnWriteArrayList<Message<ConsumerTestEvent>>()
        val latch = CountDownLatch(1)

        consumer.subscribe { msg ->
            received.add(msg)
            latch.countDown()
        }

        rawProducer.send(ProducerRecord(topicName, "key-1", ConsumerTestEvent("evt-1", 42) as Any)).get()

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out waiting for message")
        assertEquals(1, received.size)
        assertEquals("evt-1", received[0].payload.id)
        assertEquals(42, received[0].payload.value)
        assertEquals("key-1", received[0].key)
        assertEquals(topicName, received[0].topic)
    }

    @Test
    fun `subscribe receives multiple messages`() {
        val received = CopyOnWriteArrayList<Message<ConsumerTestEvent>>()
        val latch = CountDownLatch(3)

        consumer.subscribe { msg ->
            received.add(msg)
            latch.countDown()
        }

        repeat(3) { i ->
            rawProducer.send(ProducerRecord(topicName, ConsumerTestEvent("evt-$i", i) as Any)).get()
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out waiting for messages")
        assertEquals(3, received.size)
    }

    @Test
    fun `subscribe propagates headers`() {
        val received = CopyOnWriteArrayList<Message<ConsumerTestEvent>>()
        val latch = CountDownLatch(1)

        consumer.subscribe { msg ->
            received.add(msg)
            latch.countDown()
        }

        val record = ProducerRecord(topicName, null as String?, ConsumerTestEvent("evt-h", 0) as Any)
        record.headers().add(RecordHeader("X-Custom", "test-value".toByteArray()))
        rawProducer.send(record).get()

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out waiting for message")
        assertEquals("test-value", received[0].headers["X-Custom"])
    }
}
