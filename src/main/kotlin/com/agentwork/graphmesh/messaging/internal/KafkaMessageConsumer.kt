package com.agentwork.graphmesh.messaging.internal

import com.agentwork.graphmesh.messaging.Message
import com.agentwork.graphmesh.messaging.MessageConsumer
import tools.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.support.serializer.JsonDeserializer
import kotlin.reflect.KClass

class KafkaMessageConsumer<T : Any>(
    override val topic: String,
    override val groupId: String,
    private val messageType: KClass<T>,
    private val bootstrapServers: String,
    private val objectMapper: ObjectMapper
) : MessageConsumer<T> {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var container: ConcurrentMessageListenerContainer<String, T>? = null

    override fun subscribe(handler: suspend (Message<T>) -> Unit) {
        val consumerProps = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest"
        )

        val jsonDeserializer = JsonDeserializer(messageType.java).apply {
            setUseTypeHeaders(false)
            addTrustedPackages("*")
        }

        val consumerFactory = DefaultKafkaConsumerFactory(
            consumerProps,
            StringDeserializer(),
            jsonDeserializer
        )

        val listener = MessageListener<String, T> { record ->
            scope.launch {
                handler(toMessage(record))
            }
        }

        val containerProps = ContainerProperties(topic)

        container = ConcurrentMessageListenerContainer(consumerFactory, containerProps).apply {
            setupMessageListener(listener)
            start()
        }
    }

    override fun close() {
        container?.stop()
        scope.cancel()
    }

    private fun toMessage(record: ConsumerRecord<String, T>): Message<T> {
        val headers = record.headers().associate { header ->
            header.key() to String(header.value())
        }
        return Message(
            payload = record.value(),
            key = record.key(),
            headers = headers,
            topic = record.topic(),
            partition = record.partition(),
            offset = record.offset(),
            timestamp = record.timestamp()
        )
    }
}
