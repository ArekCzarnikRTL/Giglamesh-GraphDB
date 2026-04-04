package com.agentwork.graphmesh.config

import com.agentwork.graphmesh.messaging.CloudEventHeaders
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class ConfigChangeConsumer(
    private val eventPublisher: ApplicationEventPublisher
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.config.changed"], groupId = "graphmesh")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val ceHeaders = CloudEventHeaders.extract(record.headers())
        val value = record.value()

        val event = ConfigChangedEvent(
            configId = value["configId"].toString(),
            configType = ConfigType.valueOf(value["configType"].toString()),
            key = value["key"].toString(),
            action = ConfigAction.valueOf(value["action"].toString()),
            version = value["version"] as Int
        )

        logger.info(
            "Config change received: configId={}, type={}, action={}, eventType={}",
            event.configId, event.configType, event.action, ceHeaders[CloudEventHeaders.TYPE]
        )

        eventPublisher.publishEvent(event)
    }
}
