package com.agentwork.graphmesh.provenance.query

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentIterationCollectorTest {

    @Test
    fun `records think act observe sequence as one iteration`() {
        val collector = AgentIterationCollector()

        collector.recordThought("I should look up X")
        collector.recordToolStart("knowledge_query", mapOf("query" to "X"))
        collector.recordToolEnd("X is foo")

        val iterations = collector.snapshot()
        assertEquals(1, iterations.size)
        assertEquals("I should look up X", iterations[0].thought)
        assertEquals("knowledge_query", iterations[0].action)
        assertEquals(mapOf("query" to "X"), iterations[0].arguments)
        assertEquals("X is foo", iterations[0].observation)
    }

    @Test
    fun `records multiple iterations in order`() {
        val collector = AgentIterationCollector()

        collector.recordThought("t1")
        collector.recordToolStart("tool1", mapOf("a" to "1"))
        collector.recordToolEnd("obs1")

        collector.recordThought("t2")
        collector.recordToolStart("tool2", mapOf("b" to "2"))
        collector.recordToolEnd("obs2")

        val iterations = collector.snapshot()
        assertEquals(2, iterations.size)
        assertEquals("t1", iterations[0].thought)
        assertEquals("tool1", iterations[0].action)
        assertEquals("t2", iterations[1].thought)
        assertEquals("tool2", iterations[1].action)
    }

    @Test
    fun `final thought without tool call is flushed on snapshot`() {
        val collector = AgentIterationCollector()

        collector.recordThought("t1")
        collector.recordToolStart("tool1", null)
        collector.recordToolEnd("obs1")
        collector.recordThought("final reasoning")

        val iterations = collector.snapshot()
        assertEquals(2, iterations.size)
        assertEquals("final reasoning", iterations[1].thought)
        assertNull(iterations[1].action)
        assertNull(iterations[1].observation)
    }
}
