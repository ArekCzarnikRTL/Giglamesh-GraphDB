package com.agentwork.graphmesh.agent

import com.agentwork.graphmesh.query.docrag.DocumentRagResult
import com.agentwork.graphmesh.query.docrag.DocumentRagService
import com.agentwork.graphmesh.query.docrag.SourceAttribution
import com.agentwork.graphmesh.query.graphrag.GraphRagResult
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import com.agentwork.graphmesh.query.graphrag.SelectedEdge
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertContains

class AgentQueryToolsTest {

    @Test
    fun `KnowledgeQueryTool returns answer with sources`() = runBlocking {
        val graphRagService = mockk<GraphRagService>()
        every { graphRagService.query(any()) } returns GraphRagResult(
            answer = "Plants perform photosynthesis.",
            selectedEdges = listOf(
                SelectedEdge(
                    subject = "Plants", predicate = "perform", objectValue = "Photosynthesis",
                    dataset = "", reasoning = "Direct relationship", relevanceScore = 0.95
                )
            ),
            retrievedEdgeCount = 10,
            durationMs = 500
        )

        val tool = KnowledgeQueryTool(graphRagService, "col-1")
        val result = tool.execute(KnowledgeQueryTool.Args(question = "What do plants do?"))

        assertContains(result, "Plants perform photosynthesis.")
        assertContains(result, "Plants")
        assertContains(result, "Photosynthesis")
    }

    @Test
    fun `DocumentQueryTool returns answer with sources`() = runBlocking {
        val documentRagService = mockk<DocumentRagService>()
        every { documentRagService.query(any()) } returns DocumentRagResult(
            answer = "Photosynthesis is described in chapter 3.",
            sources = listOf(
                SourceAttribution(
                    chunkId = "chunk-1", documentId = "doc-1", documentTitle = "Biology Book",
                    pageNumber = 42, score = 0.9f, snippet = "Photosynthesis is the process..."
                )
            ),
            retrievedChunkCount = 5,
            durationMs = 300
        )

        val tool = DocumentQueryTool(documentRagService, "col-1")
        val result = tool.execute(DocumentQueryTool.Args(question = "Tell me about photosynthesis"))

        assertContains(result, "Photosynthesis is described in chapter 3.")
        assertContains(result, "Biology Book")
    }
}
