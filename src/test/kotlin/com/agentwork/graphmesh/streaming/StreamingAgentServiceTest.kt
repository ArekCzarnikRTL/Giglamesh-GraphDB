package com.agentwork.graphmesh.streaming

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.streaming.StreamFrame
import com.agentwork.graphmesh.agent.AgentQueryConfig
import com.agentwork.graphmesh.agent.ToolGroupRegistry
import com.agentwork.graphmesh.query.docrag.DocumentRagService
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingAgentServiceTest {

    private val promptExecutor = mockk<PromptExecutor>()
    private val graphRagService = mockk<GraphRagService>()
    private val documentRagService = mockk<DocumentRagService>()
    private val toolGroupRegistry = ToolGroupRegistry()

    private val service = StreamingAgentServiceImpl(
        promptExecutor = promptExecutor,
        graphRagService = graphRagService,
        documentRagService = documentRagService,
        toolGroupRegistry = toolGroupRegistry,
        modelName = "gpt-4o"
    )

    @Test
    fun `parseToolQuestion extracts question from JSON`() {
        assertEquals(
            "What is photosynthesis?",
            service.parseToolQuestion("""{"question": "What is photosynthesis?"}""")
        )
    }

    @Test
    fun `parseToolQuestion returns raw string for non-JSON`() {
        assertEquals("plain text", service.parseToolQuestion("plain text"))
    }

    @Test
    fun `parseToolQuestion handles null`() {
        assertEquals("", service.parseToolQuestion(null))
    }

    @Test
    fun `parseToolQuestion handles empty string`() {
        assertEquals("", service.parseToolQuestion(""))
    }

    @Test
    fun `queryStreaming emits answer when no tool call`() = runBlocking {
        every { promptExecutor.executeStreaming(any(), any(), any()) } returns flowOf(
            StreamFrame.TextDelta("Hello world"),
            StreamFrame.End()
        )

        val tokens = service.queryStreaming(
            question = "What is this?",
            collectionId = "col-1",
            config = AgentQueryConfig(maxIterations = 3),
            allowedGroups = setOf("all")
        ).toList()

        // Should have THOUGHT for text delta + ANSWER for end
        val thought = tokens.first { it.type == StreamTokenType.THOUGHT }
        assertEquals("Hello world", thought.content)

        val answer = tokens.first { it.type == StreamTokenType.ANSWER }
        assertTrue(answer.endOfStream)
        assertTrue(answer.endOfMessage)
    }

    @Test
    fun `StreamToken model works correctly`() {
        val token = StreamToken(
            content = "Hello",
            type = StreamTokenType.THOUGHT,
            endOfMessage = false,
            endOfStream = false
        )
        assertEquals("Hello", token.content)
        assertEquals(StreamTokenType.THOUGHT, token.type)
        assertFalse(token.endOfMessage)
        assertFalse(token.endOfStream)
    }

    @Test
    fun `StreamToken endOfStream defaults to false`() {
        val token = StreamToken(content = "test", type = StreamTokenType.TEXT)
        assertFalse(token.endOfStream)
        assertFalse(token.endOfMessage)
    }

    @Test
    fun `StreamTokenType has all expected values`() {
        val types = StreamTokenType.entries
        assertEquals(6, types.size)
        assertEquals(StreamTokenType.TEXT, StreamTokenType.valueOf("TEXT"))
        assertEquals(StreamTokenType.THOUGHT, StreamTokenType.valueOf("THOUGHT"))
        assertEquals(StreamTokenType.ACTION, StreamTokenType.valueOf("ACTION"))
        assertEquals(StreamTokenType.OBSERVATION, StreamTokenType.valueOf("OBSERVATION"))
        assertEquals(StreamTokenType.ANSWER, StreamTokenType.valueOf("ANSWER"))
        assertEquals(StreamTokenType.ERROR, StreamTokenType.valueOf("ERROR"))
    }
}
