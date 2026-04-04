package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.messaging.internal.DefaultTopicRegistry
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultTopicRegistryTest : AbstractKafkaIntegrationTest() {

    private lateinit var adminClient: AdminClient
    private lateinit var registry: DefaultTopicRegistry

    @BeforeEach
    fun setUp() {
        adminClient = AdminClient.create(
            mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers)
        )
        registry = DefaultTopicRegistry(adminClient)
    }

    @Test
    fun `register adds topic to registry`() {
        val config = KafkaTopicConfig(name = "graphmesh.test.register")
        registry.register(config)
        assertTrue(registry.allTopics().contains(config))
    }

    @Test
    fun `ensureTopicsExist creates topics on broker`() {
        val topicName = "graphmesh.test.ensure-${System.currentTimeMillis()}"
        val config = KafkaTopicConfig(name = topicName, partitions = 2, replicationFactor = 1)
        registry.register(config)

        registry.ensureTopicsExist()

        val existingTopics = adminClient.listTopics().names().get()
        assertTrue(existingTopics.contains(topicName))
    }

    @Test
    fun `ensureTopicsExist is idempotent`() {
        val topicName = "graphmesh.test.idempotent-${System.currentTimeMillis()}"
        val config = KafkaTopicConfig(name = topicName, partitions = 1, replicationFactor = 1)
        registry.register(config)

        registry.ensureTopicsExist()
        registry.ensureTopicsExist() // second call should not throw

        val existingTopics = adminClient.listTopics().names().get()
        assertTrue(existingTopics.contains(topicName))
    }

    @Test
    fun `allTopics returns all registered topics`() {
        val config1 = KafkaTopicConfig(name = "graphmesh.test.all1-${System.currentTimeMillis()}")
        val config2 = KafkaTopicConfig(name = "graphmesh.test.all2-${System.currentTimeMillis()}")
        registry.register(config1)
        registry.register(config2)
        assertEquals(2, registry.allTopics().size)
    }
}
