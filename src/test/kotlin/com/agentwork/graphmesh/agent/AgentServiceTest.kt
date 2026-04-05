package com.agentwork.graphmesh.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentServiceTest {

    private val registry = ToolGroupRegistry()

    @Test
    fun `ToolGroupRegistry resolves all group correctly`() {
        val tools = registry.resolveToolNames(setOf("all"))
        assertEquals(2, tools.size)
        assertTrue(tools.contains("knowledge_query"))
        assertTrue(tools.contains("document_query"))
    }

    @Test
    fun `ToolGroupRegistry resolves basic group correctly`() {
        val tools = registry.resolveToolNames(setOf("basic"))
        assertEquals(1, tools.size)
        assertTrue(tools.contains("knowledge_query"))
    }

    @Test
    fun `DEFAULT_CONFIG has reasonable defaults`() {
        val config = AgentQueryConfig()
        assertEquals(10, config.maxIterations)
        assertTrue(config.systemPrompt.contains("knowledge_query"))
        assertTrue(config.systemPrompt.contains("document_query"))
    }

    @Test
    fun `ToolInfo includes groups`() {
        val info = ToolInfo(name = "test", description = "desc", groups = listOf("basic", "all"))
        assertEquals(2, info.groups.size)
    }
}
