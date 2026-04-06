package com.agentwork.graphmesh.query.nlp

import ai.koog.prompt.executor.model.PromptExecutor
import com.agentwork.graphmesh.query.docrag.DocumentRagResult
import com.agentwork.graphmesh.query.docrag.DocumentRagService
import com.agentwork.graphmesh.query.docrag.SourceAttribution
import com.agentwork.graphmesh.query.graphrag.GraphRagResult
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import com.agentwork.graphmesh.query.graphrag.SelectedEdge
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NlpQueryServiceOrchestrationTest {

    private val promptExecutor: PromptExecutor = mockk()
    private val graphRagService: GraphRagService = mockk()
    private val documentRagService: DocumentRagService = mockk()
    private val quadStore: QuadStore = mockk()

    private lateinit var service: NlpQueryService

    @BeforeEach
    fun setUp() {
        service = NlpQueryService(
            promptExecutor = promptExecutor,
            graphRagService = graphRagService,
            documentRagService = documentRagService,
            quadStore = quadStore,
            llmModelName = "gpt-4o"
        )
    }

    @Test
    fun `query with forceIntent skips intent detection and routes to GraphRag`() {
        val graphResult = GraphRagResult(
            sessionId = UUID.randomUUID(),
            answer = "Alice works at Acme",
            selectedEdges = listOf(
                SelectedEdge("Alice", "worksAt", "Acme", "", "relevant", 0.9)
            ),
            retrievedEdgeCount = 5,
            durationMs = 100
        )
        every { graphRagService.query(any()) } returns graphResult

        val result = service.query(NlpQuery(
            question = "Where does Alice work?",
            collectionId = "test-collection",
            forceIntent = QueryIntent.GRAPH_QUERY
        ))

        assertEquals("Alice works at Acme", result.answer)
        assertEquals(QueryIntent.GRAPH_QUERY, result.detectedIntent.intent)
        assertEquals(1.0, result.detectedIntent.confidence)
        assertFalse(result.wasReformulated)
        verify(exactly = 1) { graphRagService.query(any()) }
    }

    @Test
    fun `query with forceIntent DOCUMENT_QUERY routes to DocumentRag`() {
        val docResult = DocumentRagResult(
            sessionId = UUID.randomUUID(),
            answer = "The document says...",
            sources = listOf(
                SourceAttribution("chunk-1", "doc-1", "Report.pdf", 3, 0.85f, "relevant text")
            ),
            retrievedChunkCount = 5,
            durationMs = 100
        )
        every { documentRagService.query(any()) } returns docResult

        val result = service.query(NlpQuery(
            question = "What does the report say?",
            collectionId = "test-collection",
            forceIntent = QueryIntent.DOCUMENT_QUERY
        ))

        assertEquals("The document says...", result.answer)
        assertEquals(QueryIntent.DOCUMENT_QUERY, result.detectedIntent.intent)
        verify(exactly = 1) { documentRagService.query(any()) }
    }

    @Test
    fun `query with forceIntent STRUCTURED_QUERY routes to QuadStore`() {
        val quads = listOf(
            StoredQuad("Alice", "worksAt", "Acme", "default")
        )
        every { quadStore.query("test-collection", any()) } returns quads

        val result = service.query(NlpQuery(
            question = "Find triples about Alice",
            collectionId = "test-collection",
            forceIntent = QueryIntent.STRUCTURED_QUERY
        ))

        assertTrue(result.answer.contains("Alice"))
        assertEquals(QueryIntent.STRUCTURED_QUERY, result.detectedIntent.intent)
        verify(exactly = 1) { quadStore.query("test-collection", any()) }
    }

    @Test
    fun `query with forceIntent HYBRID routes to both GraphRag and DocumentRag`() {
        val graphResult = GraphRagResult(
            sessionId = UUID.randomUUID(),
            answer = "Graph says...",
            selectedEdges = emptyList(),
            retrievedEdgeCount = 0,
            durationMs = 50
        )
        val docResult = DocumentRagResult(
            sessionId = UUID.randomUUID(),
            answer = "Document says...",
            sources = emptyList(),
            retrievedChunkCount = 0,
            durationMs = 50
        )
        every { graphRagService.query(any()) } returns graphResult
        every { documentRagService.query(any()) } returns docResult

        val result = service.query(NlpQuery(
            question = "Complex question",
            collectionId = "test-collection",
            forceIntent = QueryIntent.HYBRID
        ))

        assertTrue(result.answer.contains("Graph says..."))
        assertTrue(result.answer.contains("Document says..."))
        assertEquals(QueryIntent.HYBRID, result.detectedIntent.intent)
        verify(exactly = 1) { graphRagService.query(any()) }
        verify(exactly = 1) { documentRagService.query(any()) }
    }

    @Test
    fun `query with forceIntent does not trigger reformulation`() {
        val graphResult = GraphRagResult(
            sessionId = UUID.randomUUID(),
            answer = "answer",
            selectedEdges = emptyList(),
            retrievedEdgeCount = 0,
            durationMs = 50
        )
        every { graphRagService.query(any()) } returns graphResult

        val result = service.query(NlpQuery(
            question = "vague question",
            collectionId = "test-collection",
            forceIntent = QueryIntent.GRAPH_QUERY
        ))

        assertFalse(result.wasReformulated)
        assertEquals("vague question", result.effectiveQuestion)
    }

    @Test
    fun `query result contains duration in milliseconds`() {
        val graphResult = GraphRagResult(
            sessionId = UUID.randomUUID(),
            answer = "answer",
            selectedEdges = emptyList(),
            retrievedEdgeCount = 0,
            durationMs = 50
        )
        every { graphRagService.query(any()) } returns graphResult

        val result = service.query(NlpQuery(
            question = "test",
            collectionId = "test-collection",
            forceIntent = QueryIntent.GRAPH_QUERY
        ))

        assertTrue(result.durationMs >= 0)
    }

    @Test
    fun `query with STRUCTURED_QUERY and empty quad store returns no triples message`() {
        every { quadStore.query("test-collection", any()) } returns emptyList()

        val result = service.query(NlpQuery(
            question = "Find triples",
            collectionId = "test-collection",
            forceIntent = QueryIntent.STRUCTURED_QUERY
        ))

        assertEquals("No matching triples found.", result.answer)
    }
}
