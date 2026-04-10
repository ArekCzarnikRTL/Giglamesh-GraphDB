# RDF Import Embeddings + Graph RAG Dual-Retrieval — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable `generateEmbeddings=true` on RDF import to create entity-based vector embeddings, and extend Graph RAG to retrieve triples via entity URIs alongside the existing chunk-based path.

**Architecture:** RdfImportService gets new dependencies (LLMEmbeddingProvider, VectorStore, EmbeddingConfig). After inserting triples, it groups them by subject URI, builds readable text per entity, generates embeddings, and stores them in Qdrant with `entity_uri` payload. GraphRagService.retrieveSubgraph() splits vector search results into chunk-based hits (existing path) and entity-based hits (new path via QuadStore.findByEntities), then merges both.

**Tech Stack:** Kotlin, Spring Boot, Koog LLMEmbeddingProvider, Qdrant (VectorStore), Apache Jena, MockK for tests

---

### Task 1: Add `embeddingsGenerated` to GraphQL schema and result types

**Files:**
- Modify: `src/main/resources/graphql/rdf-import.graphqls`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt` (ImportResult)
- Modify: `src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportController.kt` (ImportRdfResultPayload)

- [ ] **Step 1: Update GraphQL schema**

Add `embeddingsGenerated` field to `ImportRdfResult` in `src/main/resources/graphql/rdf-import.graphqls`:

```graphql
type ImportRdfResult {
    tripleCount: Int!
    skippedCount: Int!
    durationMs: Int!
    embeddingsGenerated: Int!
}
```

- [ ] **Step 2: Update ImportResult data class**

In `src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt`, add field to `ImportResult`:

```kotlin
data class ImportResult(
    val tripleCount: Int,
    val skippedCount: Int,
    val durationMs: Long,
    val embeddingsGenerated: Int = 0,
)
```

- [ ] **Step 3: Update ImportRdfResultPayload**

In `src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportController.kt`, add field to `ImportRdfResultPayload`:

```kotlin
data class ImportRdfResultPayload(
    val tripleCount: Int,
    val skippedCount: Int,
    val durationMs: Long,
    val embeddingsGenerated: Int,
)
```

And update the mapping in `importRdf()`:

```kotlin
return ImportRdfResultPayload(
    tripleCount = result.tripleCount,
    skippedCount = result.skippedCount,
    durationMs = result.durationMs,
    embeddingsGenerated = result.embeddingsGenerated,
)
```

- [ ] **Step 4: Pass generateEmbeddings to service**

In `RdfImportController.importRdf()`, pass the flag:

```kotlin
val result = rdfImportService.importRdf(
    collectionId = input.collectionId,
    content = content,
    format = input.format,
    dataset = input.dataset,
    generateEmbeddings = input.generateEmbeddings,
)
```

- [ ] **Step 5: Update service method signature**

In `RdfImportService.importRdf()`, add parameter (default false, no logic yet):

```kotlin
fun importRdf(
    collectionId: String,
    content: String,
    format: RdfFormat,
    dataset: String?,
    generateEmbeddings: Boolean = false,
): ImportResult {
```

- [ ] **Step 6: Verify compilation**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL (tests may need fixing for new constructor arg)

- [ ] **Step 7: Fix existing tests**

In `src/test/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportControllerTest.kt`, the mock return now needs `embeddingsGenerated`:

```kotlin
every {
    service.importRdf("coll-1", capture(contentSlot), RdfFormat.TURTLE, null, false)
} returns RdfImportService.ImportResult(tripleCount = 1, skippedCount = 0, durationMs = 42, embeddingsGenerated = 0)
```

Update both test methods similarly. Also assert `embeddingsGenerated`:

```kotlin
assertEquals(0, result.embeddingsGenerated)
```

- [ ] **Step 8: Run tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.rdfimport.*"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/graphql/rdf-import.graphqls \
  src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt \
  src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportController.kt \
  src/test/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportControllerTest.kt
git commit -m "feat(rdf-import): add embeddingsGenerated field and generateEmbeddings passthrough"
```

---

### Task 2: Implement entity text builder and embedding generation in RdfImportService

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt`
- Modify: `src/test/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportServiceTest.kt`

- [ ] **Step 1: Write test for buildEntityText**

Add to `src/test/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportServiceTest.kt`:

```kotlin
@Test
fun `buildEntityText creates readable text from triples`() {
    val triples = listOf(
        StoredQuad(
            subject = "http://example.org/Alice",
            predicate = "http://example.org/knows",
            objectValue = "http://example.org/Bob",
            dataset = "", objectType = ObjectType.URI
        ),
        StoredQuad(
            subject = "http://example.org/Alice",
            predicate = "http://example.org/name",
            objectValue = "Alice Mueller",
            dataset = "", objectType = ObjectType.LITERAL
        ),
    )

    val text = RdfImportService.buildEntityText("http://example.org/Alice", triples)

    assertTrue(text.contains("Alice"))
    assertTrue(text.contains("knows"))
    assertTrue(text.contains("Bob"))
    assertTrue(text.contains("Alice Mueller"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.rdfimport.RdfImportServiceTest.buildEntityText creates readable text from triples"`
Expected: FAIL — `buildEntityText` does not exist

- [ ] **Step 3: Implement buildEntityText**

Add to `RdfImportService` as a companion object function:

```kotlin
companion object {
    fun buildEntityText(subjectUri: String, triples: List<StoredQuad>): String {
        val localName = extractLocalName(subjectUri)
        return triples.joinToString(". ") { quad ->
            val predName = extractLocalName(quad.predicate)
            val objName = if (quad.objectType == ObjectType.URI) {
                extractLocalName(quad.objectValue)
            } else {
                quad.objectValue
            }
            "$localName $predName $objName"
        }
    }

    fun extractLocalName(uri: String): String {
        val fragment = uri.substringAfterLast('#', "")
        if (fragment.isNotEmpty()) return fragment
        return uri.substringAfterLast('/')
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.rdfimport.RdfImportServiceTest.buildEntityText creates readable text from triples"`
Expected: PASS

- [ ] **Step 5: Write test for embedding generation with generateEmbeddings=true**

Add to test file. The service constructor needs new dependencies. Update the test class:

```kotlin
import com.agentwork.graphmesh.extraction.embedding.EmbeddingConfig
import com.agentwork.graphmesh.storage.vector.VectorPoint
import com.agentwork.graphmesh.storage.vector.VectorStore
import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

// Replace the service construction with:
private val embeddingProvider = mockk<LLMEmbeddingProvider>()
private val vectorStore = mockk<VectorStore>(relaxed = true)
private val embeddingConfig = EmbeddingConfig(model = "text-embedding-3-small")
private val service = RdfImportService(jenaAdapter, quadStore, embeddingProvider, vectorStore, embeddingConfig)
```

Then the test:

```kotlin
@Test
fun `generateEmbeddings creates entity embeddings in vector store`() {
    val turtle = """
        @prefix ex: <http://example.org/> .
        ex:Alice ex:knows ex:Bob .
        ex:Alice ex:name "Alice" .
    """.trimIndent()

    coEvery { embeddingProvider.embed(any(), any()) } returns listOf(0.1, 0.2, 0.3)

    val result = service.importRdf(collectionId, turtle, RdfFormat.TURTLE, null, generateEmbeddings = true)

    assertEquals(2, result.tripleCount)
    assertTrue(result.embeddingsGenerated > 0)

    val pointsSlot = slot<List<VectorPoint>>()
    verify { vectorStore.upsert(collectionId, capture(pointsSlot)) }
    val points = pointsSlot.captured
    assertTrue(points.any { it.payload["entity_uri"] == "http://example.org/Alice" })
    assertTrue(points.any { it.payload["source"] == "rdf-import" })
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.rdfimport.RdfImportServiceTest.generateEmbeddings creates entity embeddings in vector store"`
Expected: FAIL — constructor mismatch or no embedding logic

- [ ] **Step 7: Add new dependencies to RdfImportService constructor**

Update `RdfImportService`:

```kotlin
import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import com.agentwork.graphmesh.extraction.embedding.EmbeddingConfig
import com.agentwork.graphmesh.llm.resolveLlmModel
import com.agentwork.graphmesh.storage.vector.VectorPoint
import com.agentwork.graphmesh.storage.vector.VectorStore
import kotlinx.coroutines.runBlocking

@Service
class RdfImportService(
    private val jenaAdapter: JenaAdapter,
    private val quadStore: QuadStore,
    private val embeddingProvider: LLMEmbeddingProvider,
    private val vectorStore: VectorStore,
    private val embeddingConfig: EmbeddingConfig,
) {
```

- [ ] **Step 8: Implement embedding generation in importRdf**

After `quadStore.insertBatch(collectionId, quads)`, add:

```kotlin
var embeddingsGenerated = 0
if (generateEmbeddings && quads.isNotEmpty()) {
    embeddingsGenerated = generateEntityEmbeddings(collectionId, quads)
}
```

And update the return:

```kotlin
return ImportResult(
    tripleCount = imported,
    skippedCount = skipped,
    durationMs = System.currentTimeMillis() - start,
    embeddingsGenerated = embeddingsGenerated,
)
```

Add the method:

```kotlin
private fun generateEntityEmbeddings(collectionId: String, quads: List<StoredQuad>): Int {
    val bySubject = quads.groupBy { it.subject }
    val model = resolveLlmModel(embeddingConfig.model)

    val points = mutableListOf<VectorPoint>()
    for ((subjectUri, triples) in bySubject) {
        val text = buildEntityText(subjectUri, triples)
        if (text.isBlank()) continue
        try {
            val embedding = runBlocking { embeddingProvider.embed(text, model) }
            val vector = FloatArray(embedding.size) { embedding[it].toFloat() }
            points += VectorPoint(
                id = subjectUri,
                vector = vector,
                payload = mapOf(
                    "entity_uri" to subjectUri,
                    "source" to "rdf-import",
                    "collection" to collectionId,
                )
            )
        } catch (e: Exception) {
            logger.warn("Failed to embed entity '{}': {}", subjectUri, e.message)
        }
    }

    if (points.isNotEmpty()) {
        vectorStore.upsert(collectionId, points)
    }

    logger.info("Generated {} entity embeddings for collection '{}'", points.size, collectionId)
    return points.size
}
```

- [ ] **Step 9: Run all rdfimport tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.rdfimport.*"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Write test for generateEmbeddings=false (default, no vectorStore call)**

```kotlin
@Test
fun `importRdf without generateEmbeddings does not call vectorStore`() {
    val turtle = """
        @prefix ex: <http://example.org/> .
        ex:Alice ex:knows ex:Bob .
    """.trimIndent()

    val result = service.importRdf(collectionId, turtle, RdfFormat.TURTLE, null)

    assertEquals(0, result.embeddingsGenerated)
    verify(exactly = 0) { vectorStore.upsert(any(), any()) }
}
```

- [ ] **Step 11: Run tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.rdfimport.*"`
Expected: PASS

- [ ] **Step 12: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt \
  src/test/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportServiceTest.kt
git commit -m "feat(rdf-import): generate entity embeddings when generateEmbeddings=true"
```

---

### Task 3: Extend GraphRagService with dual retrieval path

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`
- Modify: `src/test/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagServiceTest.kt`

- [ ] **Step 1: Write test for entity URI extraction from search results**

Add to `src/test/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagServiceTest.kt`:

```kotlin
@Test
fun `splitSearchResults separates chunk and entity hits`() {
    val results = listOf(
        SearchResult(id = "chunk-1", score = 0.9f, payload = mapOf("chunk_id" to "doc-1/p1/c1")),
        SearchResult(id = "entity-1", score = 0.85f, payload = mapOf("entity_uri" to "http://example.org/Alice", "source" to "rdf-import")),
        SearchResult(id = "chunk-2", score = 0.8f, payload = mapOf("chunk_id" to "doc-1/p1/c2")),
        SearchResult(id = "entity-2", score = 0.75f, payload = mapOf("entity_uri" to "http://example.org/Bob", "source" to "rdf-import")),
    )

    val chunkIds = results.mapNotNull { it.payload["chunk_id"]?.toString() }
    val entityUris = results.mapNotNull { it.payload["entity_uri"]?.toString() }

    assertEquals(listOf("doc-1/p1/c1", "doc-1/p1/c2"), chunkIds)
    assertEquals(listOf("http://example.org/Alice", "http://example.org/Bob"), entityUris)
}
```

Add required import:
```kotlin
import com.agentwork.graphmesh.storage.vector.SearchResult
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.query.graphrag.GraphRagServiceTest.splitSearchResults separates chunk and entity hits"`
Expected: PASS (this is a logic validation test, no service needed)

- [ ] **Step 3: Modify retrieveSubgraph in GraphRagService**

Replace the `retrieveSubgraph` method in `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`:

```kotlin
private fun retrieveSubgraph(query: GraphRagQuery): List<StoredQuad> {
    val embeddingModel = resolveLlmModel(embeddingConfig.model)

    val embedding = runBlocking {
        embeddingProvider.embed(query.question, embeddingModel)
    }
    val queryVector = FloatArray(embedding.size) { embedding[it].toFloat() }

    val searchResults = vectorStore.search(
        collection = query.collectionId,
        queryVector = queryVector,
        limit = 50
    )

    // Split results into chunk-based and entity-based hits
    val chunkUrns = searchResults
        .mapNotNull { it.payload["chunk_id"]?.toString() }
        .map { "urn:chunk:$it" }
        .distinct()

    val entityUris = searchResults
        .mapNotNull { it.payload["entity_uri"]?.toString() }
        .distinct()

    // Path 1: Chunk-based retrieval (existing provenance path)
    val chunkTriples = if (chunkUrns.isNotEmpty()) {
        val subgraphUris = quadStore.findSubgraphsForChunks(query.collectionId, chunkUrns)
        if (subgraphUris.isNotEmpty()) {
            quadStore.findQuotedTriplesForSubgraphs(query.collectionId, subgraphUris)
        } else {
            emptyList()
        }
    } else {
        emptyList()
    }

    // Path 2: Entity-based retrieval (RDF import path)
    val entityTriples = if (entityUris.isNotEmpty()) {
        quadStore.findByEntities(query.collectionId, entityUris)
    } else {
        emptyList()
    }

    if (chunkUrns.isEmpty() && entityUris.isEmpty()) {
        logger.debug("retrieveSubgraph: no chunk_ids or entity_uris in vector hits")
        return emptyList()
    }

    // 1-hop entity expansion on chunk triples (existing behavior)
    val chunkEntityUris = collectEntityUris(chunkTriples)
    val expandedEdges = if (chunkEntityUris.isNotEmpty()) {
        quadStore.findByEntities(query.collectionId, chunkEntityUris)
    } else {
        emptyList()
    }

    logger.debug(
        "retrieveSubgraph: {} chunks → {} chunk triples, {} entity URIs → {} entity triples, {} expanded edges",
        chunkUrns.size, chunkTriples.size, entityUris.size, entityTriples.size, expandedEdges.size
    )

    return (chunkTriples + entityTriples + expandedEdges).distinct().take(query.maxEdges)
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run all existing Graph RAG tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.query.graphrag.*"`
Expected: PASS (existing tests don't use retrieveSubgraph directly)

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt \
  src/test/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagServiceTest.kt
git commit -m "feat(graph-rag): add entity-URI retrieval path for RDF-imported data"
```

---

### Task 4: Update smoke test and verify full build

**Files:**
- Modify: `ontology-smoke-test.sh`

- [ ] **Step 1: Update smoke test import to use generateEmbeddings**

In `ontology-smoke-test.sh`, update the `importRdf` mutation variables (around the Step 9 section) to include `generateEmbeddings: true`:

Change the `jq` construction to:

```bash
RESP=$(gql 'mutation($input: ImportRdfInput!) {
  importRdf(input: $input) { tripleCount skippedCount durationMs embeddingsGenerated }
}' "$(jq -cn \
  --arg cid "$RDF_COLLECTION_ID" \
  --arg content "$B64_RDF" \
  --arg format "$RDF_FORMAT" \
  '{input: {collectionId: $cid, content: $content, format: $format, generateEmbeddings: true}}')")
```

Update the success output to include embeddings:

```bash
EMBEDDINGS=$(echo "$RESP" | jq '.data.importRdf.embeddingsGenerated // 0')
if [[ "$TRIPLE_COUNT" -gt 0 ]]; then
  ok "RDF-Import: $TRIPLE_COUNT Triples, $SKIPPED uebersprungen, $EMBEDDINGS Embeddings, ${DURATION}ms ($RDF_SIZE Bytes)"
```

- [ ] **Step 2: Run full Gradle build**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (all tests pass)

- [ ] **Step 3: Commit**

```bash
git add ontology-smoke-test.sh
git commit -m "feat(smoke-test): enable generateEmbeddings in RDF import smoke test"
```
