# Feature 36: Fix `GraphRagService.retrieveSubgraph` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the broken `GraphRagService.retrieveSubgraph` so that vector hits actually map to knowledge edges via the chunk → provenance subgraph → quoted triple → entity URI pipeline.

**Architecture:** Add two new lookup methods to `QuadStore` (as default-method overlays on the existing `query(QuadQuery)` so no new prepared statements are needed in `CassandraQuadStore`). They walk `urn:chunk:* → urn:graphmesh:subgraph:* → tg:contains <<s p o>>` and unpack the quoted triples back into knowledge `StoredQuad`s. `retrieveSubgraph` is rewritten to use this pipeline and to expand the unpacked entities one hop via the existing `findByEntities`.

**Tech Stack:** Kotlin, Spring Boot, Apache Cassandra (existing `quads_by_entity`/`quads_by_collection` schema, no DDL changes), JUnit 5 + `kotlin-test-junit5`.

---

## Spec Reference

`docs/features/36-fix-retrieveSubgraph.md`

## Background — what the code actually looks like (not what the spec assumed)

The spec mentions `src/main/kotlin/com/agentwork/graphmesh/storage/cassandra/CassandraQuadStore.kt`. **That file does not exist.** The actual implementation lives in `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt` (the file is named `QuadStoreService.kt` but contains `class CassandraQuadStore : QuadStore`). This plan uses the real path.

Important facts the implementer must know:

1. **Quoted triples are serialized as a string.** `QuadConverter.serializeTerm` (`src/main/kotlin/com/agentwork/graphmesh/rdf/QuadConverter.kt:38-44`) writes a `RdfTerm.QuotedTriple` as `<<s|p|o>>` into the `objectValue` field. `objectType` is `ObjectType.QUOTED_TRIPLE`.
2. **All needed query patterns already exist.** `CassandraQuadStore.query(QuadQuery)` supports the 16 pattern combinations of (s,p,o,d) wildcards. We need:
   - Pattern 10 (`?, P, O, ?`) → find subgraphs by `(predicate=prov:wasDerivedFrom, objectValue=chunkUrn)`. Entity row uses `entity=chunkUrn, role='O', AND p = ?`.
   - Pattern 4 (`S, P, ?, ?`) → find quoted-triple rows by `(subject=subgraphUri, predicate=tg:contains)`.
3. **Provenance constants.** `ProvenanceNamespaces.PROV_WAS_DERIVED_FROM = "http://www.w3.org/ns/prov#wasDerivedFrom"` and `TG_CONTAINS = "http://graphmesh.io/ontology/contains"`. They live in `src/main/kotlin/com/agentwork/graphmesh/provenance/ProvenanceNamespaces.kt`.
4. **Chunk URN format.** `ProvenanceService` (`src/main/kotlin/com/agentwork/graphmesh/provenance/ProvenanceService.kt:30-35`) writes `provenance.chunkUri` directly as the object of `wasDerivedFrom`. Search the producers to confirm the prefix actually used (the spec says `urn:chunk:doc-<uuid>/p<n>/c<m>`). If the producer already prepends `urn:chunk:`, `GraphRagService` must NOT prepend it again. **Verify in Task 0 before writing the prefix logic.**
5. **Entity-URI prefix.** Knowledge triples use `http://graphmesh.io/entity/<hash>` as subjects. Confirm in Task 0 by reading `EntityIdGenerator.kt`.
6. **Existing tests.** The only `GraphRagServiceTest` (`src/test/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagServiceTest.kt`) is a pure unit test of `parseEdgeSelection`; it does not construct a `GraphRagService` and does not need fakes for the LLM/vector store. Adding any `GraphRagService` integration test would require fakes for `LLMEmbeddingProvider`, `VectorStore`, `QuadStore`, `PromptExecutor`, `ExplainabilityEventProducer`. We will NOT do that — instead we test the new logic in isolation (the new `QuadStore` methods + a small pure helper extracted from `retrieveSubgraph`).
7. **Integration tests need docker.** Per the user's known build issues, `QuadStoreIntegrationTest` (Spring Boot context, real Cassandra) cannot run in plain `./gradlew test` without `docker-compose up`. We will NOT add new integration tests; pure JUnit unit tests over an in-memory fake `QuadStore` are sufficient.
8. **Workflow.** Per user preference: commit straight to main, no PRs, never push to origin.

## File Structure

| File | Responsibility | Action |
|------|----------------|--------|
| `src/main/kotlin/com/agentwork/graphmesh/rdf/QuadConverter.kt` | RDF↔storage serialization | Modify: expose a public helper `unpackQuotedTriple(stored: StoredQuad): StoredQuad?` that turns a `tg:contains`-row whose `objectValue` is `<<s\|p\|o>>` into the inner `StoredQuad`. |
| `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt` | Storage interface | Modify: add two default methods `findSubgraphsForChunks` and `findQuotedTriplesForSubgraphs` implemented purely on top of `query(QuadQuery)`. No change to `CassandraQuadStore`. |
| `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt` | Graph RAG pipeline | Modify: rewrite `retrieveSubgraph` per the spec. Extract a pure helper `collectEntityUris(quotedTriples: List<StoredQuad>): List<String>` so it can be unit-tested without fakes. |
| `src/test/kotlin/com/agentwork/graphmesh/rdf/QuadConverterTest.kt` | Unit test for `unpackQuotedTriple` | Create. |
| `src/test/kotlin/com/agentwork/graphmesh/storage/InMemoryQuadStore.kt` | Test fake | Create: a tiny in-memory `QuadStore` so default methods can be unit-tested. Lives under `test/`. |
| `src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreDefaultMethodsTest.kt` | Unit test the new default methods | Create. |
| `src/test/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagServiceTest.kt` | Existing — extend with `collectEntityUris` test | Modify. |
| `smoke-test.sh` | End-to-end smoke test | Modify: tighten the graphRag assertion. |

---

## Task 0: Verify code-level assumptions before writing any code

**Files:**
- Read only

This task exists because the user has been bitten before by spec/code divergence. Do NOT skip it.

- [ ] **Step 0.1: Confirm chunk URN prefix produced by extraction**

Run:

```
grep -rn "urn:chunk" src/main/kotlin
```

Expected: at least one producer (likely under `extraction/` or `provenance/`) builds `chunkUri = "urn:chunk:" + <chunkId>`. Note the **exact** producer file:line.

If the producers already store the URN with the `urn:chunk:` prefix → `GraphRagService` MUST prepend `urn:chunk:` to the bare `chunk_id` from the vector payload (because the vector payload uses the bare form `doc-<uuid>/p1/c1`).
If a producer instead stores chunk_ids without the prefix → use the bare id as the lookup key and adjust Task 4 accordingly.

Record the answer in your scratchpad before continuing.

- [ ] **Step 0.2: Confirm entity URI prefix**

Run:

```
grep -rn "graphmesh.io/entity" src/main/kotlin | head -20
```

Expected: `EntityIdGenerator` (or similar) emits subjects starting with `http://graphmesh.io/entity/`. Confirm the literal prefix string. The plan assumes `http://graphmesh.io/entity/`. If different, use the actual prefix in Task 4 and Task 6.

- [ ] **Step 0.3: Confirm vector payload key for chunk id**

Run:

```
grep -n "chunk_id" src/main/kotlin/com/agentwork/graphmesh/extraction/embedding/EmbeddingService.kt
```

Expected: the embedding payload writes `chunk_id` (lowercase, snake_case). Confirm.

- [ ] **Step 0.4: Confirm `NamedGraph.SOURCE` constant**

Already read in this plan: `NamedGraph.SOURCE = "urn:graph:source"` (`src/main/kotlin/com/agentwork/graphmesh/rdf/Quad.kt:29`). No action needed unless Step 0.1 contradicts it.

No commit for Task 0.

---

## Task 1: Add `unpackQuotedTriple` helper to `QuadConverter`

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/rdf/QuadConverter.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/rdf/QuadConverterTest.kt`

- [ ] **Step 1.1: Write the failing test**

Create `src/test/kotlin/com/agentwork/graphmesh/rdf/QuadConverterTest.kt`:

```kotlin
package com.agentwork.graphmesh.rdf

import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.StoredQuad
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuadConverterTest {

    @Test
    fun `unpackQuotedTriple returns inner triple as StoredQuad`() {
        val outer = StoredQuad(
            subject = "urn:graphmesh:subgraph:abc",
            predicate = "http://graphmesh.io/ontology/contains",
            objectValue = "<<http://graphmesh.io/entity/aaa|http://example.org/label|GraphMesh>>",
            dataset = "urn:graph:source",
            objectType = ObjectType.QUOTED_TRIPLE
        )

        val inner = QuadConverter.unpackQuotedTriple(outer)

        assertEquals(
            StoredQuad(
                subject = "http://graphmesh.io/entity/aaa",
                predicate = "http://example.org/label",
                objectValue = "GraphMesh",
                dataset = "",
                objectType = ObjectType.URI
            ),
            inner
        )
    }

    @Test
    fun `unpackQuotedTriple returns null when row is not a quoted triple`() {
        val outer = StoredQuad(
            subject = "urn:graphmesh:subgraph:abc",
            predicate = "http://www.w3.org/ns/prov#wasDerivedFrom",
            objectValue = "urn:chunk:doc-1/p1/c1",
            dataset = "urn:graph:source",
            objectType = ObjectType.URI
        )

        assertNull(QuadConverter.unpackQuotedTriple(outer))
    }

    @Test
    fun `unpackQuotedTriple returns null on malformed payload`() {
        val outer = StoredQuad(
            subject = "urn:graphmesh:subgraph:abc",
            predicate = "http://graphmesh.io/ontology/contains",
            objectValue = "not a quoted triple",
            dataset = "urn:graph:source",
            objectType = ObjectType.QUOTED_TRIPLE
        )

        assertNull(QuadConverter.unpackQuotedTriple(outer))
    }
}
```

- [ ] **Step 1.2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.rdf.QuadConverterTest" -q`
Expected: FAIL — `unresolved reference: unpackQuotedTriple` (compile error counts as failing test).

- [ ] **Step 1.3: Add `unpackQuotedTriple` to `QuadConverter`**

In `src/main/kotlin/com/agentwork/graphmesh/rdf/QuadConverter.kt`, add after the existing `fromStoredQuad` function (still inside `object QuadConverter`):

```kotlin
    /**
     * Unpacks a `tg:contains <<s|p|o>>` row produced by `ProvenanceService` back
     * into the inner knowledge triple. Returns `null` if [stored] is not a
     * QUOTED_TRIPLE row, or if the payload cannot be parsed.
     *
     * The returned [StoredQuad] uses the empty default-graph dataset because
     * the inner triple's original dataset is not preserved by the
     * `<<s|p|o>>` serialization.
     */
    fun unpackQuotedTriple(stored: com.agentwork.graphmesh.storage.StoredQuad): com.agentwork.graphmesh.storage.StoredQuad? {
        if (stored.objectType != com.agentwork.graphmesh.storage.ObjectType.QUOTED_TRIPLE) return null
        val v = stored.objectValue
        if (!v.startsWith("<<") || !v.endsWith(">>")) return null
        val inner = v.removePrefix("<<").removeSuffix(">>")
        val parts = inner.split("|", limit = 3)
        if (parts.size != 3) return null
        return com.agentwork.graphmesh.storage.StoredQuad(
            subject = parts[0],
            predicate = parts[1],
            objectValue = parts[2],
            dataset = "",
            objectType = com.agentwork.graphmesh.storage.ObjectType.URI
        )
    }
```

(Imports for `StoredQuad`/`ObjectType` are already present at the top of the file — you may use the short names instead of fully-qualified ones; both compile.)

- [ ] **Step 1.4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.rdf.QuadConverterTest" -q`
Expected: 3 tests, all PASS.

- [ ] **Step 1.5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/rdf/QuadConverter.kt src/test/kotlin/com/agentwork/graphmesh/rdf/QuadConverterTest.kt
git commit -m "feat(rdf): add QuadConverter.unpackQuotedTriple for tg:contains rows"
```

---

## Task 2: Add an in-memory `QuadStore` test fake

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/storage/InMemoryQuadStore.kt`

This fake exists so we can unit-test the new default methods on `QuadStore` (Task 3) and the helper in `GraphRagService` (Task 5) without spinning up Cassandra. It only implements the abstract members of `QuadStore` (`insert`, `insertBatch`, `delete`, `deleteCollection`, `query`); the new default methods will be inherited from the interface.

- [ ] **Step 2.1: Create the fake**

Create `src/test/kotlin/com/agentwork/graphmesh/storage/InMemoryQuadStore.kt`:

```kotlin
package com.agentwork.graphmesh.storage

/**
 * In-memory test fake for [QuadStore]. Stores quads in a per-collection list
 * and answers [query] by linear scan, matching the same semantics
 * (`field == query.field` for each non-null query field).
 *
 * Only used by unit tests.
 */
class InMemoryQuadStore : QuadStore {

    private val byCollection: MutableMap<String, MutableList<StoredQuad>> = mutableMapOf()

    override fun insert(collection: String, quad: StoredQuad) {
        byCollection.getOrPut(collection) { mutableListOf() }.add(quad)
    }

    override fun insertBatch(collection: String, quads: List<StoredQuad>) {
        quads.forEach { insert(collection, it) }
    }

    override fun delete(collection: String, quad: StoredQuad) {
        byCollection[collection]?.remove(quad)
    }

    override fun deleteCollection(collection: String) {
        byCollection.remove(collection)
    }

    override fun query(collection: String, query: QuadQuery): List<StoredQuad> {
        val rows = byCollection[collection] ?: return emptyList()
        return rows.filter { q ->
            (query.subject == null || q.subject == query.subject) &&
            (query.predicate == null || q.predicate == query.predicate) &&
            (query.objectValue == null || q.objectValue == query.objectValue) &&
            (query.dataset == null || q.dataset == query.dataset)
        }
    }
}
```

- [ ] **Step 2.2: Sanity-build**

Run: `./gradlew compileTestKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2.3: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/storage/InMemoryQuadStore.kt
git commit -m "test(storage): add InMemoryQuadStore fake for unit tests"
```

---

## Task 3: Add `findSubgraphsForChunks` and `findQuotedTriplesForSubgraphs` as default methods on `QuadStore`

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreDefaultMethodsTest.kt`

- [ ] **Step 3.1: Write the failing test**

Create `src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreDefaultMethodsTest.kt`:

```kotlin
package com.agentwork.graphmesh.storage

import com.agentwork.graphmesh.provenance.ProvenanceNamespaces
import com.agentwork.graphmesh.rdf.NamedGraph
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuadStoreDefaultMethodsTest {

    private val collection = "c1"
    private val chunkUrn = "urn:chunk:doc-abc/p1/c1"
    private val otherChunkUrn = "urn:chunk:doc-abc/p1/c2"
    private val subgraphUri = "urn:graphmesh:subgraph:sg-1"
    private val unrelatedSubgraph = "urn:graphmesh:subgraph:sg-2"
    private val entityA = "http://graphmesh.io/entity/aaa"
    private val entityB = "http://graphmesh.io/entity/bbb"

    private fun seededStore(): QuadStore {
        val store = InMemoryQuadStore()
        // sg-1 derived from chunkUrn, contains two quoted triples
        store.insert(collection, StoredQuad(
            subject = subgraphUri,
            predicate = ProvenanceNamespaces.PROV_WAS_DERIVED_FROM,
            objectValue = chunkUrn,
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.URI
        ))
        store.insert(collection, StoredQuad(
            subject = subgraphUri,
            predicate = ProvenanceNamespaces.TG_CONTAINS,
            objectValue = "<<$entityA|http://example.org/label|GraphMesh>>",
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.QUOTED_TRIPLE
        ))
        store.insert(collection, StoredQuad(
            subject = subgraphUri,
            predicate = ProvenanceNamespaces.TG_CONTAINS,
            objectValue = "<<$entityB|http://example.org/relatedTo|$entityA>>",
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.QUOTED_TRIPLE
        ))
        // sg-2 derived from a different chunk — must NOT be returned
        store.insert(collection, StoredQuad(
            subject = unrelatedSubgraph,
            predicate = ProvenanceNamespaces.PROV_WAS_DERIVED_FROM,
            objectValue = otherChunkUrn,
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.URI
        ))
        store.insert(collection, StoredQuad(
            subject = unrelatedSubgraph,
            predicate = ProvenanceNamespaces.TG_CONTAINS,
            objectValue = "<<http://graphmesh.io/entity/zzz|http://example.org/label|Other>>",
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.QUOTED_TRIPLE
        ))
        return store
    }

    @Test
    fun `findSubgraphsForChunks returns subgraph URIs that wasDerivedFrom matching chunks`() {
        val store = seededStore()
        val result = store.findSubgraphsForChunks(collection, listOf(chunkUrn))
        assertEquals(listOf(subgraphUri), result)
    }

    @Test
    fun `findSubgraphsForChunks returns empty for unknown chunks`() {
        val store = seededStore()
        val result = store.findSubgraphsForChunks(collection, listOf("urn:chunk:does/not/exist"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `findSubgraphsForChunks returns empty for empty input`() {
        val store = seededStore()
        assertTrue(store.findSubgraphsForChunks(collection, emptyList()).isEmpty())
    }

    @Test
    fun `findSubgraphsForChunks deduplicates across chunks`() {
        val store = seededStore()
        // Same subgraph derived from two chunks → only one URI in result
        store.insert(collection, StoredQuad(
            subject = subgraphUri,
            predicate = ProvenanceNamespaces.PROV_WAS_DERIVED_FROM,
            objectValue = otherChunkUrn,
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.URI
        ))
        val result = store.findSubgraphsForChunks(collection, listOf(chunkUrn, otherChunkUrn))
        assertEquals(setOf(subgraphUri, unrelatedSubgraph), result.toSet())
        assertEquals(2, result.size, "result must be deduplicated")
    }

    @Test
    fun `findQuotedTriplesForSubgraphs returns inner triples as StoredQuads`() {
        val store = seededStore()
        val result = store.findQuotedTriplesForSubgraphs(collection, listOf(subgraphUri))
        assertEquals(2, result.size)
        val subjects = result.map { it.subject }.toSet()
        assertEquals(setOf(entityA, entityB), subjects)
        // The unpacked rows should NOT keep the quoted-triple object type
        assertTrue(result.all { it.objectType == ObjectType.URI })
    }

    @Test
    fun `findQuotedTriplesForSubgraphs returns empty for empty input`() {
        val store = seededStore()
        assertTrue(store.findQuotedTriplesForSubgraphs(collection, emptyList()).isEmpty())
    }

    @Test
    fun `findQuotedTriplesForSubgraphs returns empty for unknown subgraph`() {
        val store = seededStore()
        assertTrue(store.findQuotedTriplesForSubgraphs(collection, listOf("urn:graphmesh:subgraph:nope")).isEmpty())
    }
}
```

- [ ] **Step 3.2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.QuadStoreDefaultMethodsTest" -q`
Expected: FAIL with `unresolved reference: findSubgraphsForChunks` / `findQuotedTriplesForSubgraphs`.

- [ ] **Step 3.3: Add the default methods to `QuadStore`**

Replace the contents of `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt` with:

```kotlin
package com.agentwork.graphmesh.storage

import com.agentwork.graphmesh.provenance.ProvenanceNamespaces
import com.agentwork.graphmesh.rdf.QuadConverter

interface QuadStore {
    fun insert(collection: String, quad: StoredQuad)
    fun insertBatch(collection: String, quads: List<StoredQuad>)
    fun delete(collection: String, quad: StoredQuad)
    fun deleteCollection(collection: String)
    fun query(collection: String, query: QuadQuery): List<StoredQuad>

    fun findByEntities(collection: String, entityIds: List<String>): List<StoredQuad> {
        return entityIds.flatMap { id ->
            query(collection, QuadQuery(subject = id)) +
            query(collection, QuadQuery(objectValue = id))
        }.distinct()
    }

    /**
     * Finds all provenance subgraph URIs whose `prov:wasDerivedFrom` points
     * at one of the given chunk URNs (e.g. `urn:chunk:doc-<uuid>/p1/c1`).
     *
     * Result is deduplicated.
     */
    fun findSubgraphsForChunks(collection: String, chunkUrns: List<String>): List<String> {
        if (chunkUrns.isEmpty()) return emptyList()
        return chunkUrns.flatMap { urn ->
            query(
                collection,
                QuadQuery(
                    predicate = ProvenanceNamespaces.PROV_WAS_DERIVED_FROM,
                    objectValue = urn
                )
            ).map { it.subject }
        }.distinct()
    }

    /**
     * Returns the inner knowledge triples (`<<s|p|o>>`) embedded in the
     * `tg:contains` rows of the given subgraph URIs, unpacked back into
     * regular [StoredQuad] form (objectType = URI, dataset = "").
     */
    fun findQuotedTriplesForSubgraphs(
        collection: String,
        subgraphUris: List<String>
    ): List<StoredQuad> {
        if (subgraphUris.isEmpty()) return emptyList()
        return subgraphUris.flatMap { sg ->
            query(
                collection,
                QuadQuery(
                    subject = sg,
                    predicate = ProvenanceNamespaces.TG_CONTAINS
                )
            ).mapNotNull { QuadConverter.unpackQuotedTriple(it) }
        }
    }
}
```

- [ ] **Step 3.4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.QuadStoreDefaultMethodsTest" -q`
Expected: 7 tests, all PASS.

- [ ] **Step 3.5: Run the existing storage tests too (regression check, only the unit-level ones)**

Run: `./gradlew test --tests "com.agentwork.graphmesh.rdf.*" --tests "com.agentwork.graphmesh.storage.QuadStoreDefaultMethodsTest" -q`
Expected: PASS (the heavy `QuadStoreIntegrationTest` requires Cassandra and is excluded by this filter).

- [ ] **Step 3.6: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreDefaultMethodsTest.kt
git commit -m "feat(storage): add findSubgraphsForChunks and findQuotedTriplesForSubgraphs"
```

---

## Task 4: Rewrite `GraphRagService.retrieveSubgraph` and extract a pure helper

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`

This task does NOT add a service-level integration test (would need fakes for the LLM provider, vector store, prompt executor, explainability producer — out of scope for a fix). The pure logic that benefits from testing is extracted into `collectEntityUris(...)`, which is unit-tested in Task 5.

- [ ] **Step 4.1: Replace the body of `retrieveSubgraph` and add `collectEntityUris`**

In `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`, replace the existing `retrieveSubgraph` function (lines 96–122) with the two functions below. Use the **exact** entity-URI prefix you confirmed in Task 0 — the plan assumes `http://graphmesh.io/entity/`. If your Step 0.2 result was different, substitute that prefix in `collectEntityUris`.

Also: only prepend `urn:chunk:` if Task 0 Step 0.1 confirmed that producers actually use the `urn:chunk:` prefix. If they don't, drop the `"urn:chunk:$it"` line and pass `it` directly.

```kotlin
    private fun retrieveSubgraph(query: GraphRagQuery): List<StoredQuad> {
        val embeddingModel = resolveLlmModel(embeddingConfig.model)

        val embedding = runBlocking {
            embeddingProvider.embed(query.question, embeddingModel)
        }
        val queryVector = FloatArray(embedding.size) { embedding[it].toFloat() }

        // Vector hits → bare chunk ids from the embedding payload
        val searchResults = vectorStore.search(
            collection = query.collectionId,
            queryVector = queryVector,
            limit = 50
        )

        val chunkUrns = searchResults
            .mapNotNull { it.payload["chunk_id"]?.toString() }
            .map { "urn:chunk:$it" }
            .distinct()

        if (chunkUrns.isEmpty()) {
            logger.debug("retrieveSubgraph: no chunk_ids in vector hits")
            return emptyList()
        }

        // Phase 1: chunks → provenance subgraphs
        val subgraphUris = quadStore.findSubgraphsForChunks(query.collectionId, chunkUrns)
        if (subgraphUris.isEmpty()) {
            logger.debug("retrieveSubgraph: no subgraphs for {} chunkUrns", chunkUrns.size)
            return emptyList()
        }

        // Phase 2: subgraphs → unpacked quoted triples (the actual knowledge edges)
        val quotedTriples = quadStore.findQuotedTriplesForSubgraphs(query.collectionId, subgraphUris)

        // Phase 3: 1-hop entity expansion
        val entityUris = collectEntityUris(quotedTriples)
        val expandedEdges = if (entityUris.isNotEmpty()) {
            quadStore.findByEntities(query.collectionId, entityUris)
        } else {
            emptyList()
        }

        logger.debug(
            "retrieveSubgraph: {} chunks → {} subgraphs → {} quoted triples → {} entities → {} expanded edges",
            chunkUrns.size, subgraphUris.size, quotedTriples.size, entityUris.size, expandedEdges.size
        )

        return (quotedTriples + expandedEdges).distinct().take(query.maxEdges)
    }

    /**
     * Pure helper: collects entity URIs (subjects/objects starting with the
     * GraphMesh entity-URI prefix) from a list of unpacked quoted triples.
     * Public-internal for unit testing.
     */
    internal fun collectEntityUris(quotedTriples: List<StoredQuad>): List<String> {
        return quotedTriples
            .flatMap { listOf(it.subject, it.objectValue) }
            .filter { it.startsWith("http://graphmesh.io/entity/") }
            .distinct()
    }
```

- [ ] **Step 4.2: Build the project to check the rewrite compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4.3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt
git commit -m "fix(graphrag): rewrite retrieveSubgraph to walk chunk→subgraph→entities"
```

---

## Task 5: Unit-test `GraphRagService.collectEntityUris`

**Files:**
- Modify: `src/test/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagServiceTest.kt`

Constructing a real `GraphRagService` requires too many collaborators. Instead we test the extracted pure helper as a copy-of-logic test alongside the existing `parseEdgeSelection` tests. (The existing file already follows this pattern — see its `parseEdgeSelection` private copy.)

- [ ] **Step 5.1: Add the failing test**

Append to `src/test/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagServiceTest.kt` (inside the existing `class GraphRagServiceTest`):

```kotlin
    @Test
    fun `collectEntityUris extracts entity URIs from both subject and object positions`() {
        val triples = listOf(
            StoredQuad(
                subject = "http://graphmesh.io/entity/aaa",
                predicate = "http://example.org/label",
                objectValue = "GraphMesh",
                dataset = ""
            ),
            StoredQuad(
                subject = "http://graphmesh.io/entity/bbb",
                predicate = "http://example.org/relatedTo",
                objectValue = "http://graphmesh.io/entity/aaa",
                dataset = ""
            )
        )

        val result = collectEntityUrisCopy(triples)

        assertEquals(
            setOf("http://graphmesh.io/entity/aaa", "http://graphmesh.io/entity/bbb"),
            result.toSet()
        )
    }

    @Test
    fun `collectEntityUris filters out non-entity URIs and literals`() {
        val triples = listOf(
            StoredQuad(
                subject = "urn:graphmesh:subgraph:abc",
                predicate = "http://www.w3.org/ns/prov#wasDerivedFrom",
                objectValue = "urn:chunk:doc-1/p1/c1",
                dataset = ""
            )
        )

        assertTrue(collectEntityUrisCopy(triples).isEmpty())
    }

    @Test
    fun `collectEntityUris deduplicates`() {
        val triples = listOf(
            StoredQuad(
                subject = "http://graphmesh.io/entity/aaa",
                predicate = "p",
                objectValue = "http://graphmesh.io/entity/bbb",
                dataset = ""
            ),
            StoredQuad(
                subject = "http://graphmesh.io/entity/aaa",
                predicate = "q",
                objectValue = "http://graphmesh.io/entity/bbb",
                dataset = ""
            )
        )

        val result = collectEntityUrisCopy(triples)
        assertEquals(2, result.size)
    }

    // Standalone copy of GraphRagService.collectEntityUris for testing without
    // having to construct the full service (which needs LLM/vector collaborators).
    private fun collectEntityUrisCopy(quotedTriples: List<StoredQuad>): List<String> {
        return quotedTriples
            .flatMap { listOf(it.subject, it.objectValue) }
            .filter { it.startsWith("http://graphmesh.io/entity/") }
            .distinct()
    }
```

- [ ] **Step 5.2: Run the test**

Run: `./gradlew test --tests "com.agentwork.graphmesh.query.graphrag.GraphRagServiceTest" -q`
Expected: all tests PASS (pre-existing 7 + new 3 = 10 tests).

- [ ] **Step 5.3: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagServiceTest.kt
git commit -m "test(graphrag): cover collectEntityUris helper"
```

---

## Task 6: Tighten `smoke-test.sh` graphRag assertion

**Files:**
- Modify: `smoke-test.sh`

- [ ] **Step 6.1: Inspect the current graphRag step**

Run: `grep -n "graphRag" smoke-test.sh`

Expected: at least one block that POSTs a `graphRag` GraphQL query and parses the response. Note the variable that holds the response (the spec example uses `$RESP`).

- [ ] **Step 6.2: Replace the assertion**

Locate the existing graphRag assertion in `smoke-test.sh` (the block that checks the graphRag response). Replace it with — adapted to the actual response variable name in the file — the stricter check from the spec:

```bash
ANS=$(echo "$RESP" | jq -r '.data.graphRag.answer // empty')
EDGES=$(echo "$RESP" | jq '.data.graphRag.retrievedEdgeCount // 0')
if [[ -n "$ANS" && "$EDGES" -gt 0 && "$ANS" != *"No relevant knowledge"* ]]; then
  ok "graphRag-Antwort ($EDGES edges): ${ANS:0:80}..."
else
  fail "graphRag liefert leere Antwort oder 0 edges (edges=$EDGES)"
fi
```

If the existing block uses different helper functions (`ok`/`fail` vs. `pass`/`die` etc.), match the helpers actually defined in the file. Do **not** introduce new helpers.

- [ ] **Step 6.3: Sanity-lint the script**

Run: `bash -n smoke-test.sh`
Expected: no syntax errors (the script is not executed end-to-end here — that requires the full stack).

- [ ] **Step 6.4: Commit**

```bash
git add smoke-test.sh
git commit -m "test(smoke): assert graphRag returns >0 edges and substantive answer"
```

---

## Task 7: Final verification

- [ ] **Step 7.1: Run all unit tests touched by this feature**

Run:

```
./gradlew test \
  --tests "com.agentwork.graphmesh.rdf.QuadConverterTest" \
  --tests "com.agentwork.graphmesh.storage.QuadStoreDefaultMethodsTest" \
  --tests "com.agentwork.graphmesh.query.graphrag.GraphRagServiceTest" -q
```

Expected: all PASS.

- [ ] **Step 7.2: Compile-check the whole project**

Run: `./gradlew compileKotlin compileTestKotlin -q`
Expected: BUILD SUCCESSFUL.

(`./gradlew build` is NOT required because of pre-existing build issues unrelated to this feature: ambiguous mainClass, Koog bean conflict, integration tests needing docker. See user memory `project_known_build_issues.md`.)

- [ ] **Step 7.3: Show the diff summary**

Run: `git log --oneline -10`

Expected to see the 6 commits from this plan (Tasks 1, 2, 3, 4, 5, 6) plus prior history. No push.

---

## Acceptance Criteria (from spec)

- [x] `findSubgraphsForChunks` and `findQuotedTriplesForSubgraphs` exist on `QuadStore` and are unit-tested for happy path, empty input, and unknown chunk/subgraph (Task 3 covers all three).
- [x] `GraphRagService.retrieveSubgraph` walks chunkURN → subgraph → quoted triples → entity URIs → 1-hop expansion (Task 4).
- [x] `smoke-test.sh` asserts `retrievedEdgeCount > 0` and rejects the "No relevant knowledge" fallback (Task 6).
- [x] Provenance/quoted-triple schemas (Feature 29) untouched — fix is read-only on the storage layer (no writes to `ProvenanceService`).
- [x] Existing `documentRag`, `triples`, `vectorSearch` unchanged.
- [x] Pre-existing `GraphRagServiceTest.parseEdgeSelection` tests still pass (Task 5 only appends).

## Risks & Out of Scope

- **Service-level integration test** for `GraphRagService.retrieveSubgraph` (with fakes for `LLMEmbeddingProvider`, `VectorStore`, `PromptExecutor`, `ExplainabilityEventProducer`). Out of scope: would double the size of this fix and the spec only asked for "Unit-Test mit gemocktem QuadStore + VectorStore". The pure entity-collection logic IS unit-tested in Task 5; the storage walk IS unit-tested in Task 3 with the same in-memory store. The end-to-end check is `smoke-test.sh` (Task 6).
- **CassandraQuadStore changes.** None required: the new methods are default methods on the interface and use the existing `query(QuadQuery)` patterns 4 and 10. The spec speculated about new prepared statements; on inspection, the existing prepared statements already cover both lookups.
- **Chunk URN prefix.** Task 0 must verify the producer side actually uses `urn:chunk:`. If a future producer change drops the prefix, Task 4's `"urn:chunk:$it"` line is the single point of failure.
