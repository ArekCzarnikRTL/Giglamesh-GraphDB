# MCP Tool Interface — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the weak MCP tools test with proper MockK-based unit tests that exercise the actual `GraphMeshMcpTools` service.

**Architecture:** Single test class instantiates `GraphMeshMcpTools` with 4 mocked dependencies. Each test mocks the service return value and asserts the formatted string output. Follows existing project test patterns (MockK, JUnit 5, kotlin-test).

**Tech Stack:** Kotlin, JUnit 5, MockK 1.13.16, kotlin-test

---

### Task 1: Rewrite GraphMeshMcpToolsTest with MockK

**Files:**
- Rewrite: `src/test/kotlin/com/agentwork/graphmesh/api/mcp/GraphMeshMcpToolsTest.kt`

**Reference files (read before implementing):**
- `src/main/kotlin/com/agentwork/graphmesh/api/mcp/GraphMeshMcpTools.kt` — the class under test
- `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagModels.kt` — `GraphRagQuery`, `GraphRagResult`, `SelectedEdge`
- `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagModels.kt` — `DocumentRagQuery`, `DocumentRagResult`, `SourceAttribution`
- `src/main/kotlin/com/agentwork/graphmesh/collection/Collection.kt` — `Collection` data class
- `src/main/kotlin/com/agentwork/graphmesh/librarian/Document.kt` — `Document`, `DocumentType`, `DocumentState`
- `src/test/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryServiceOrchestrationTest.kt` — example of MockK test pattern in this project

- [ ] **Step 1: Write knowledgeQuery tests**

Replace the entire content of `GraphMeshMcpToolsTest.kt` with:

```kotlin
package com.agentwork.graphmesh.api.mcp

import com.agentwork.graphmesh.collection.Collection
import com.agentwork.graphmesh.collection.CollectionService
import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentState
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.query.docrag.DocumentRagQuery
import com.agentwork.graphmesh.query.docrag.DocumentRagResult
import com.agentwork.graphmesh.query.docrag.DocumentRagService
import com.agentwork.graphmesh.query.docrag.SourceAttribution
import com.agentwork.graphmesh.query.graphrag.GraphRagQuery
import com.agentwork.graphmesh.query.graphrag.GraphRagResult
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import com.agentwork.graphmesh.query.graphrag.SelectedEdge
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphMeshMcpToolsTest {

    private val graphRagService = mockk<GraphRagService>()
    private val documentRagService = mockk<DocumentRagService>()
    private val collectionService = mockk<CollectionService>()
    private val librarianService = mockk<LibrarianService>()

    private val tools = GraphMeshMcpTools(
        graphRagService, documentRagService, collectionService, librarianService
    )

    // --- knowledgeQuery ---

    @Test
    fun `knowledgeQuery formats answer with sources`() {
        every { graphRagService.query(any()) } returns GraphRagResult(
            answer = "Alice works at Acme Corp.",
            selectedEdges = listOf(
                SelectedEdge("Alice", "worksAt", "Acme Corp", "ds1", "Direct relationship", 0.9)
            ),
            retrievedEdgeCount = 50,
            durationMs = 100
        )

        val output = tools.knowledgeQuery("Who does Alice work for?", "col-1", null)

        assertTrue(output.contains("Alice works at Acme Corp."))
        assertTrue(output.contains("Alice --[worksAt]--> Acme Corp"))
        assertTrue(output.contains("Reasoning: Direct relationship"))
        assertTrue(output.contains("1 edges from 50 retrieved"))
    }

    @Test
    fun `knowledgeQuery uses default maxEdges when null`() {
        every { graphRagService.query(any()) } returns GraphRagResult(
            answer = "Answer", selectedEdges = emptyList(), retrievedEdgeCount = 0, durationMs = 0
        )

        tools.knowledgeQuery("question", "col-1", null)

        verify { graphRagService.query(GraphRagQuery("question", "col-1", maxEdges = 150)) }
    }

    @Test
    fun `knowledgeQuery passes custom maxEdges`() {
        every { graphRagService.query(any()) } returns GraphRagResult(
            answer = "Answer", selectedEdges = emptyList(), retrievedEdgeCount = 0, durationMs = 0
        )

        tools.knowledgeQuery("question", "col-1", 50)

        verify { graphRagService.query(GraphRagQuery("question", "col-1", maxEdges = 50)) }
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.api.mcp.GraphMeshMcpToolsTest" --info`
Expected: 3 tests PASS

- [ ] **Step 3: Add documentQuery tests**

Add the following tests to the class (after the knowledgeQuery tests):

```kotlin
    // --- documentQuery ---

    @Test
    fun `documentQuery formats answer with sources`() {
        every { documentRagService.query(any()) } returns DocumentRagResult(
            answer = "The document discusses AI.",
            sources = listOf(
                SourceAttribution("chunk-1", "doc-1", "AI Paper", 3, 0.85f, "AI is transforming...")
            ),
            retrievedChunkCount = 5,
            durationMs = 100
        )

        val output = tools.documentQuery("What is AI?", "col-1", null)

        assertTrue(output.contains("The document discusses AI."))
        assertTrue(output.contains("AI Paper"))
        assertTrue(output.contains("page 3"))
        assertTrue(output.contains("score: 0.85"))
        assertTrue(output.contains("1 chunks"))
    }

    @Test
    fun `documentQuery uses default topK when null`() {
        every { documentRagService.query(any()) } returns DocumentRagResult(
            answer = "Answer", sources = emptyList(), retrievedChunkCount = 0, durationMs = 0
        )

        tools.documentQuery("question", "col-1", null)

        verify { documentRagService.query(DocumentRagQuery("question", "col-1", topK = 10)) }
    }

    @Test
    fun `documentQuery passes custom topK`() {
        every { documentRagService.query(any()) } returns DocumentRagResult(
            answer = "Answer", sources = emptyList(), retrievedChunkCount = 0, durationMs = 0
        )

        tools.documentQuery("question", "col-1", 5)

        verify { documentRagService.query(DocumentRagQuery("question", "col-1", topK = 5)) }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.api.mcp.GraphMeshMcpToolsTest" --info`
Expected: 6 tests PASS

- [ ] **Step 5: Add collectionList tests**

Add:

```kotlin
    // --- collectionList ---

    @Test
    fun `collectionList formats collections`() {
        every { collectionService.findAll(any()) } returns listOf(
            Collection(id = "c1", name = "Research", description = "Research papers", tags = setOf("ai", "ml")),
            Collection(id = "c2", name = "Legal", description = "Legal docs", tags = setOf("compliance"))
        )

        val output = tools.collectionList(null)

        assertTrue(output.contains("Research (ID: c1): Research papers [Tags: ai, ml]"))
        assertTrue(output.contains("Legal (ID: c2): Legal docs [Tags: compliance]"))
    }

    @Test
    fun `collectionList returns message on empty result`() {
        every { collectionService.findAll(any()) } returns emptyList()

        val output = tools.collectionList(null)

        assertEquals("No collections found.", output)
    }

    @Test
    fun `collectionList passes parsed tags to service`() {
        every { collectionService.findAll(any()) } returns emptyList()

        tools.collectionList("ai, ml")

        verify { collectionService.findAll(setOf("ai", "ml")) }
    }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.api.mcp.GraphMeshMcpToolsTest" --info`
Expected: 9 tests PASS

- [ ] **Step 7: Add documentSearch tests**

Add:

```kotlin
    // --- documentSearch ---

    @Test
    fun `documentSearch formats documents`() {
        every { librarianService.findByCollection("col-1", null) } returns listOf(
            Document(id = "d1", collectionId = "col-1", title = "Report Q1", type = DocumentType.SOURCE, state = DocumentState.EXTRACTED),
            Document(id = "d2", collectionId = "col-1", title = "Report Q2", type = DocumentType.SOURCE, state = DocumentState.PROCESSING)
        )

        val output = tools.documentSearch("col-1", null)

        assertTrue(output.contains("Report Q1 (ID: d1, type: SOURCE, state: EXTRACTED)"))
        assertTrue(output.contains("Report Q2 (ID: d2, type: SOURCE, state: PROCESSING)"))
    }

    @Test
    fun `documentSearch returns message on empty result`() {
        every { librarianService.findByCollection("col-1", null) } returns emptyList()

        val output = tools.documentSearch("col-1", null)

        assertEquals("No documents found.", output)
    }

    @Test
    fun `documentSearch filters by title case-insensitively`() {
        every { librarianService.findByCollection("col-1", null) } returns listOf(
            Document(id = "d1", collectionId = "col-1", title = "Report Q1", type = DocumentType.SOURCE, state = DocumentState.EXTRACTED),
            Document(id = "d2", collectionId = "col-1", title = "Invoice March", type = DocumentType.SOURCE, state = DocumentState.EXTRACTED)
        )

        val output = tools.documentSearch("col-1", "report")

        assertTrue(output.contains("Report Q1"))
        assertTrue(!output.contains("Invoice March"))
    }
```

- [ ] **Step 8: Run all tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.api.mcp.GraphMeshMcpToolsTest" --info`
Expected: 12 tests PASS

- [ ] **Step 9: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/api/mcp/GraphMeshMcpToolsTest.kt
git commit -m "test(mcp): rewrite MCP tools tests with MockK"
```
