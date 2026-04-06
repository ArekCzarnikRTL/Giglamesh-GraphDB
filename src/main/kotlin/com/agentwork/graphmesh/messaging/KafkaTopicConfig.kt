package com.agentwork.graphmesh.messaging

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class KafkaTopicConfig {

    @Bean
    fun documentIngestedTopic(): NewTopic =
        TopicBuilder.name("graphmesh.document.ingested")
            .partitions(3)
            .replicas(1)
            .build()

    @Bean
    fun queryExplainedTopic(): NewTopic =
        TopicBuilder.name("graphmesh.query.explained")
            .partitions(3)
            .replicas(1)
            .build()
}
