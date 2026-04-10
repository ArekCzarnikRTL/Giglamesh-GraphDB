# Feature 45: Query Performance Optimierung — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce query latency by 50-70% by eliminating unnecessary LLM calls, parallelizing independent operations, and caching embeddings.

**Architecture:** Schrittweise Optimierung in 7 Tasks. Jeder Task ist ein eigener Commit. Tasks 1-3 sind unabhaengig, Tasks 4-6 bauen auf 3 auf, Task 7 baut auf 1-2 auf.

**Tech Stack:** Kotlin, Spring Boot, Caffeine Cache, Kotlin Coroutines (transitiv via Koog), Koog LLM Framework

---

## File Map

| Datei | Aenderung |
|---|---|
| `src/main/kotlin/.../query/graphrag/GraphRagService.kt` | Task 1: selectAndSynthesize(), Task 4: precomputedEmbedding |
| `src/main/kotlin/.../query/graphrag/GraphRagModels.kt` | Task 4: precomputedEmbedding Feld |
| `src/main/kotlin/.../query/docrag/DocumentRagModels.kt` | Task 4: precomputedEmbedding Feld |
| `src/main/kotlin/.../query/docrag/DocumentRagService.kt` | Task 3+4: CachedEmbeddingService + precomputed |
| `src/main/kotlin/.../query/nlp/NlpQueryService.kt` | Task 2+4+5+6: Heuristik, Embedding durchreichen, Parallelisierung |
| `src/main/kotlin/.../query/CachedEmbeddingService.kt` | Task 3: NEU |
| `src/main/kotlin/.../query/CollectionContentTypeService.kt` | Task 2: NEU |
| `build.gradle.kts` | Task 2: Caffeine Dependency |
| `src/test/kotlin/.../query/graphrag/GraphRagServiceTest.kt` | Task 1+7: parseSelectAndSynthesize Tests |
| `src/test/kotlin/.../query/CachedEmbeddingServiceTest.kt` | Task 7: NEU |
| `src/test/kotlin/.../query/CollectionContentTypeServiceTest.kt` | Task 7: NEU |
| `src/test/kotlin/.../query/nlp/NlpQueryServiceTest.kt` | Task 7: Intent-Heuristik Tests |

---

### Task 1: Edge-Selection + Synthesis zusammenlegen

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`
- Modify: `src/test/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagServiceTest.kt`

- [ ] **Step 1: Write failing test for parseSelectAndSynthesize**

In `src/test/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagServiceTest.kt`, add:

```kotlin
@Test
fun `parseSelectAndSynthesize extracts answer and edges`() {
    val response = """
        ANSWER:
        Alice und Bob arbeiten bei Acme Corp.

        EDGES:
        0|Alice works at Acme Corp, directly relevant
        2|Location info helps contextualize
    """.trimIndent()

    val (answer, edges) = parseSelectAndSynthesizeCopy(response, testEdges)
    assertEquals("Alice und Bob arbeiten bei Acme Corp.", answer.trim())
    assertEquals(2, edges.size)
    assertEquals("Alice", edges[0].subject)
    assertEquals("Acme Corp", edges[1].subject)
}

@Test
fun `parseSelectAndSynthesize handles missing EDGES marker`() {
    val response = "Just a plain answer without edges section."
    val (answer, edges) = parseSelectAndSynthesizeCopy(response, testEdges)
    assertEquals("Just a plain answer without edges section.", answer.trim())
    assertTrue(edges.isEmpty())
}

@Test
fun `parseSelectAndSynthesize handles ANSWER marker only`() {
    val response = """
        ANSWER:
        Some answer text here.
    """.trimIndent()

    val (answer, edges) = parseSelectAndSynthesizeCopy(response, testEdges)
    assertEquals("Some answer text here.", answer.trim())
    assertTrue(edges.isEmpty())
}

@Test
fun `parseSelectAndSynthesize handles empty answer section`() {
    val response = """
        ANSWER:

        EDGES:
        0|Relevant edge
    """.trimIndent()

    val (answer, edges) = parseSelectAndSynthesizeCopy(response, testEdges)
    assertTrue(answer.isBlank())
    assertEquals(1, edges.size)
}

private fun parseSelectAndSynthesizeCopy(
    llmResponse: String,
    edges: List<StoredQuad>
): Pair<String, List<SelectedEdge>> {
    val edgesMarker = "EDGES:"
    val answerMarker = "ANSWER:"
    val edgesIdx = llmResponse.indexOf(edgesMarker)

    val rawAnswer = if (edgesIdx >= 0) {
        llmResponse.substring(0, edgesIdx)
    } else {
        llmResponse
    }.removePrefix(answerMarker).trim()

    val edgeSection = if (edgesIdx >= 0) {
        llmResponse.substring(edgesIdx + edgesMarker.length)
    } else {
        ""
    }

    val selectedEdges = edgeSection.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() && it.contains("|") }
        .mapNotNull { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val index = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
            val reasoning = parts[1].trim()
            if (index !in edges.indices || reasoning.isBlank()) return@mapNotNull null

            val quad = edges[index]
            SelectedEdge(
                subject = quad.subject,
                predicate = quad.predicate,
                objectValue = quad.objectValue,
                dataset = quad.dataset,
                reasoning = reasoning,
                relevanceScore = 1.0 - (index.toDouble() / edges.size)
            )
        }

    return rawAnswer to selectedEdges
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.query.graphrag.GraphRagServiceTest" -x compileTestKotlin`

Expected: Compile error since `parseSelectAndSynthesizeCopy` is in the test — this should actually compile and pass since it's self-contained. Run to verify the parsing logic is correct first.

- [ ] **Step 3: Implement selectAndSynthesize in GraphRagService**

In `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`, add the new method and update `query()`:

Replace the Phase 2 + Phase 3 section in `query()`:

```kotlin
// Old:
// Phase 2: Edge Selection
logger.info("Phase 2: Edge selection from {} edges", subgraph.size)
val selectedEdges = selectEdges(query.question, subgraph, query.maxSelectedEdges)
logger.info("{} edges selected", selectedEdges.size)

// Phase 3: Answer Synthesis
logger.info("Phase 3: Answer synthesis")
val answer = synthesizeAnswer(query.question, selectedEdges)
```

With:

```kotlin
// Phase 2+3: Combined selection and synthesis
logger.info("Phase 2+3: Select and synthesize from {} edges", subgraph.size)
val (answer, selectedEdges) = selectAndSynthesize(query.question, subgraph, query.maxSelectedEdges)
logger.info("{} edges selected, answer generated", selectedEdges.size)
```

Add the new method:

```kotlin
private fun selectAndSynthesize(
    question: String,
    edges: List<StoredQuad>,
    maxSelected: Int
): Pair<String, List<SelectedEdge>> {
    val edgeList = edges.mapIndexed { index, quad ->
        "$index|${quad.subject}|${quad.predicate}|${quad.objectValue}"
    }.joinToString("\n")

    val combinedPrompt = prompt("select-and-synthesize") {
        system("""
            You are a knowledge assistant. Answer the user's question based ONLY on the
            provided knowledge graph facts. Do not make up information beyond what the facts state.
            If the facts don't contain enough information, say so.

            After your answer, list the fact numbers you used with a brief reason.
            Select at most $maxSelected facts. Only cite truly relevant facts.

            Knowledge graph facts:
            $edgeList

            Respond in this exact format:
            ANSWER:
            <your answer here>

            EDGES:
            <index>|<why this fact was relevant>
        """.trimIndent())
        user(question)
    }

    val llmModel = resolveLlmModel(llmModelName)
    val response = runBlocking {
        promptExecutor.execute(combinedPrompt, llmModel)
    }

    return parseSelectAndSynthesize(response.first().content, edges)
}

internal fun parseSelectAndSynthesize(
    llmResponse: String,
    edges: List<StoredQuad>
): Pair<String, List<SelectedEdge>> {
    val edgesMarker = "EDGES:"
    val answerMarker = "ANSWER:"
    val edgesIdx = llmResponse.indexOf(edgesMarker)

    val rawAnswer = if (edgesIdx >= 0) {
        llmResponse.substring(0, edgesIdx)
    } else {
        llmResponse
    }.removePrefix(answerMarker).trim()

    val edgeSection = if (edgesIdx >= 0) {
        llmResponse.substring(edgesIdx + edgesMarker.length)
    } else {
        ""
    }

    val selectedEdges = edgeSection.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() && it.contains("|") }
        .mapNotNull { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val index = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
            val reasoning = parts[1].trim()
            if (index !in edges.indices || reasoning.isBlank()) return@mapNotNull null

            val quad = edges[index]
            SelectedEdge(
                subject = quad.subject,
                predicate = quad.predicate,
                objectValue = quad.objectValue,
                dataset = quad.dataset,
                reasoning = reasoning,
                relevanceScore = 1.0 - (index.toDouble() / edges.size)
            )
        }

    return rawAnswer to selectedEdges
}
```

- [ ] **Step 4: Run all GraphRag tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.query.graphrag.*"`

Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt \
        src/test/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagServiceTest.kt
git commit -m "perf(graph-rag): merge edge selection and answer synthesis into one LLM call"
```

---

### Task 2: CollectionContentTypeService + Intent-Heuristik

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/query/CollectionContentTypeService.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add Caffeine dependency**

In `build.gradle.kts`, add to the `dependencies` block:

```kotlin
implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
```

- [ ] **Step 2: Create CollectionContentTypeService**

Create `src/main/kotlin/com/agentwork/graphmesh/query/CollectionContentTypeService.kt`:

```kotlin
package com.agentwork.graphmesh.query

import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class CollectionContentTypeService(
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService,
) {

    data class ContentFlags(val hasTriples: Boolean, val hasDocuments: Boolean)

    private val cache: Cache<String, ContentFlags> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(Duration.ofMinutes(10))
        .build()

    fun hasTriples(collectionId: String): Boolean =
        getFlags(collectionId).hasTriples

    fun hasDocuments(collectionId: String): Boolean =
        getFlags(collectionId).hasDocuments

    fun isMixed(collectionId: String): Boolean =
        getFlags(collectionId).let { it.hasTriples && it.hasDocuments }

    fun invalidate(collectionId: String) = cache.invalidate(collectionId)

    private fun getFlags(collectionId: String): ContentFlags =
        cache.get(collectionId) { cid ->
            ContentFlags(
                hasTriples = quadStore.query(cid, QuadQuery(), limit = 1).isNotEmpty(),
                hasDocuments = librarianService.findByCollection(cid).isNotEmpty()
            )
        }
}
```

- [ ] **Step 3: Add intent heuristic to NlpQueryService**

In `src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt`:

Add constructor parameter:

```kotlin
class NlpQueryService(
    private val promptExecutor: PromptExecutor,
    private val graphRagService: GraphRagService,
    private val documentRagService: DocumentRagService,
    private val quadStore: QuadStore,
    private val contentTypeService: CollectionContentTypeService,  // NEW
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val llmModelName: String
)
```

Add import:

```kotlin
import com.agentwork.graphmesh.query.CollectionContentTypeService
```

Replace the intent detection block in `query()`:

```kotlin
// Old:
val detectedIntent = if (query.forceIntent != null) {
    DetectedIntent(query.forceIntent, 1.0, "Intent forced by caller")
} else {
    logger.info("Detecting intent for: '{}'", query.question)
    detectIntent(query.question)
}

// New:
val detectedIntent = when {
    query.forceIntent != null ->
        DetectedIntent(query.forceIntent, 1.0, "Intent forced by caller")
    !contentTypeService.hasDocuments(query.collectionId) -> {
        logger.info("Collection has only graph data, skipping intent detection")
        DetectedIntent(QueryIntent.GRAPH_QUERY, 1.0, "Collection contains only graph data")
    }
    !contentTypeService.hasTriples(query.collectionId) -> {
        logger.info("Collection has only documents, skipping intent detection")
        DetectedIntent(QueryIntent.DOCUMENT_QUERY, 1.0, "Collection contains only documents")
    }
    else -> {
        logger.info("Mixed collection, detecting intent for: '{}'", query.question)
        detectIntent(query.question)
    }
}
```

- [ ] **Step 4: Build and run tests**

Run: `./gradlew test`

Expected: All PASS (existing tests still work, new service auto-wires)

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts \
        src/main/kotlin/com/agentwork/graphmesh/query/CollectionContentTypeService.kt \
        src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt
git commit -m "perf(nlp): skip intent detection for single-type collections"
```

---

### Task 3: CachedEmbeddingService

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/query/CachedEmbeddingService.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt`

- [ ] **Step 1: Create CachedEmbeddingService**

Create `src/main/kotlin/com/agentwork/graphmesh/query/CachedEmbeddingService.kt`:

```kotlin
package com.agentwork.graphmesh.query

import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.extraction.embedding.EmbeddingConfig
import com.agentwork.graphmesh.llm.resolveLlmModel
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class CachedEmbeddingService(
    private val embeddingProvider: LLMEmbeddingProvider,
    private val embeddingConfig: EmbeddingConfig,
) {

    private val cache: Cache<String, FloatArray> = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofMinutes(30))
        .build()

    fun embed(text: String): FloatArray {
        val model = resolveLlmModel(embeddingConfig.model)
        val key = "${model.id}:${text.hashCode()}"
        return cache.get(key) { _ ->
            val embedding = runBlocking { embeddingProvider.embed(text, model) }
            FloatArray(embedding.size) { embedding[it].toFloat() }
        }
    }
}
```

- [ ] **Step 2: Replace embeddingProvider in GraphRagService**

In `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`:

Change constructor — replace `embeddingProvider: LLMEmbeddingProvider` and `embeddingConfig: EmbeddingConfig` with `cachedEmbeddingService: CachedEmbeddingService`:

```kotlin
@Service
class GraphRagService(
    private val cachedEmbeddingService: CachedEmbeddingService,  // CHANGED
    private val vectorStore: VectorStore,
    private val quadStore: QuadStore,
    private val promptExecutor: PromptExecutor,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val llmModelName: String,
    private val explainabilityProducer: ExplainabilityEventProducer
)
```

Add import:

```kotlin
import com.agentwork.graphmesh.query.CachedEmbeddingService
```

Remove imports that are no longer needed:

```kotlin
// Remove these:
import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import com.agentwork.graphmesh.extraction.embedding.EmbeddingConfig
```

In `retrieveSubgraph()`, replace the embedding block:

```kotlin
// Old:
val embeddingModel = resolveLlmModel(embeddingConfig.model)
val embedding = runBlocking {
    embeddingProvider.embed(query.question, embeddingModel)
}
val queryVector = FloatArray(embedding.size) { embedding[it].toFloat() }

// New:
val queryVector = cachedEmbeddingService.embed(query.question)
```

- [ ] **Step 3: Replace embeddingProvider in DocumentRagService**

In `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt`:

Change constructor — replace `embeddingProvider: LLMEmbeddingProvider` and `embeddingConfig: EmbeddingConfig` with `cachedEmbeddingService: CachedEmbeddingService`:

```kotlin
@Service
class DocumentRagService(
    private val cachedEmbeddingService: CachedEmbeddingService,  // CHANGED
    private val vectorStore: VectorStore,
    private val librarianService: LibrarianService,
    private val promptExecutor: PromptExecutor,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val llmModelName: String,
    private val explainabilityProducer: ExplainabilityEventProducer
)
```

Add import:

```kotlin
import com.agentwork.graphmesh.query.CachedEmbeddingService
```

Remove imports:

```kotlin
// Remove:
import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import com.agentwork.graphmesh.extraction.embedding.EmbeddingConfig
```

In `retrieveChunks()`, replace embedding block:

```kotlin
// Old:
val embeddingModel = resolveLlmModel(embeddingConfig.model)
val embedding = runBlocking {
    embeddingProvider.embed(query.question, embeddingModel)
}
val queryVector = FloatArray(embedding.size) { embedding[it].toFloat() }

// New:
val queryVector = cachedEmbeddingService.embed(query.question)
```

- [ ] **Step 4: Build and run tests**

Run: `./gradlew test`

Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/query/CachedEmbeddingService.kt \
        src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt \
        src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt
git commit -m "perf(query): add CachedEmbeddingService to avoid redundant embedding calls"
```

---

### Task 4: Precomputed Embedding durchreichen

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagModels.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagModels.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt`

- [ ] **Step 1: Add precomputedEmbedding to GraphRagQuery**

In `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagModels.kt`:

```kotlin
data class GraphRagQuery(
    val question: String,
    val collectionId: String,
    val precomputedEmbedding: FloatArray? = null,
    val maxEdges: Int = 150,
    val maxDepth: Int = 2,
    val maxSelectedEdges: Int = 30
)
```

- [ ] **Step 2: Add precomputedEmbedding to DocumentRagQuery**

In `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagModels.kt`:

```kotlin
data class DocumentRagQuery(
    val question: String,
    val collectionId: String,
    val precomputedEmbedding: FloatArray? = null,
    val topK: Int = 10,
    val similarityThreshold: Float = 0.3f
)
```

- [ ] **Step 3: Use precomputedEmbedding in GraphRagService.retrieveSubgraph**

In `retrieveSubgraph()`, change:

```kotlin
// Old:
val queryVector = cachedEmbeddingService.embed(query.question)

// New:
val queryVector = query.precomputedEmbedding ?: cachedEmbeddingService.embed(query.question)
```

- [ ] **Step 4: Use precomputedEmbedding in DocumentRagService.retrieveChunks**

In `retrieveChunks()`, change:

```kotlin
// Old:
val queryVector = cachedEmbeddingService.embed(query.question)

// New:
val queryVector = query.precomputedEmbedding ?: cachedEmbeddingService.embed(query.question)
```

- [ ] **Step 5: Precompute embedding in NlpQueryService.route()**

In `src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt`:

Add constructor parameter and import:

```kotlin
import com.agentwork.graphmesh.query.CachedEmbeddingService

class NlpQueryService(
    private val promptExecutor: PromptExecutor,
    private val graphRagService: GraphRagService,
    private val documentRagService: DocumentRagService,
    private val quadStore: QuadStore,
    private val contentTypeService: CollectionContentTypeService,
    private val cachedEmbeddingService: CachedEmbeddingService,  // NEW
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val llmModelName: String
)
```

Change `route()` signature and body:

```kotlin
private fun route(question: String, intent: QueryIntent, collectionId: String): Pair<String, List<String>> {
    val embedding = cachedEmbeddingService.embed(question)

    return when (intent) {
        QueryIntent.GRAPH_QUERY -> {
            val result = graphRagService.query(GraphRagQuery(question, collectionId, precomputedEmbedding = embedding))
            val sources = result.selectedEdges.map { edge ->
                "${edge.subject} --[${edge.predicate}]--> ${edge.objectValue}"
            }
            result.answer to sources
        }

        QueryIntent.DOCUMENT_QUERY -> {
            val result = documentRagService.query(DocumentRagQuery(question, collectionId, precomputedEmbedding = embedding))
            val sources = result.sources.map { src ->
                "${src.documentTitle} (page ${src.pageNumber ?: "?"})"
            }
            result.answer to sources
        }

        QueryIntent.STRUCTURED_QUERY -> {
            val quads = quadStore.query(collectionId, QuadQuery())
            val text = quads.take(20).joinToString("\n") { q ->
                "${q.subject} --[${q.predicate}]--> ${q.objectValue}"
            }
            val answer = text.ifEmpty { "No matching triples found." }
            answer to quads.take(20).map { "${it.dataset}" }.distinct()
        }

        QueryIntent.HYBRID -> {
            val graphResult = graphRagService.query(GraphRagQuery(question, collectionId, precomputedEmbedding = embedding))
            val docResult = documentRagService.query(DocumentRagQuery(question, collectionId, precomputedEmbedding = embedding))

            val answer = """
                Based on the Knowledge Graph:
                ${graphResult.answer}

                Based on Documents:
                ${docResult.answer}
            """.trimIndent()

            val sources = graphResult.selectedEdges.map { edge ->
                "[Graph] ${edge.subject} --[${edge.predicate}]--> ${edge.objectValue}"
            } + docResult.sources.map { src ->
                "[Document] ${src.documentTitle} (page ${src.pageNumber ?: "?"})"
            }

            answer to sources
        }
    }
}
```

- [ ] **Step 6: Build and run tests**

Run: `./gradlew test`

Expected: All PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagModels.kt \
        src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagModels.kt \
        src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt \
        src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt \
        src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt
git commit -m "perf(nlp): precompute embedding once and pass to delegated services"
```

---

### Task 5: Embedding parallel zur Intent-Detection (MIXED)

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt`

- [ ] **Step 1: Refactor query() to parallelize intent + embedding for MIXED collections**

In `NlpQueryService.kt`, add imports:

```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
```

Replace the intent detection + route section in `query()`. The full `query()` method becomes:

```kotlin
fun query(query: NlpQuery): NlpQueryResult {
    val startTime = System.currentTimeMillis()

    // Step 1: Intent Detection + Embedding (parallel when MIXED)
    val (detectedIntent, precomputedEmbedding) = when {
        query.forceIntent != null -> {
            DetectedIntent(query.forceIntent, 1.0, "Intent forced by caller") to
                cachedEmbeddingService.embed(query.question)
        }
        !contentTypeService.hasDocuments(query.collectionId) -> {
            logger.info("Collection has only graph data, skipping intent detection")
            DetectedIntent(QueryIntent.GRAPH_QUERY, 1.0, "Collection contains only graph data") to
                cachedEmbeddingService.embed(query.question)
        }
        !contentTypeService.hasTriples(query.collectionId) -> {
            logger.info("Collection has only documents, skipping intent detection")
            DetectedIntent(QueryIntent.DOCUMENT_QUERY, 1.0, "Collection contains only documents") to
                cachedEmbeddingService.embed(query.question)
        }
        else -> {
            logger.info("Mixed collection, detecting intent in parallel with embedding")
            runBlocking {
                coroutineScope {
                    val intentDeferred = async { detectIntent(query.question) }
                    val embeddingDeferred = async { cachedEmbeddingService.embed(query.question) }
                    intentDeferred.await() to embeddingDeferred.await()
                }
            }
        }
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

    // Step 3: Route to appropriate service (embedding already computed)
    val (answer, sources) = route(effectiveQuestion, detectedIntent.intent, query.collectionId, precomputedEmbedding)

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

Update `route()` to accept the precomputed embedding as parameter:

```kotlin
private fun route(
    question: String,
    intent: QueryIntent,
    collectionId: String,
    precomputedEmbedding: FloatArray
): Pair<String, List<String>> {
    return when (intent) {
        QueryIntent.GRAPH_QUERY -> {
            val result = graphRagService.query(GraphRagQuery(question, collectionId, precomputedEmbedding = precomputedEmbedding))
            val sources = result.selectedEdges.map { edge ->
                "${edge.subject} --[${edge.predicate}]--> ${edge.objectValue}"
            }
            result.answer to sources
        }

        QueryIntent.DOCUMENT_QUERY -> {
            val result = documentRagService.query(DocumentRagQuery(question, collectionId, precomputedEmbedding = precomputedEmbedding))
            val sources = result.sources.map { src ->
                "${src.documentTitle} (page ${src.pageNumber ?: "?"})"
            }
            result.answer to sources
        }

        QueryIntent.STRUCTURED_QUERY -> {
            val quads = quadStore.query(collectionId, QuadQuery())
            val text = quads.take(20).joinToString("\n") { q ->
                "${q.subject} --[${q.predicate}]--> ${q.objectValue}"
            }
            val answer = text.ifEmpty { "No matching triples found." }
            answer to quads.take(20).map { "${it.dataset}" }.distinct()
        }

        QueryIntent.HYBRID -> {
            val graphResult = graphRagService.query(GraphRagQuery(question, collectionId, precomputedEmbedding = precomputedEmbedding))
            val docResult = documentRagService.query(DocumentRagQuery(question, collectionId, precomputedEmbedding = precomputedEmbedding))

            val answer = """
                Based on the Knowledge Graph:
                ${graphResult.answer}

                Based on Documents:
                ${docResult.answer}
            """.trimIndent()

            val sources = graphResult.selectedEdges.map { edge ->
                "[Graph] ${edge.subject} --[${edge.predicate}]--> ${edge.objectValue}"
            } + docResult.sources.map { src ->
                "[Document] ${src.documentTitle} (page ${src.pageNumber ?: "?"})"
            }

            answer to sources
        }
    }
}
```

- [ ] **Step 2: Build and run tests**

Run: `./gradlew test`

Expected: All PASS

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt
git commit -m "perf(nlp): parallelize intent detection and embedding for mixed collections"
```

---

### Task 6: HYBRID parallel

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt`

- [ ] **Step 1: Parallelize HYBRID branch in route()**

In `NlpQueryService.kt`, replace the HYBRID branch in `route()`:

```kotlin
QueryIntent.HYBRID -> {
    val (graphResult, docResult) = runBlocking {
        coroutineScope {
            val graph = async {
                graphRagService.query(GraphRagQuery(question, collectionId, precomputedEmbedding = precomputedEmbedding))
            }
            val doc = async {
                documentRagService.query(DocumentRagQuery(question, collectionId, precomputedEmbedding = precomputedEmbedding))
            }
            graph.await() to doc.await()
        }
    }

    val answer = """
        Based on the Knowledge Graph:
        ${graphResult.answer}

        Based on Documents:
        ${docResult.answer}
    """.trimIndent()

    val sources = graphResult.selectedEdges.map { edge ->
        "[Graph] ${edge.subject} --[${edge.predicate}]--> ${edge.objectValue}"
    } + docResult.sources.map { src ->
        "[Document] ${src.documentTitle} (page ${src.pageNumber ?: "?"})"
    }

    answer to sources
}
```

- [ ] **Step 2: Build and run tests**

Run: `./gradlew test`

Expected: All PASS

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt
git commit -m "perf(nlp): run GraphRAG and DocRAG in parallel for HYBRID queries"
```

---

### Task 7: Tests fuer neue Komponenten

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/query/CachedEmbeddingServiceTest.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/query/CollectionContentTypeServiceTest.kt`
- Modify: `src/test/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryServiceTest.kt`

- [ ] **Step 1: Create CachedEmbeddingServiceTest**

Create `src/test/kotlin/com/agentwork/graphmesh/query/CachedEmbeddingServiceTest.kt`:

```kotlin
package com.agentwork.graphmesh.query

import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.extraction.embedding.EmbeddingConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CachedEmbeddingServiceTest {

    private var callCount = 0

    private val mockProvider = object : LLMEmbeddingProvider {
        override suspend fun embed(text: String, model: LLModel): List<Double> {
            callCount++
            return listOf(1.0, 2.0, 3.0)
        }
    }

    private val service = CachedEmbeddingService(
        embeddingProvider = mockProvider,
        embeddingConfig = EmbeddingConfig(model = "text-embedding-3-small")
    )

    @Test
    fun `embed returns correct vector`() {
        val result = service.embed("hello")
        assertContentEquals(floatArrayOf(1.0f, 2.0f, 3.0f), result)
    }

    @Test
    fun `embed caches result for same text`() {
        callCount = 0
        service.embed("same question")
        service.embed("same question")
        assertEquals(1, callCount, "Should call provider only once for same text")
    }

    @Test
    fun `embed calls provider for different texts`() {
        callCount = 0
        service.embed("question one")
        service.embed("question two")
        assertEquals(2, callCount, "Should call provider for each distinct text")
    }
}
```

- [ ] **Step 2: Create CollectionContentTypeServiceTest**

Create `src/test/kotlin/com/agentwork/graphmesh/query/CollectionContentTypeServiceTest.kt`:

```kotlin
package com.agentwork.graphmesh.query

import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.storage.InMemoryQuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectionContentTypeServiceTest {

    private val quadStore = InMemoryQuadStore()
    private val librarianService = mockk<LibrarianService>()
    private val service = CollectionContentTypeService(quadStore, librarianService)

    @Test
    fun `hasTriples returns true when quads exist`() {
        every { librarianService.findByCollection(any(), any()) } returns emptyList()
        quadStore.insert("col1", StoredQuad("s", "p", "o", ""))
        assertTrue(service.hasTriples("col1"))
    }

    @Test
    fun `hasTriples returns false for empty collection`() {
        every { librarianService.findByCollection(any(), any()) } returns emptyList()
        assertFalse(service.hasTriples("empty"))
    }

    @Test
    fun `hasDocuments returns true when documents exist`() {
        every { librarianService.findByCollection("col1", any()) } returns listOf(mockk())
        assertTrue(service.hasDocuments("col1"))
    }

    @Test
    fun `hasDocuments returns false for empty collection`() {
        every { librarianService.findByCollection("empty", any()) } returns emptyList()
        assertFalse(service.hasDocuments("empty"))
    }

    @Test
    fun `isMixed returns true when both exist`() {
        quadStore.insert("mixed", StoredQuad("s", "p", "o", ""))
        every { librarianService.findByCollection("mixed", any()) } returns listOf(mockk())
        assertTrue(service.isMixed("mixed"))
    }

    @Test
    fun `invalidate clears cache so next call re-checks`() {
        every { librarianService.findByCollection(any(), any()) } returns emptyList()
        // First call: empty
        assertFalse(service.hasTriples("col2"))
        // Insert data
        quadStore.insert("col2", StoredQuad("s", "p", "o", ""))
        // Still cached as false
        assertFalse(service.hasTriples("col2"))
        // Invalidate
        service.invalidate("col2")
        // Now re-checks
        assertTrue(service.hasTriples("col2"))
    }
}
```

- [ ] **Step 3: Run all tests**

Run: `./gradlew test`

Expected: All PASS

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/query/CachedEmbeddingServiceTest.kt \
        src/test/kotlin/com/agentwork/graphmesh/query/CollectionContentTypeServiceTest.kt
git commit -m "test(query): add tests for CachedEmbeddingService and CollectionContentTypeService"
```
