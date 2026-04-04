package com.agentwork.graphmesh.messaging

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
abstract class AbstractKafkaIntegrationTest {

    companion object {
        @JvmStatic
        val kafkaContainer: KafkaContainer = KafkaContainer("apache/kafka-native:4.0.0").apply {
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun kafkaProperties(registry: DynamicPropertyRegistry) {
            registry.add("graphmesh.messaging.kafka.bootstrap-servers") { kafkaContainer.bootstrapServers }
            registry.add("spring.kafka.bootstrap-servers") { kafkaContainer.bootstrapServers }
        }
    }
}
