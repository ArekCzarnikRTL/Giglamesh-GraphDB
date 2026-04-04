package com.agentwork.graphmesh.messaging.internal

import com.agentwork.graphmesh.messaging.KafkaTopicConfig
import com.agentwork.graphmesh.messaging.TopicRegistry
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.common.errors.TopicExistsException
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException

class DefaultTopicRegistry(
    private val adminClient: AdminClient
) : TopicRegistry {

    private val log = LoggerFactory.getLogger(javaClass)
    private val topics = CopyOnWriteArrayList<KafkaTopicConfig>()

    override fun register(config: KafkaTopicConfig) {
        topics.add(config)
    }

    override fun allTopics(): List<KafkaTopicConfig> = topics.toList()

    override fun ensureTopicsExist() {
        if (topics.isEmpty()) return

        val newTopics = topics.map { it.toNewTopic() }
        val results = adminClient.createTopics(newTopics)

        results.values().forEach { (topicName, future) ->
            try {
                future.get()
                log.info("Created topic: {}", topicName)
            } catch (e: ExecutionException) {
                if (e.cause is TopicExistsException) {
                    log.debug("Topic already exists: {}", topicName)
                } else {
                    throw e
                }
            }
        }
    }
}
