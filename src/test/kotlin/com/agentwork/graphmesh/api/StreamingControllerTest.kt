package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.streaming.StreamToken
import com.agentwork.graphmesh.streaming.StreamTokenType
import com.agentwork.graphmesh.streaming.StreamingAgentService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class StreamingControllerTest {

    @Test
    fun `agentStream delegates to streaming agent service`() = runBlocking {
        val streamingService = mockk<StreamingAgentService>()
        every { streamingService.queryStreaming(any(), any(), any(), any()) } returns flowOf(
            StreamToken("thinking...", StreamTokenType.THOUGHT),
            StreamToken("The answer is 42.", StreamTokenType.ANSWER, endOfMessage = true, endOfStream = true)
        )

        val controller = StreamingController(streamingService)
        val input = AgentStreamInput(question = "What is the answer?", collectionId = "col-1", maxIterations = null, allowedGroups = null)

        val tokens = controller.agentStream(input).toList()

        assertEquals(2, tokens.size)
        assertEquals(StreamTokenType.THOUGHT, tokens[0].type)
        assertEquals(StreamTokenType.ANSWER, tokens[1].type)
        verify { streamingService.queryStreaming("What is the answer?", "col-1", any(), setOf("all")) }
    }

    @Test
    fun `agentStream passes custom parameters`() = runBlocking {
        val streamingService = mockk<StreamingAgentService>()
        every { streamingService.queryStreaming(any(), any(), any(), any()) } returns flowOf(
            StreamToken("done", StreamTokenType.ANSWER, endOfStream = true)
        )

        val controller = StreamingController(streamingService)
        val input = AgentStreamInput(
            question = "Q", collectionId = "col-1",
            maxIterations = 5, allowedGroups = listOf("basic")
        )

        controller.agentStream(input).toList()

        verify { streamingService.queryStreaming("Q", "col-1", match { it.maxIterations == 5 }, setOf("basic")) }
    }
}
