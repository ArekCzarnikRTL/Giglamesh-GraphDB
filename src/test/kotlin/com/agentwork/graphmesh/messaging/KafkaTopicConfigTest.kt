package com.agentwork.graphmesh.messaging

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

class KafkaTopicConfigTest {

    @Test
    fun `valid topic name is accepted`() {
        val config = KafkaTopicConfig(name = "graphmesh.document.uploaded")
        assertEquals("graphmesh.document.uploaded", config.name)
        assertEquals(3, config.partitions)
        assertEquals(1, config.replicationFactor)
    }

    @Test
    fun `topic name without graphmesh prefix is rejected`() {
        assertThrows<IllegalArgumentException> {
            KafkaTopicConfig(name = "invalid.topic.name")
        }
    }

    @Test
    fun `toNewTopic creates correct NewTopic`() {
        val config = KafkaTopicConfig(
            name = "graphmesh.config.push",
            partitions = 6,
            replicationFactor = 3,
            configs = mapOf("retention.ms" to "86400000")
        )
        val newTopic = config.toNewTopic()
        assertEquals("graphmesh.config.push", newTopic.name())
        assertEquals(6, newTopic.numPartitions())
        assertEquals(3, newTopic.replicationFactor())
        assertEquals("86400000", newTopic.configs()["retention.ms"])
    }
}
