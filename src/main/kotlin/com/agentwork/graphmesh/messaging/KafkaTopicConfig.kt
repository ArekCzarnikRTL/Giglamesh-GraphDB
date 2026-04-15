package com.agentwork.graphmesh.messaging

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class KafkaTopicConfig {

    private fun topic(name: String): NewTopic =
        TopicBuilder.name(name)
            .partitions(PARTITIONS)
            .replicas(1)
            .build()

    @Bean fun documentIngestedTopic(): NewTopic = topic("graphmesh.document.ingested")
    @Bean fun queryExplainedTopic(): NewTopic = topic("graphmesh.query.explained")
    @Bean fun pageExtractedTopic(): NewTopic = topic("graphmesh.page.extracted")
    @Bean fun chunkCreatedTopic(): NewTopic = topic("graphmesh.chunk.created")
    @Bean fun collectionLifecycleTopic(): NewTopic = topic("graphmesh.collection.lifecycle")
    @Bean fun configChangedTopic(): NewTopic = topic("graphmesh.config.changed")

    companion object {
        const val PARTITIONS = 5
    }
}
