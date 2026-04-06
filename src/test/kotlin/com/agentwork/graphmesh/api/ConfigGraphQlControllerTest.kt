package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.config.ConfigItem
import com.agentwork.graphmesh.config.ConfigService
import com.agentwork.graphmesh.config.ConfigType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigGraphQlControllerTest {

    private val service = mockk<ConfigService>()
    private val controller = ConfigGraphQlController(service)

    @Test
    fun `configKeys returns entries for all types when type omitted`() {
        every { service.findAll(null) } returns listOf(
            ConfigItem(id = "LLM_SETTINGS:model", type = ConfigType.LLM_SETTINGS, key = "model", value = "gpt-4"),
            ConfigItem(id = "FLOW:x", type = ConfigType.FLOW, key = "x", value = "y")
        )

        val result = controller.configKeys(null)

        assertEquals(2, result.size)
        assertEquals("gpt-4", result.first { it.key == "model" }.value)
    }

    @Test
    fun `configKeys filters by type when given`() {
        every { service.findAll(ConfigType.LLM_SETTINGS) } returns listOf(
            ConfigItem(id = "LLM_SETTINGS:model", type = ConfigType.LLM_SETTINGS, key = "model", value = "gpt-4")
        )

        val result = controller.configKeys("LLM_SETTINGS")

        assertEquals(1, result.size)
        assertEquals("LLM_SETTINGS", result.single().type)
    }

    @Test
    fun `configValue returns null when not found`() {
        every { service.findByTypeAndKey(ConfigType.LLM_SETTINGS, "missing") } returns null

        val result = controller.configValue(key = "missing", type = "LLM_SETTINGS")

        assertNull(result)
    }

    @Test
    fun `configValue returns entry when present`() {
        every { service.findByTypeAndKey(ConfigType.LLM_SETTINGS, "model") } returns
            ConfigItem(id = "LLM_SETTINGS:model", type = ConfigType.LLM_SETTINGS, key = "model", value = "gpt-4")

        val result = controller.configValue(key = "model", type = "LLM_SETTINGS")

        assertEquals("gpt-4", result?.value)
    }

    @Test
    fun `setConfig saves and returns the entry`() {
        val captured = slot<ConfigItem>()
        every { service.findByTypeAndKey(ConfigType.LLM_SETTINGS, "model") } returns null
        every { service.save(capture(captured)) } answers {
            captured.captured.copy(version = 2)
        }

        val result = controller.setConfig(key = "model", value = "gpt-5", type = "LLM_SETTINGS")

        assertEquals("LLM_SETTINGS", result.type)
        assertEquals("model", result.key)
        assertEquals("gpt-5", result.value)
        assertEquals(2, result.version)
        verify { service.save(any()) }
    }
}
