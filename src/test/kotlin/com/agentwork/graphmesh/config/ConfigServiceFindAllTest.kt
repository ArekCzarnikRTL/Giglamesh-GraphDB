package com.agentwork.graphmesh.config

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigServiceFindAllTest {

    private val store = mockk<ConfigStore>()
    private val eventPublisher = mockk<org.springframework.context.ApplicationEventPublisher>(relaxed = true)
    private val producer = mockk<ConfigChangeProducer>(relaxed = true)
    private val service = ConfigService(store, eventPublisher, producer)

    @Test
    fun `findAll returns items for all types when type is null`() {
        every { store.findByType(ConfigType.ONTOLOGY) } returns listOf(item("ONTOLOGY", "o1"))
        every { store.findByType(ConfigType.FLOW) } returns listOf(item("FLOW", "f1"))
        every { store.findByType(ConfigType.TOOL) } returns emptyList()
        every { store.findByType(ConfigType.PARAMETER) } returns emptyList()
        every { store.findByType(ConfigType.COLLECTION_SETTINGS) } returns emptyList()
        every { store.findByType(ConfigType.LLM_SETTINGS) } returns emptyList()
        every { store.findByType(ConfigType.SCHEMA) } returns emptyList()

        val result = service.findAll(null)

        assertEquals(2, result.size)
        assertTrue(result.any { it.key == "o1" })
        assertTrue(result.any { it.key == "f1" })
    }

    @Test
    fun `findAll returns only matching type when type given`() {
        every { store.findByType(ConfigType.FLOW) } returns listOf(item("FLOW", "only"))

        val result = service.findAll(ConfigType.FLOW)

        assertEquals(1, result.size)
        assertEquals("only", result.single().key)
    }

    private fun item(type: String, key: String) = ConfigItem(
        id = "$type:$key",
        type = ConfigType.valueOf(type),
        key = key,
        value = "v"
    )
}
