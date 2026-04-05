package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.agent.AgentQueryConfig
import com.agentwork.graphmesh.agent.AgentQueryResult
import com.agentwork.graphmesh.agent.AgentService
import com.agentwork.graphmesh.agent.ToolInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AgentControllerTest {

    @Test
    fun `askAgent delegates to agent service`() {
        val agentService = mockk<AgentService>()
        every { agentService.query("What is X?", "col-1", any()) } returns AgentQueryResult(
            answer = "X is a concept.",
            durationMs = 1500
        )

        val controller = AgentController(agentService)
        val input = AgentQueryInput(question = "What is X?", collectionId = "col-1", maxIterations = null)

        val result = controller.askAgent(input)

        assertEquals("X is a concept.", result.answer)
        assertEquals(1500, result.durationMs)
        verify { agentService.query("What is X?", "col-1", any()) }
    }

    @Test
    fun `askAgent passes custom maxIterations`() {
        val agentService = mockk<AgentService>()
        every { agentService.query(any(), any(), any()) } returns AgentQueryResult(
            answer = "Answer", durationMs = 100
        )

        val controller = AgentController(agentService)
        val input = AgentQueryInput(question = "Q", collectionId = "col-1", maxIterations = 5)

        controller.askAgent(input)

        verify { agentService.query("Q", "col-1", match { it.maxIterations == 5 }) }
    }

    @Test
    fun `agentTools returns available tools`() {
        val agentService = mockk<AgentService>()
        every { agentService.getAvailableTools() } returns listOf(
            ToolInfo("knowledge_query", "Query the knowledge graph"),
            ToolInfo("document_query", "Search documents")
        )

        val controller = AgentController(agentService)
        val tools = controller.agentTools()

        assertEquals(2, tools.size)
        assertEquals("knowledge_query", tools[0].name)
        assertEquals("document_query", tools[1].name)
    }
}
