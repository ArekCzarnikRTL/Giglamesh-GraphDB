package com.agentwork.graphmesh.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentServiceTest {

    @Test
    fun `getAvailableTools returns knowledge and document tools`() {
        val tools = AgentService.AVAILABLE_TOOLS
        assertEquals(2, tools.size)
        assertTrue(tools.any { it.name == "knowledge_query" })
        assertTrue(tools.any { it.name == "document_query" })
    }

    @Test
    fun `getAvailableTools has descriptions`() {
        val tools = AgentService.AVAILABLE_TOOLS
        assertTrue(tools.all { it.description.isNotBlank() })
    }

    @Test
    fun `DEFAULT_CONFIG has reasonable defaults`() {
        val config = AgentQueryConfig()
        assertEquals(10, config.maxIterations)
        assertTrue(config.systemPrompt.contains("knowledge_query"))
        assertTrue(config.systemPrompt.contains("document_query"))
    }
}
