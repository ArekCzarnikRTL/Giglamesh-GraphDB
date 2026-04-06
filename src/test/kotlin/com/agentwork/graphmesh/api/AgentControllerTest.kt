package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.agent.AgentQueryConfig
import com.agentwork.graphmesh.agent.AgentQueryResult
import com.agentwork.graphmesh.agent.AgentService
import com.agentwork.graphmesh.agent.ToolGroup
import com.agentwork.graphmesh.agent.ToolInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class AgentControllerTest {

    @Test
    fun `askAgent delegates to agent service`() {
        val agentService = mockk<AgentService>()
        every { agentService.query("What is X?", "col-1", any(), any()) } returns AgentQueryResult(
            sessionId = UUID.randomUUID(),
            answer = "X is a concept.",
            durationMs = 1500
        )

        val controller = AgentController(agentService)
        val input = AgentQueryInput(question = "What is X?", collectionId = "col-1", maxIterations = null, allowedGroups = null)

        val result = controller.askAgent(input)

        assertEquals("X is a concept.", result.answer)
        assertEquals(1500, result.durationMs)
        verify { agentService.query("What is X?", "col-1", any(), setOf("all")) }
    }

    @Test
    fun `askAgent passes custom maxIterations`() {
        val agentService = mockk<AgentService>()
        every { agentService.query(any(), any(), any(), any()) } returns AgentQueryResult(
            sessionId = UUID.randomUUID(), answer = "Answer", durationMs = 100
        )

        val controller = AgentController(agentService)
        val input = AgentQueryInput(question = "Q", collectionId = "col-1", maxIterations = 5, allowedGroups = null)

        controller.askAgent(input)

        verify { agentService.query("Q", "col-1", match { it.maxIterations == 5 }, any()) }
    }

    @Test
    fun `askAgent passes allowedGroups`() {
        val agentService = mockk<AgentService>()
        every { agentService.query(any(), any(), any(), any()) } returns AgentQueryResult(
            sessionId = UUID.randomUUID(), answer = "Answer", durationMs = 100
        )

        val controller = AgentController(agentService)
        val input = AgentQueryInput(question = "Q", collectionId = "col-1", maxIterations = null, allowedGroups = listOf("basic"))

        controller.askAgent(input)

        verify { agentService.query("Q", "col-1", any(), setOf("basic")) }
    }

    @Test
    fun `agentTools returns available tools with groups`() {
        val agentService = mockk<AgentService>()
        every { agentService.getAvailableTools() } returns listOf(
            ToolInfo("knowledge_query", "Query the knowledge graph", listOf("all", "basic")),
            ToolInfo("document_query", "Search documents", listOf("all", "advanced"))
        )

        val controller = AgentController(agentService)
        val tools = controller.agentTools()

        assertEquals(2, tools.size)
        assertEquals("knowledge_query", tools[0].name)
        assertEquals(listOf("all", "basic"), tools[0].groups)
    }

    @Test
    fun `toolGroups returns all groups`() {
        val agentService = mockk<AgentService>()
        every { agentService.getToolGroups() } returns listOf(
            ToolGroup("all", "All tools", setOf("knowledge_query", "document_query")),
            ToolGroup("basic", "Basic tools", setOf("knowledge_query"))
        )

        val controller = AgentController(agentService)
        val groups = controller.toolGroups()

        assertEquals(2, groups.size)
        assertEquals("all", groups[0].name)
    }
}
