# NLP Query Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the partially implemented NLP Query Service by adding query reformulation, missing model fields, and comprehensive tests.

**Architecture:** Monolithic `NlpQueryService` class with Koog `PromptExecutor` for LLM calls. New `reformulate()` method added as private method. Models extended with `wasReformulated` and `reformulatedQuestion` fields. Existing test pattern (standalone logic copies) for parsing; mockk added for service-level orchestration tests.

**Tech Stack:** Kotlin, Spring Boot, Koog PromptExecutor, Spring GraphQL, JUnit 5, mockk

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `src/main/kotlin/.../query/nlp/NlpQueryModels.kt` | Modify | Add `reformulatedQuestion` to DetectedIntent, `wasReformulated` to NlpQueryResult |
| `src/main/resources/graphql/nlp-query.graphqls` | Modify | Add `wasReformulated` field to NlpQueryResponse |
| `src/main/kotlin/.../query/nlp/NlpQueryService.kt` | Modify | Add `reformulate()` method, integrate into `query()` flow |
| `src/test/kotlin/.../query/nlp/NlpQueryServiceTest.kt` | Modify | Add reformulation parsing tests, service orchestration tests with mockk |
| `build.gradle.kts` | Modify | Add mockk test dependency |

All paths relative to `/Users/czarnik/IdeaProjects/GraphMesh/`.

---

### Task 1: Add mockk dependency

**Files:**
- Modify: `build.gradle.kts:49-52`

- [ ] **Step 1: Add mockk to build.gradle.kts**

In the `dependencies` block, after the existing `testImplementation` lines, add:

```kotlin
    testImplementation("io.mockk:mockk:1.13.16")
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add mockk test dependency"
```

---

### Task 2: Extend data models

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryModels.kt`

- [ ] **Step 1: Write failing test for new fields**

In `src/test/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryServiceTest.kt`, add at the end of the class (before the closing `}`):

```kotlin
    @Test
    fun `DetectedIntent supports reformulatedQuestion field`() {
        val intent = DetectedIntent(
            intent = QueryIntent.GRAPH_QUERY,
            confidence = 0.9,
            reasoning = "test",
            reformulatedQuestion = "improved question"
        )
        assertEquals("improved question", intent.reformulatedQuestion)
    }

    @Test
    fun `DetectedIntent reformulatedQuestion defaults to null`() {
        val intent = DetectedIntent(QueryIntent.GRAPH_QUERY, 0.9, "test")
        assertEquals(null, intent.reformulatedQuestion)
    }

    @Test
    fun `NlpQueryResult includes wasReformulated field`() {
        val result = NlpQueryResult(
            answer = "answer",
            detectedIntent = DetectedIntent(QueryIntent.GRAPH_QUERY, 0.9, "test"),
            wasReformulated = true,
            effectiveQuestion = "reformulated",
            durationMs = 100,
            sources = emptyList()
        )
        assertEquals(true, result.wasReformulated)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.query.nlp.NlpQueryServiceTest"`
Expected: FAIL — `DetectedIntent` has no `reformulatedQuestion` parameter, `NlpQueryResult` has no `wasReformulated` parameter

- [ ] **Step 3: Update NlpQueryModels.kt**

Replace the full content of `src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryModels.kt` with:

```kotlin
package com.agentwork.graphmesh.query.nlp

data class NlpQuery(
    val question: String,
    val collectionId: String,
    val forceIntent: QueryIntent? = null
)

enum class QueryIntent {
    GRAPH_QUERY,
    DOCUMENT_QUERY,
    STRUCTURED_QUERY,
    HYBRID
}

data class DetectedIntent(
    val intent: QueryIntent,
    val confidence: Double,
    val reasoning: String,
    val reformulatedQuestion: String? = null
)

data class NlpQueryResult(
    val answer: String,
    val detectedIntent: DetectedIntent,
    val wasReformulated: Boolean,
    val effectiveQuestion: String,
    val durationMs: Long,
    val sources: List<String>
)
```

- [ ] **Step 4: Fix NlpQueryService.kt compilation**

In `src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt`, the `query()` method constructs `NlpQueryResult` without `wasReformulated`. Update the return statement in `query()` (around line 48-54) from:

```kotlin
        return NlpQueryResult(
            answer = answer,
            detectedIntent = detectedIntent,
            effectiveQuestion = query.question,
            durationMs = durationMs,
            sources = sources
        )
```

to:

```kotlin
        return NlpQueryResult(
            answer = answer,
            detectedIntent = detectedIntent,
            wasReformulated = false,
            effectiveQuestion = query.question,
            durationMs = durationMs,
            sources = sources
        )
```

This is a temporary placeholder — Task 4 will wire in the actual reformulation logic.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.query.nlp.NlpQueryServiceTest"`
Expected: PASS — all 11 tests green

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryModels.kt src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt src/test/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryServiceTest.kt
git commit -m "feat(nlp): add wasReformulated and reformulatedQuestion fields to models"
```

---

### Task 3: Update GraphQL schema

**Files:**
- Modify: `src/main/resources/graphql/nlp-query.graphqls`

- [ ] **Step 1: Add wasReformulated to NlpQueryResponse**

In `src/main/resources/graphql/nlp-query.graphqls`, change the `NlpQueryResponse` type from:

```graphql
type NlpQueryResponse {
    answer: String!
    detectedIntent: DetectedIntentType!
    effectiveQuestion: String!
    durationMs: Int!
    sources: [String!]!
}
```

to:

```graphql
type NlpQueryResponse {
    answer: String!
    detectedIntent: DetectedIntentType!
    wasReformulated: Boolean!
    effectiveQuestion: String!
    durationMs: Int!
    sources: [String!]!
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/graphql/nlp-query.graphqls
git commit -m "feat(nlp): add wasReformulated to GraphQL NlpQueryResponse"
```

---

### Task 4: Implement query reformulation

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt`
- Modify: `src/test/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryServiceTest.kt`

- [ ] **Step 1: Write failing tests for reformulation parsing**

In `src/test/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryServiceTest.kt`, add these tests and the standalone parsing method at the end of the class:

```kotlin
    @Test
    fun `parseReformulationResponse returns reformulated question`() {
        val response = "What specific entities in the knowledge graph are related to machine learning?"
        val result = parseReformulationResponse(response)
        assertEquals("What specific entities in the knowledge graph are related to machine learning?", result)
    }

    @Test
    fun `parseReformulationResponse returns null for KEINE_AENDERUNG`() {
        val response = "KEINE_AENDERUNG"
        val result = parseReformulationResponse(response)
        assertEquals(null, result)
    }

    @Test
    fun `parseReformulationResponse returns null for NO_CHANGE`() {
        val response = "NO_CHANGE"
        val result = parseReformulationResponse(response)
        assertEquals(null, result)
    }

    @Test
    fun `parseReformulationResponse trims whitespace`() {
        val response = "  What are the key relationships?  \n"
        val result = parseReformulationResponse(response)
        assertEquals("What are the key relationships?", result)
    }

    @Test
    fun `parseReformulationResponse returns null for blank`() {
        val response = "   "
        val result = parseReformulationResponse(response)
        assertEquals(null, result)
    }

    private fun parseReformulationResponse(response: String): String? {
        val trimmed = response.trim()
        if (trimmed.isBlank() || trimmed.equals("KEINE_AENDERUNG", ignoreCase = true) || trimmed.equals("NO_CHANGE", ignoreCase = true)) {
            return null
        }
        return trimmed
    }
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.query.nlp.NlpQueryServiceTest"`
Expected: PASS — all 16 tests green (the standalone parsing method makes them pass immediately)

- [ ] **Step 3: Add reformulate method and parsing to NlpQueryService**

In `src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt`, add these two methods after the existing `parseIntentResponse()` method (after line 110):

```kotlin
    private fun reformulate(question: String, intent: QueryIntent): String? {
        val reformulationPrompt = prompt("query-reformulation") {
            system("""
                You are a query reformulator. Analyze the question and improve it if it is vague or ambiguous.
                The detected query type is: ${intent.name}
                
                If the question is already specific enough, respond with exactly: NO_CHANGE
                Otherwise, respond with ONLY the improved question, nothing else.
                
                Rules:
                - Make vague questions more specific
                - Add context about what kind of answer is expected
                - Keep the core intent of the question
                - Do not add information the user didn't ask about
            """.trimIndent())
            user(question)
        }

        val llmModel = LLModel(LLMProvider.OpenAI, llmModelName)
        val response = runBlocking {
            promptExecutor.execute(reformulationPrompt, llmModel)
        }

        return parseReformulationResponse(response.first().content)
    }

    internal fun parseReformulationResponse(response: String): String? {
        val trimmed = response.trim()
        if (trimmed.isBlank() || trimmed.equals("KEINE_AENDERUNG", ignoreCase = true) || trimmed.equals("NO_CHANGE", ignoreCase = true)) {
            return null
        }
        return trimmed
    }
```

- [ ] **Step 4: Wire reformulation into query() method**

In `src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt`, replace the `query()` method body (lines 30-55) with:

```kotlin
    fun query(query: NlpQuery): NlpQueryResult {
        val startTime = System.currentTimeMillis()

        // Step 1: Intent Detection
        val detectedIntent = if (query.forceIntent != null) {
            DetectedIntent(query.forceIntent, 1.0, "Intent forced by caller")
        } else {
            logger.info("Detecting intent for: '{}'", query.question)
            detectIntent(query.question)
        }
        logger.info("Detected intent: {} (confidence: {})", detectedIntent.intent, detectedIntent.confidence)

        // Step 2: Reformulate if confidence is low
        val reformulated = if (detectedIntent.confidence < 0.7 && query.forceIntent == null) {
            logger.info("Low confidence ({}), attempting reformulation", detectedIntent.confidence)
            reformulate(query.question, detectedIntent.intent)
        } else {
            null
        }
        val effectiveQuestion = reformulated ?: query.question
        val wasReformulated = reformulated != null

        if (wasReformulated) {
            logger.info("Question reformulated: '{}' -> '{}'", query.question, effectiveQuestion)
        }

        // Step 3: Route to appropriate service
        val (answer, sources) = route(effectiveQuestion, detectedIntent.intent, query.collectionId)

        val durationMs = System.currentTimeMillis() - startTime
        logger.info("NLP query completed in {} ms via {}", durationMs, detectedIntent.intent)

        return NlpQueryResult(
            answer = answer,
            detectedIntent = detectedIntent,
            wasReformulated = wasReformulated,
            effectiveQuestion = effectiveQuestion,
            durationMs = durationMs,
            sources = sources
        )
    }
```

- [ ] **Step 5: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Update standalone parseIntentResponse in test to match new DetectedIntent**

In `src/test/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryServiceTest.kt`, the existing standalone `parseIntentResponse()` method returns `DetectedIntent` without the new `reformulatedQuestion` field. Since it has a default value of `null`, this should already compile. Verify by running:

Run: `./gradlew test --tests "com.agentwork.graphmesh.query.nlp.NlpQueryServiceTest"`
Expected: PASS — all 16 tests green

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt src/test/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryServiceTest.kt
git commit -m "feat(nlp): add query reformulation for low-confidence intents"
```

---

### Task 5: Add service orchestration tests with mockk

**Files:**
- Modify: `src/test/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryServiceTest.kt`

- [ ] **Step 1: Add orchestration tests**

Add a new test class in a separate file `src/test/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryServiceOrchestrationTest.kt`:

```kotlin
package com.agentwork.graphmesh.query.nlp

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import com.agentwork.graphmesh.query.docrag.DocumentRagQuery
import com.agentwork.graphmesh.query.docrag.DocumentRagResult
import com.agentwork.graphmesh.query.docrag.DocumentRagService
import com.agentwork.graphmesh.query.docrag.SourceAttribution
import com.agentwork.graphmesh.query.graphrag.GraphRagQuery
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
            answer = "Graph says...",
            selectedEdges = emptyList(),
            retrievedEdgeCount = 0,
            durationMs = 50
        )
        val docResult = DocumentRagResult(
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
```

- [ ] **Step 2: Run orchestration tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.query.nlp.NlpQueryServiceOrchestrationTest"`
Expected: PASS — all 7 tests green

Note: If `SourceAttribution` constructor signature differs, adjust the test data to match the actual constructor. The actual signature is: `SourceAttribution(chunkId, documentId, documentTitle, pageNumber, score, snippet)`. Check `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagModels.kt` if compilation fails.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryServiceOrchestrationTest.kt
git commit -m "test(nlp): add service orchestration tests with mockk"
```

---

### Task 6: Full build verification

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — all tests pass

- [ ] **Step 2: Run full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Final commit if any fixups were needed**

Only if changes were made during verification:
```bash
git add -A
git commit -m "fix(nlp): fixups from full build verification"
```
