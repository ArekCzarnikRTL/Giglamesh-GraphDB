# Feature 34: Graph Explorer UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an interactive force-directed Graph Explorer UI in Next.js backed by three new GraphQL queries on the existing Cassandra-backed quad store.

**Architecture:** Backend exposes `entitySearch`, `graphMetadata`, and a `limit` parameter on `triples` via Spring GraphQL controllers and `QuadStore` extensions. Frontend mounts a new `/graph` route that uses `react-force-graph-2d` (loaded via `next/dynamic` to avoid SSR) and a `useGraphData` hook backed by Apollo v4.

**Tech Stack:** Spring Boot 4 + Kotlin + Spring GraphQL (backend); Next.js 14 + Apollo v4 + react-force-graph-2d + Vitest/jsdom (frontend); Cassandra (storage).

**Spec:** `docs/superpowers/specs/2026-04-07-feature-34-graph-explorer-ui-design.md`

**Conventions:**
- Direct-to-main commits (no PRs).
- Backend tests: `./gradlew test`. Pre-existing build issues are tracked in memory; only verify that **new** tests pass.
- Frontend: `cd frontend && pnpm test` (Vitest), `pnpm build` (Next.js build).
- Each commit message follows the existing repo style: `feat(area): short message` or `test(area): ...`. Always co-author with Claude.

---

## File Structure

### Backend (created/modified)

| Path | Purpose |
|---|---|
| `src/main/resources/graphql/schema.graphqls` | Add `limit` to `triples`; declare `entitySearch`, `graphMetadata`, `GraphMetadata`. |
| `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt` | Add `limit: Int?` to `query`; add `findSubjects(...)` and `aggregateMetadata(...)` interface methods. |
| `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt` | Implement the limit slice + new methods on Cassandra-backed store. |
| `src/main/kotlin/com/agentwork/graphmesh/storage/GraphMetadataView.kt` | Pure data class returned by `aggregateMetadata`. |
| `src/test/kotlin/com/agentwork/graphmesh/storage/InMemoryQuadStore.kt` | Same `limit` + new methods on the test fake. |
| `src/main/kotlin/com/agentwork/graphmesh/api/GraphController.kt` | Add `limit` argument; new `entitySearch`, `graphMetadata` resolvers. |
| `src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreLimitTest.kt` | New unit tests for `query(limit)`, `findSubjects`, `aggregateMetadata` (against `InMemoryQuadStore`). |
| `src/test/kotlin/com/agentwork/graphmesh/api/GraphControllerTest.kt` | New tests for `triples(limit)`, `entitySearch`, `graphMetadata` resolvers (mock `QuadStore`). |

### Frontend (created/modified)

| Path | Purpose |
|---|---|
| `frontend/package.json` | Add `react-force-graph-2d`, `d3-force`, `-D canvas`. |
| `frontend/src/types/graph.ts` | TypeScript types: `GraphNode`, `GraphEdge`, `GraphData`, `RdfTermType`, `GraphFilter`, `LayoutConfig`. |
| `frontend/src/lib/graph/transforms.ts` | Pure helpers: `quadsToGraphData`, `mergeGraphData`, `extractLabel`, `inferSubjectType`, `quadToEdgeId`. |
| `frontend/src/graphql/graph.ts` | `GRAPH_TRIPLES_QUERY`, `NODE_NEIGHBORS_QUERY`, `ENTITY_SEARCH_QUERY`, `GRAPH_METADATA_QUERY`. |
| `frontend/src/hooks/useGraphData.ts` | Hook: `loadInitial`, `expandNode`, `clear`, `graphData` state. |
| `frontend/src/components/graph/GraphCanvas.tsx` | Canvas wrapper, `next/dynamic({ ssr: false })`, custom node painter. |
| `frontend/src/components/graph/GraphControls.tsx` | Sliders for d3 layout params; "reset view" button. |
| `frontend/src/components/graph/GraphFilter.tsx` | Multi-select for datasets / predicates / entity types. |
| `frontend/src/components/graph/NodeDetail.tsx` | Side panel listing all triples for the selected node. |
| `frontend/src/components/graph/EntitySearch.tsx` | Lazy-query autocomplete against `entitySearch`. |
| `frontend/src/app/graph/page.tsx` | Container page that wires everything together; reads `?entity=` query param. |
| `frontend/src/__tests__/lib/graph/transforms.test.ts` | Pure helper tests. |
| `frontend/src/__tests__/hooks/useGraphData.test.tsx` | Hook tests with `MockedProvider`. |
| `frontend/src/__tests__/components/graph/GraphCanvas.test.tsx` | Canvas mounting test (with canvas polyfill OR mock fallback). |
| `frontend/src/__tests__/components/graph/NodeDetail.test.tsx` | Detail panel tests. |
| `frontend/src/__tests__/components/graph/GraphFilter.test.tsx` | Filter UI tests. |
| `frontend/src/__tests__/components/graph/EntitySearch.test.tsx` | Autocomplete tests. |

---

## Parallelization Note

Phase A (Backend) and Phase B (Frontend foundation) can be developed **in parallel** because the frontend phase only needs the GraphQL **schema strings** locked in (which Phase A Task 1 commits early). Subagents can be dispatched as:
- Backend stream: Task 1 → Tasks 2 → 3 → 4 → 5 → 6 sequentially.
- Frontend stream (after Task 1 lands): Tasks 7 → 8 → 9 in parallel; then 10–14 sequentially; then 15.

---

# Phase A — Backend

## Task 1: GraphQL Schema Extension

**Files:**
- Modify: `src/main/resources/graphql/schema.graphqls`

- [ ] **Step 1: Add `limit` parameter to `triples` and declare new queries/types**

Replace the `triples` block and the `vectorSearch` block area inside the `Query` type, then append the new type at the bottom. The full updated `Query` block should read:

```graphql
type Query {
    collections(tags: [String]): [Collection!]!
    collection(id: ID!): Collection

    documents(
        collectionId: ID!
        filter: DocumentFilter
        page: Int = 0
        pageSize: Int = 20
    ): DocumentPage!

    document(id: ID!): Document
    documentChunks(documentId: ID!): [Document!]!

    triples(
        collectionId: ID!
        subject: String
        predicate: String
        object: String
        dataset: String
        limit: Int = 500
    ): [Quad!]!

    vectorSearch(
        collectionId: ID!
        query: String!
        limit: Int = 10
    ): [SearchResult!]!

    entitySearch(
        collectionId: ID!
        prefix: String!
        limit: Int = 20
    ): [String!]!

    graphMetadata(collectionId: ID!): GraphMetadata!
}
```

And append at the bottom of the file (after the `DocumentPage` block):

```graphql
type GraphMetadata {
    datasets: [String!]!
    predicates: [String!]!
    entityTypes: [String!]!
}
```

- [ ] **Step 2: Compile-check (schema only)**

Run: `./gradlew compileKotlin`
Expected: Schema parses; existing controllers will fail to compile because of the new `limit` arg on `triples` — that's fine, we'll fix in Task 4. If `compileKotlin` is too noisy, just inspect the file with `git diff` for typos.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/graphql/schema.graphqls
git commit -m "$(cat <<'EOF'
feat(graphql): add limit to triples, entitySearch, graphMetadata schema

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: GraphMetadataView data class

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/GraphMetadataView.kt`

- [ ] **Step 1: Create the data class**

```kotlin
package com.agentwork.graphmesh.storage

/**
 * Aggregate view over a collection's quads, used by the
 * `graphMetadata` GraphQL query to populate filter dropdowns.
 *
 * Each list is alphabetically sorted and capped to 200 entries.
 */
data class GraphMetadataView(
    val datasets: List<String>,
    val predicates: List<String>,
    val entityTypes: List<String>
)
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/GraphMetadataView.kt
git commit -m "$(cat <<'EOF'
feat(storage): add GraphMetadataView data class

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: QuadStore interface — limit, findSubjects, aggregateMetadata

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt`
- Modify: `src/test/kotlin/com/agentwork/graphmesh/storage/InMemoryQuadStore.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreLimitTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreLimitTest.kt`:

```kotlin
package com.agentwork.graphmesh.storage

import com.agentwork.graphmesh.rdf.NamedGraph
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuadStoreLimitTest {

    private val collection = "c1"
    private val rdfType = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"

    private fun store(): InMemoryQuadStore {
        val s = InMemoryQuadStore()
        for (i in 1..10) {
            s.insert(collection, StoredQuad(
                subject = "http://ex.org/e$i",
                predicate = "http://ex.org/p",
                objectValue = "v$i",
                dataset = NamedGraph.SOURCE,
                objectType = ObjectType.LITERAL
            ))
        }
        // entity types
        s.insert(collection, StoredQuad(
            subject = "http://ex.org/e1",
            predicate = rdfType,
            objectValue = "http://ex.org/Person",
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.URI
        ))
        s.insert(collection, StoredQuad(
            subject = "http://ex.org/e2",
            predicate = rdfType,
            objectValue = "http://ex.org/Org",
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.URI
        ))
        return s
    }

    @Test fun `query respects limit`() {
        val s = store()
        val all = s.query(collection, QuadQuery())
        assertTrue(all.size > 5, "fixture should have >5 quads")
        val limited = s.query(collection, QuadQuery(), limit = 5)
        assertEquals(5, limited.size)
    }

    @Test fun `query without limit returns all`() {
        val s = store()
        val all = s.query(collection, QuadQuery())
        val limited = s.query(collection, QuadQuery(), limit = null)
        assertEquals(all.size, limited.size)
    }

    @Test fun `findSubjects returns distinct subjects matching substring case-insensitive`() {
        val s = store()
        val matches = s.findSubjects(collection, substringMatch = "E1", limit = 10)
        // e1 and e10 both contain "e1" case-insensitively
        assertTrue("http://ex.org/e1" in matches)
        assertTrue("http://ex.org/e10" in matches)
        assertEquals(matches.size, matches.distinct().size, "must be distinct")
    }

    @Test fun `findSubjects respects limit`() {
        val s = store()
        val matches = s.findSubjects(collection, substringMatch = "ex.org", limit = 3)
        assertEquals(3, matches.size)
    }

    @Test fun `aggregateMetadata returns distinct datasets predicates and entity types`() {
        val s = store()
        val meta = s.aggregateMetadata(collection)
        assertEquals(listOf(NamedGraph.SOURCE), meta.datasets)
        assertTrue("http://ex.org/p" in meta.predicates)
        assertTrue(rdfType in meta.predicates)
        assertEquals(listOf("http://ex.org/Org", "http://ex.org/Person"), meta.entityTypes)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.QuadStoreLimitTest"`
Expected: Compile error / FAIL — `query` does not accept `limit`, `findSubjects` and `aggregateMetadata` not defined.

- [ ] **Step 3: Update QuadStore interface**

Replace the existing `query` line in `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt` and add the two new methods. The interface body should now read:

```kotlin
interface QuadStore {
    fun insert(collection: String, quad: StoredQuad)
    fun insertBatch(collection: String, quads: List<StoredQuad>)
    fun delete(collection: String, quad: StoredQuad)
    fun deleteCollection(collection: String)
    fun query(collection: String, query: QuadQuery, limit: Int? = null): List<StoredQuad>

    /**
     * Returns up to [limit] distinct subject URIs from [collection] whose
     * URI string contains [substringMatch] (case-insensitive). Result order
     * is implementation-defined; callers should not rely on it.
     */
    fun findSubjects(collection: String, substringMatch: String, limit: Int): List<String>

    /**
     * Returns distinct datasets, predicates, and entity types
     * (objects of `rdf:type` triples) for [collection]. Each list is
     * alphabetically sorted and capped at 200 entries.
     */
    fun aggregateMetadata(collection: String): GraphMetadataView

    // existing default methods unchanged
    fun findByEntities(collection: String, entityIds: List<String>): List<StoredQuad> {
        return entityIds.flatMap { id ->
            query(collection, QuadQuery(subject = id)) +
            query(collection, QuadQuery(objectValue = id))
        }.distinct()
    }

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

(Imports stay as they were.)

- [ ] **Step 4: Update InMemoryQuadStore**

Edit `src/test/kotlin/com/agentwork/graphmesh/storage/InMemoryQuadStore.kt` so the class implements the new interface:

```kotlin
package com.agentwork.graphmesh.storage

private const val RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"

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

    override fun query(collection: String, query: QuadQuery, limit: Int?): List<StoredQuad> {
        val rows = byCollection[collection] ?: return emptyList()
        val filtered = rows.filter { q ->
            (query.subject == null || q.subject == query.subject) &&
            (query.predicate == null || q.predicate == query.predicate) &&
            (query.objectValue == null || q.objectValue == query.objectValue) &&
            (query.dataset == null || q.dataset == query.dataset)
        }
        return if (limit != null) filtered.take(limit) else filtered
    }

    override fun findSubjects(collection: String, substringMatch: String, limit: Int): List<String> {
        val rows = byCollection[collection] ?: return emptyList()
        val needle = substringMatch.lowercase()
        return rows.asSequence()
            .map { it.subject }
            .filter { it.lowercase().contains(needle) }
            .distinct()
            .take(limit)
            .toList()
    }

    override fun aggregateMetadata(collection: String): GraphMetadataView {
        val rows = byCollection[collection] ?: return GraphMetadataView(emptyList(), emptyList(), emptyList())
        val datasets = rows.map { it.dataset }.distinct().sorted().take(200)
        val predicates = rows.map { it.predicate }.distinct().sorted().take(200)
        val entityTypes = rows.asSequence()
            .filter { it.predicate == RDF_TYPE }
            .map { it.objectValue }
            .distinct()
            .sorted()
            .take(200)
            .toList()
        return GraphMetadataView(datasets, predicates, entityTypes)
    }
}
```

- [ ] **Step 5: Update QuadStoreService (Cassandra impl)**

Edit `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt`:

a) Change the `override fun query(collection, query)` signature to `override fun query(collection: String, query: QuadQuery, limit: Int?)` and add at the very end of the function (right before `return rows.mapNotNull { ... }`), wrap the result like:

```kotlin
        val result = rows.mapNotNull { row ->
            val quad = StoredQuad(
                subject = row.getString("s")!!,
                predicate = row.getString("p")!!,
                objectValue = row.getString("o")!!,
                dataset = row.getString("d")!!,
                objectType = ObjectType.fromCode(row.getString("otype")!!),
                datatype = row.getString("dtype") ?: "",
                language = row.getString("lang") ?: ""
            )
            if (matchesFilter(quad, s, p, o, d)) quad else null
        }
        return if (limit != null) result.take(limit) else result
    }
```

b) Add the new methods anywhere inside the class:

```kotlin
    override fun findSubjects(collection: String, substringMatch: String, limit: Int): List<String> {
        // MVP: full scan via query(QuadQuery()), then in-memory filter+distinct.
        // TODO: replace with CQL prefix index or materialized view when needed.
        val needle = substringMatch.lowercase()
        return query(collection, QuadQuery(), limit = null)
            .asSequence()
            .map { it.subject }
            .filter { it.lowercase().contains(needle) }
            .distinct()
            .take(limit)
            .toList()
    }

    override fun aggregateMetadata(collection: String): GraphMetadataView {
        val all = query(collection, QuadQuery(), limit = null)
        val datasets = all.map { it.dataset }.distinct().sorted().take(200)
        val predicates = all.map { it.predicate }.distinct().sorted().take(200)
        val rdfType = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
        val entityTypes = all.asSequence()
            .filter { it.predicate == rdfType }
            .map { it.objectValue }
            .distinct()
            .sorted()
            .take(200)
            .toList()
        return GraphMetadataView(datasets, predicates, entityTypes)
    }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.QuadStoreLimitTest"`
Expected: All 5 tests PASS.

- [ ] **Step 7: Run the broader storage test class to check no regression**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.QuadStoreDefaultMethodsTest"`
Expected: PASS. If it fails because the default methods now need `limit = null`, they should still compile because `limit` has a default value — just verify.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt \
        src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt \
        src/test/kotlin/com/agentwork/graphmesh/storage/InMemoryQuadStore.kt \
        src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreLimitTest.kt
git commit -m "$(cat <<'EOF'
feat(storage): add limit, findSubjects and aggregateMetadata to QuadStore

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: GraphController — limit, entitySearch, graphMetadata resolvers

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/api/GraphController.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/api/GraphControllerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/agentwork/graphmesh/api/GraphControllerTest.kt`:

```kotlin
package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.storage.InMemoryQuadStore
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.StoredQuad
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphControllerTest {

    private val rdfType = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"

    private fun seeded(): Pair<GraphController, InMemoryQuadStore> {
        val store = InMemoryQuadStore()
        for (i in 1..6) {
            store.insert("c1", StoredQuad(
                subject = "http://ex.org/e$i",
                predicate = "http://ex.org/p",
                objectValue = "v$i",
                dataset = NamedGraph.SOURCE,
                objectType = ObjectType.LITERAL
            ))
        }
        store.insert("c1", StoredQuad(
            subject = "http://ex.org/e1",
            predicate = rdfType,
            objectValue = "http://ex.org/Person",
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.URI
        ))
        return GraphController(store) to store
    }

    @Test fun `triples respects explicit limit`() {
        val (ctl, _) = seeded()
        val result = ctl.triples("c1", null, null, null, null, limit = 3)
        assertEquals(3, result.size)
    }

    @Test fun `triples caps limit at 5000`() {
        val (ctl, _) = seeded()
        // Even though only 7 quads exist, the cap logic should not throw.
        val result = ctl.triples("c1", null, null, null, null, limit = 100000)
        assertTrue(result.size <= 5000)
    }

    @Test fun `entitySearch returns matching subjects`() {
        val (ctl, _) = seeded()
        val matches = ctl.entitySearch("c1", prefix = "e1", limit = 10)
        assertTrue("http://ex.org/e1" in matches)
    }

    @Test fun `graphMetadata returns aggregated lists`() {
        val (ctl, _) = seeded()
        val meta = ctl.graphMetadata("c1")
        assertTrue(NamedGraph.SOURCE in meta.datasets)
        assertTrue("http://ex.org/p" in meta.predicates)
        assertTrue(rdfType in meta.predicates)
        assertEquals(listOf("http://ex.org/Person"), meta.entityTypes)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.api.GraphControllerTest"`
Expected: Compile errors — `triples` doesn't take `limit`, no `entitySearch`, no `graphMetadata`.

- [ ] **Step 3: Implement the resolver changes**

Replace the body of `src/main/kotlin/com/agentwork/graphmesh/api/GraphController.kt` with:

```kotlin
package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.storage.GraphMetadataView
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

private const val MAX_TRIPLE_LIMIT = 5000
private const val DEFAULT_TRIPLE_LIMIT = 500

@Controller
class GraphController(
    private val quadStore: QuadStore
) {

    @QueryMapping
    fun triples(
        @Argument collectionId: String,
        @Argument subject: String?,
        @Argument predicate: String?,
        @Argument("object") objectValue: String?,
        @Argument dataset: String?,
        @Argument limit: Int? = null
    ): List<StoredQuad> {
        val effectiveLimit = (limit ?: DEFAULT_TRIPLE_LIMIT).coerceIn(1, MAX_TRIPLE_LIMIT)
        return quadStore.query(
            collectionId,
            QuadQuery(
                subject = subject,
                predicate = predicate,
                objectValue = objectValue,
                dataset = dataset
            ),
            limit = effectiveLimit
        )
    }

    @QueryMapping
    fun entitySearch(
        @Argument collectionId: String,
        @Argument prefix: String,
        @Argument limit: Int? = null
    ): List<String> {
        val effectiveLimit = (limit ?: 20).coerceIn(1, 200)
        return quadStore.findSubjects(collectionId, prefix, effectiveLimit)
    }

    @QueryMapping
    fun graphMetadata(@Argument collectionId: String): GraphMetadataView {
        return quadStore.aggregateMetadata(collectionId)
    }

    // Schema field is `object`, Kotlin property is `objectValue`.
    @SchemaMapping(typeName = "Quad", field = "object")
    fun quadObject(quad: StoredQuad): String = quad.objectValue

    // GraphQL enum exposed as String.
    @SchemaMapping(typeName = "Quad", field = "objectType")
    fun quadObjectType(quad: StoredQuad): String = quad.objectType.name

    // Map GraphMetadataView to GraphQL type GraphMetadata via field name match.
    @SchemaMapping(typeName = "GraphMetadata", field = "datasets")
    fun gmDatasets(view: GraphMetadataView): List<String> = view.datasets

    @SchemaMapping(typeName = "GraphMetadata", field = "predicates")
    fun gmPredicates(view: GraphMetadataView): List<String> = view.predicates

    @SchemaMapping(typeName = "GraphMetadata", field = "entityTypes")
    fun gmEntityTypes(view: GraphMetadataView): List<String> = view.entityTypes
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.api.GraphControllerTest"`
Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/api/GraphController.kt \
        src/test/kotlin/com/agentwork/graphmesh/api/GraphControllerTest.kt
git commit -m "$(cat <<'EOF'
feat(api): add triples limit, entitySearch and graphMetadata resolvers

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Verify backend regressions

- [ ] **Step 1: Run all storage and api tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.*" --tests "com.agentwork.graphmesh.api.*"`
Expected: All tests pass. If a pre-existing failure surfaces (see memory `project_known_build_issues`), confirm it's unrelated to Task 1–4 changes (same failure on `git stash`); otherwise fix it.

- [ ] **Step 2: No commit (verification only)**

---

# Phase B — Frontend

## Task 6: Add dependencies

**Files:**
- Modify: `frontend/package.json`

- [ ] **Step 1: Install runtime + dev deps**

Run from repo root:

```bash
cd frontend && pnpm add react-force-graph-2d d3-force && pnpm add -D canvas
```

Expected: Adds three packages. **If `canvas` install fails** because of missing system libs (Cairo/Pango), record the failure and proceed with this fallback:

```bash
cd frontend && pnpm remove canvas
```

…then later in Task 13 use the `vi.mock` strategy described in the fallback note.

- [ ] **Step 2: Verify package.json updated**

Run: `cat frontend/package.json | grep -E "react-force-graph|d3-force|canvas"`
Expected: Lines with the new packages.

- [ ] **Step 3: Commit**

```bash
git add frontend/package.json frontend/pnpm-lock.yaml
git commit -m "$(cat <<'EOF'
chore(frontend): add react-force-graph-2d, d3-force, canvas deps

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: TypeScript types for graph

**Files:**
- Create: `frontend/src/types/graph.ts`

- [ ] **Step 1: Write the type module**

```typescript
export type RdfTermType = "URI" | "LITERAL" | "BLANK_NODE" | "QUOTED_TRIPLE";

export interface GraphNode {
  id: string;
  label: string;
  type: RdfTermType;
  isSubject: boolean;
  expanded: boolean;
  size: number;
}

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  predicate: string;
  dataset: string;
  label: string;
}

export interface GraphData {
  nodes: GraphNode[];
  links: GraphEdge[];
}

export interface GraphFilter {
  datasets: string[];
  predicates: string[];
  entityTypes: string[];
}

export interface LayoutConfig {
  chargeStrength: number;
  linkDistance: number;
  centerStrength: number;
  collisionRadius: number;
}

/** Wire shape returned by the GraphMesh `triples` GraphQL query. */
export interface QuadDto {
  subject: string;
  predicate: string;
  object: string;
  dataset: string;
  objectType: string; // "URI" | "LITERAL" | "QUOTED_TRIPLE"
  datatype?: string | null;
  language?: string | null;
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/types/graph.ts
git commit -m "$(cat <<'EOF'
feat(frontend): add graph TypeScript types

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Pure helpers in lib/graph/transforms.ts

**Files:**
- Create: `frontend/src/lib/graph/transforms.ts`
- Create: `frontend/src/__tests__/lib/graph/transforms.test.ts`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/__tests__/lib/graph/transforms.test.ts`:

```typescript
import { describe, it, expect } from "vitest";
import {
  extractLabel,
  inferSubjectType,
  quadsToGraphData,
  mergeGraphData,
  quadToEdgeId,
} from "@/lib/graph/transforms";
import { QuadDto } from "@/types/graph";

const q = (overrides: Partial<QuadDto>): QuadDto => ({
  subject: "http://ex.org/a",
  predicate: "http://ex.org/p",
  object: "http://ex.org/b",
  dataset: "default",
  objectType: "URI",
  datatype: null,
  language: null,
  ...overrides,
});

describe("extractLabel", () => {
  it("returns suffix after last slash", () => {
    expect(extractLabel("http://ex.org/foo")).toBe("foo");
  });
  it("returns suffix after last hash", () => {
    expect(extractLabel("http://ex.org#bar")).toBe("bar");
  });
  it("returns input when no separator", () => {
    expect(extractLabel("plainlabel")).toBe("plainlabel");
  });
});

describe("inferSubjectType", () => {
  it("detects blank node", () => {
    expect(inferSubjectType("_:b1")).toBe("BLANK_NODE");
  });
  it("detects quoted triple", () => {
    expect(inferSubjectType("<<s|p|o>>")).toBe("QUOTED_TRIPLE");
  });
  it("defaults to URI", () => {
    expect(inferSubjectType("http://ex.org/a")).toBe("URI");
  });
});

describe("quadsToGraphData", () => {
  it("creates one node per distinct subject and object", () => {
    const data = quadsToGraphData([
      q({ subject: "a", object: "b" }),
      q({ subject: "a", object: "c", predicate: "p2" }),
    ]);
    expect(data.nodes.map((n) => n.id).sort()).toEqual(["a", "b", "c"]);
    expect(data.links).toHaveLength(2);
  });

  it("marks subjects with isSubject=true", () => {
    const data = quadsToGraphData([q({ subject: "a", object: "b" })]);
    expect(data.nodes.find((n) => n.id === "a")?.isSubject).toBe(true);
    expect(data.nodes.find((n) => n.id === "b")?.isSubject).toBe(false);
  });

  it("uses objectType for object node type", () => {
    const data = quadsToGraphData([
      q({ subject: "a", object: "lit", objectType: "LITERAL" }),
    ]);
    expect(data.nodes.find((n) => n.id === "lit")?.type).toBe("LITERAL");
  });
});

describe("quadToEdgeId", () => {
  it("includes all four components", () => {
    expect(quadToEdgeId(q({ subject: "a", predicate: "p", object: "b", dataset: "d" })))
      .toBe("a|p|b|d");
  });
});

describe("mergeGraphData", () => {
  it("does not duplicate nodes or links", () => {
    const a = quadsToGraphData([q({ subject: "a", object: "b" })]);
    const b = quadsToGraphData([q({ subject: "a", object: "b" })]);
    const merged = mergeGraphData(a, b);
    expect(merged.nodes).toHaveLength(2);
    expect(merged.links).toHaveLength(1);
  });

  it("marks expanded node when expandedNodeId given", () => {
    const a = quadsToGraphData([q({ subject: "a", object: "b" })]);
    const merged = mergeGraphData(a, { nodes: [], links: [] }, "a");
    expect(merged.nodes.find((n) => n.id === "a")?.expanded).toBe(true);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && pnpm test src/__tests__/lib/graph/transforms.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement transforms**

Create `frontend/src/lib/graph/transforms.ts`:

```typescript
import { GraphData, GraphEdge, GraphNode, QuadDto, RdfTermType } from "@/types/graph";

export function extractLabel(uri: string): string {
  const hash = uri.lastIndexOf("#");
  const slash = uri.lastIndexOf("/");
  const idx = Math.max(hash, slash);
  return idx >= 0 ? uri.slice(idx + 1) : uri;
}

export function inferSubjectType(uri: string): RdfTermType {
  if (uri.startsWith("_:")) return "BLANK_NODE";
  if (uri.startsWith("<<") && uri.endsWith(">>")) return "QUOTED_TRIPLE";
  return "URI";
}

export function quadToEdgeId(quad: QuadDto): string {
  return `${quad.subject}|${quad.predicate}|${quad.object}|${quad.dataset}`;
}

export function quadsToGraphData(quads: QuadDto[]): GraphData {
  const nodes = new Map<string, GraphNode>();
  const links: GraphEdge[] = [];

  for (const quad of quads) {
    if (!nodes.has(quad.subject)) {
      nodes.set(quad.subject, {
        id: quad.subject,
        label: extractLabel(quad.subject),
        type: inferSubjectType(quad.subject),
        isSubject: true,
        expanded: false,
        size: 6,
      });
    } else {
      const existing = nodes.get(quad.subject)!;
      existing.isSubject = true;
    }

    if (!nodes.has(quad.object)) {
      const objectType = (quad.objectType as RdfTermType) ?? "URI";
      nodes.set(quad.object, {
        id: quad.object,
        label: extractLabel(quad.object),
        type: objectType,
        isSubject: false,
        expanded: false,
        size: objectType === "LITERAL" ? 4 : 6,
      });
    }

    links.push({
      id: quadToEdgeId(quad),
      source: quad.subject,
      target: quad.object,
      predicate: quad.predicate,
      dataset: quad.dataset,
      label: extractLabel(quad.predicate),
    });
  }

  return { nodes: Array.from(nodes.values()), links };
}

export function mergeGraphData(
  existing: GraphData,
  incoming: GraphData,
  expandedNodeId?: string
): GraphData {
  const nodeMap = new Map(existing.nodes.map((n) => [n.id, n]));
  for (const node of incoming.nodes) {
    if (!nodeMap.has(node.id)) {
      nodeMap.set(node.id, { ...node });
    } else if (node.isSubject) {
      nodeMap.get(node.id)!.isSubject = true;
    }
  }
  if (expandedNodeId) {
    const target = nodeMap.get(expandedNodeId);
    if (target) target.expanded = true;
  }

  const linkIds = new Set(existing.links.map((l) => l.id));
  const merged: GraphEdge[] = [...existing.links];
  for (const link of incoming.links) {
    if (!linkIds.has(link.id)) {
      merged.push(link);
      linkIds.add(link.id);
    }
  }

  return { nodes: Array.from(nodeMap.values()), links: merged };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && pnpm test src/__tests__/lib/graph/transforms.test.ts`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/graph/transforms.ts \
        frontend/src/__tests__/lib/graph/transforms.test.ts
git commit -m "$(cat <<'EOF'
feat(frontend): add pure graph transform helpers

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: GraphQL queries

**Files:**
- Create: `frontend/src/graphql/graph.ts`

- [ ] **Step 1: Write the queries module**

```typescript
import { gql } from "@apollo/client";

const QUAD_FIELDS = `
  subject
  predicate
  object
  dataset
  objectType
  datatype
  language
`;

export const GRAPH_TRIPLES_QUERY = gql`
  query GraphTriples(
    $collectionId: ID!
    $subject: String
    $predicate: String
    $object: String
    $dataset: String
    $limit: Int
  ) {
    triples(
      collectionId: $collectionId
      subject: $subject
      predicate: $predicate
      object: $object
      dataset: $dataset
      limit: $limit
    ) {
      ${QUAD_FIELDS}
    }
  }
`;

export const NODE_NEIGHBORS_QUERY = gql`
  query NodeNeighbors($collectionId: ID!, $entityUri: String!, $limit: Int) {
    asSubject: triples(collectionId: $collectionId, subject: $entityUri, limit: $limit) {
      ${QUAD_FIELDS}
    }
    asObject: triples(collectionId: $collectionId, object: $entityUri, limit: $limit) {
      ${QUAD_FIELDS}
    }
  }
`;

export const ENTITY_SEARCH_QUERY = gql`
  query EntitySearch($collectionId: ID!, $prefix: String!, $limit: Int) {
    entitySearch(collectionId: $collectionId, prefix: $prefix, limit: $limit)
  }
`;

export const GRAPH_METADATA_QUERY = gql`
  query GraphMetadata($collectionId: ID!) {
    graphMetadata(collectionId: $collectionId) {
      datasets
      predicates
      entityTypes
    }
  }
`;
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/graphql/graph.ts
git commit -m "$(cat <<'EOF'
feat(frontend): add graph GraphQL queries

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: useGraphData hook

**Files:**
- Create: `frontend/src/hooks/useGraphData.ts`
- Create: `frontend/src/__tests__/hooks/useGraphData.test.tsx`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/__tests__/hooks/useGraphData.test.tsx`:

```tsx
import { describe, it, expect } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import { ReactNode } from "react";
import { useGraphData } from "@/hooks/useGraphData";
import {
  GRAPH_TRIPLES_QUERY,
  NODE_NEIGHBORS_QUERY,
} from "@/graphql/graph";

const quad = (s: string, p: string, o: string) => ({
  subject: s,
  predicate: p,
  object: o,
  dataset: "default",
  objectType: "URI",
  datatype: null,
  language: null,
});

const initialMock = {
  request: {
    query: GRAPH_TRIPLES_QUERY,
    variables: {
      collectionId: "c1",
      subject: null,
      predicate: null,
      object: null,
      dataset: null,
      limit: 500,
    },
  },
  result: {
    data: {
      triples: [quad("a", "p", "b"), quad("a", "p2", "c")],
    },
  },
};

const neighborMock = {
  request: {
    query: NODE_NEIGHBORS_QUERY,
    variables: { collectionId: "c1", entityUri: "b", limit: 50 },
  },
  result: {
    data: {
      asSubject: [quad("b", "p", "d")],
      asObject: [quad("a", "p", "b")],
    },
  },
};

const wrapper = (mocks: any[]) => {
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <MockedProvider mocks={mocks} addTypename={false}>{children}</MockedProvider>
  );
  return Wrapper;
};

describe("useGraphData", () => {
  it("loadInitial populates graphData", async () => {
    const { result } = renderHook(() => useGraphData("c1"), {
      wrapper: wrapper([initialMock]),
    });

    await act(async () => {
      await result.current.loadInitial({ datasets: [], predicates: [], entityTypes: [] });
    });

    await waitFor(() => {
      expect(result.current.graphData.nodes.length).toBeGreaterThan(0);
    });
    expect(result.current.graphData.nodes.map((n) => n.id).sort()).toEqual(["a", "b", "c"]);
  });

  it("expandNode merges neighbors without duplicates and marks expanded", async () => {
    const { result } = renderHook(() => useGraphData("c1"), {
      wrapper: wrapper([initialMock, neighborMock]),
    });

    await act(async () => {
      await result.current.loadInitial({ datasets: [], predicates: [], entityTypes: [] });
    });
    await act(async () => {
      await result.current.expandNode("b");
    });

    await waitFor(() => {
      expect(result.current.graphData.nodes.find((n) => n.id === "b")?.expanded).toBe(true);
    });
    // existing nodes a,b,c plus new d
    expect(result.current.graphData.nodes.map((n) => n.id).sort()).toEqual(["a", "b", "c", "d"]);
    // existing a-p-b link should not be duplicated
    const apb = result.current.graphData.links.filter(
      (l) => l.source === "a" && l.predicate === "p" && l.target === "b"
    );
    expect(apb).toHaveLength(1);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && pnpm test src/__tests__/hooks/useGraphData.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement the hook**

Create `frontend/src/hooks/useGraphData.ts`:

```typescript
"use client";

import { useState, useCallback } from "react";
import { useApolloClient } from "@apollo/client/react";
import {
  GRAPH_TRIPLES_QUERY,
  NODE_NEIGHBORS_QUERY,
} from "@/graphql/graph";
import { GraphData, GraphFilter, QuadDto } from "@/types/graph";
import { mergeGraphData, quadsToGraphData } from "@/lib/graph/transforms";

const INITIAL_LIMIT = 500;
const NEIGHBOR_LIMIT = 50;

export function useGraphData(collectionId: string) {
  const client = useApolloClient();
  const [graphData, setGraphData] = useState<GraphData>({ nodes: [], links: [] });

  const loadInitial = useCallback(
    async (filter: GraphFilter): Promise<void> => {
      if (!collectionId) return;
      const { data } = await client.query<{ triples: QuadDto[] }>({
        query: GRAPH_TRIPLES_QUERY,
        variables: {
          collectionId,
          subject: null,
          predicate: filter.predicates.length === 1 ? filter.predicates[0] : null,
          object: null,
          dataset: filter.datasets.length === 1 ? filter.datasets[0] : null,
          limit: INITIAL_LIMIT,
        },
        fetchPolicy: "network-only",
      });
      let next = quadsToGraphData(data?.triples ?? []);
      if (filter.entityTypes.length > 0) {
        next = filterByEntityTypes(next, data?.triples ?? [], filter.entityTypes);
      }
      setGraphData(next);
    },
    [client, collectionId]
  );

  const expandNode = useCallback(
    async (entityUri: string): Promise<void> => {
      if (!collectionId) return;
      const { data } = await client.query<{
        asSubject: QuadDto[];
        asObject: QuadDto[];
      }>({
        query: NODE_NEIGHBORS_QUERY,
        variables: { collectionId, entityUri, limit: NEIGHBOR_LIMIT },
        fetchPolicy: "network-only",
      });
      const all = [...(data?.asSubject ?? []), ...(data?.asObject ?? [])];
      const incoming = quadsToGraphData(all);
      setGraphData((prev) => mergeGraphData(prev, incoming, entityUri));
    },
    [client, collectionId]
  );

  const clear = useCallback(() => {
    setGraphData({ nodes: [], links: [] });
  }, []);

  return { graphData, loadInitial, expandNode, clear };
}

function filterByEntityTypes(
  data: GraphData,
  quads: QuadDto[],
  entityTypes: string[]
): GraphData {
  const RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
  const types = new Set(entityTypes);
  const allowedSubjects = new Set(
    quads.filter((q) => q.predicate === RDF_TYPE && types.has(q.object)).map((q) => q.subject)
  );
  if (allowedSubjects.size === 0) return data;
  const nodes = data.nodes.filter((n) => allowedSubjects.has(n.id) || !n.isSubject);
  const ids = new Set(nodes.map((n) => n.id));
  const links = data.links.filter((l) => ids.has(l.source) && ids.has(l.target));
  return { nodes, links };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && pnpm test src/__tests__/hooks/useGraphData.test.tsx`
Expected: Both tests PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/hooks/useGraphData.ts \
        frontend/src/__tests__/hooks/useGraphData.test.tsx
git commit -m "$(cat <<'EOF'
feat(frontend): add useGraphData hook with loadInitial and expandNode

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: NodeDetail component

**Files:**
- Create: `frontend/src/components/graph/NodeDetail.tsx`
- Create: `frontend/src/__tests__/components/graph/NodeDetail.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MockedProvider } from "@apollo/client/testing/react";
import { GRAPH_TRIPLES_QUERY } from "@/graphql/graph";
import { NodeDetail } from "@/components/graph/NodeDetail";
import { GraphNode } from "@/types/graph";

const node: GraphNode = {
  id: "http://ex.org/a",
  label: "a",
  type: "URI",
  isSubject: true,
  expanded: false,
  size: 6,
};

const triplesMock = {
  request: {
    query: GRAPH_TRIPLES_QUERY,
    variables: {
      collectionId: "c1",
      subject: "http://ex.org/a",
      predicate: null,
      object: null,
      dataset: null,
      limit: 50,
    },
  },
  result: {
    data: {
      triples: [
        {
          subject: "http://ex.org/a",
          predicate: "http://ex.org/p",
          object: "value1",
          dataset: "default",
          objectType: "LITERAL",
          datatype: null,
          language: null,
        },
      ],
    },
  },
};

describe("NodeDetail", () => {
  it("loads and renders triples for the node", async () => {
    render(
      <MockedProvider mocks={[triplesMock]} addTypename={false}>
        <NodeDetail node={node} collectionId="c1" onExpand={vi.fn()} onClose={vi.fn()} />
      </MockedProvider>
    );
    await waitFor(() => {
      expect(screen.getByText(/value1/)).toBeInTheDocument();
    });
  });

  it("calls onExpand when 'Nachbarn laden' clicked", async () => {
    const onExpand = vi.fn();
    const user = userEvent.setup();
    render(
      <MockedProvider mocks={[triplesMock]} addTypename={false}>
        <NodeDetail node={node} collectionId="c1" onExpand={onExpand} onClose={vi.fn()} />
      </MockedProvider>
    );
    const btn = await screen.findByRole("button", { name: /Nachbarn laden/i });
    await user.click(btn);
    expect(onExpand).toHaveBeenCalledWith("http://ex.org/a");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && pnpm test src/__tests__/components/graph/NodeDetail.test.tsx`
Expected: FAIL — component not found.

- [ ] **Step 3: Implement the component**

Create `frontend/src/components/graph/NodeDetail.tsx`:

```tsx
"use client";

import { useQuery } from "@apollo/client/react";
import { GRAPH_TRIPLES_QUERY } from "@/graphql/graph";
import { GraphNode, QuadDto } from "@/types/graph";

interface NodeDetailProps {
  node: GraphNode;
  collectionId: string;
  onExpand: (entityUri: string) => void;
  onClose: () => void;
}

interface TriplesData {
  triples: QuadDto[];
}

export function NodeDetail({ node, collectionId, onExpand, onClose }: NodeDetailProps) {
  const { data, loading } = useQuery<TriplesData>(GRAPH_TRIPLES_QUERY, {
    variables: {
      collectionId,
      subject: node.id,
      predicate: null,
      object: null,
      dataset: null,
      limit: 50,
    },
  });

  return (
    <aside className="w-96 border-l bg-white p-4 overflow-y-auto">
      <div className="flex justify-between items-center mb-4">
        <h3 className="font-semibold truncate">{node.label}</h3>
        <button
          onClick={onClose}
          className="text-gray-500 hover:text-gray-700"
          aria-label="Schließen"
        >
          ×
        </button>
      </div>

      <div className="mb-4">
        <span className="text-xs uppercase text-gray-500">{node.type}</span>
        <p className="text-sm text-gray-700 break-all">{node.id}</p>
      </div>

      {!node.expanded && (
        <button
          onClick={() => onExpand(node.id)}
          className="mb-4 px-3 py-1 text-sm bg-blue-600 text-white rounded hover:bg-blue-700"
        >
          Nachbarn laden
        </button>
      )}

      <h4 className="font-medium text-sm mb-2">
        Triples ({data?.triples?.length ?? 0})
      </h4>
      {loading ? (
        <p className="text-sm text-gray-500">Laden...</p>
      ) : (
        <div className="space-y-2">
          {data?.triples?.map((t, i) => (
            <div key={i} className="text-xs border rounded p-2 bg-gray-50">
              <div>
                <span className="font-medium">Prädikat:</span> {t.predicate}
              </div>
              <div>
                <span className="font-medium">Objekt:</span> {t.object}
              </div>
              <div className="text-gray-400">Dataset: {t.dataset}</div>
            </div>
          ))}
        </div>
      )}
    </aside>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && pnpm test src/__tests__/components/graph/NodeDetail.test.tsx`
Expected: Both tests PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/graph/NodeDetail.tsx \
        frontend/src/__tests__/components/graph/NodeDetail.test.tsx
git commit -m "$(cat <<'EOF'
feat(frontend): add NodeDetail panel for graph explorer

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: GraphFilter and GraphControls components

**Files:**
- Create: `frontend/src/components/graph/GraphFilter.tsx`
- Create: `frontend/src/components/graph/GraphControls.tsx`
- Create: `frontend/src/__tests__/components/graph/GraphFilter.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { GraphFilter } from "@/components/graph/GraphFilter";

describe("GraphFilter", () => {
  it("renders dataset, predicate and entityType selects", () => {
    render(
      <GraphFilter
        filter={{ datasets: [], predicates: [], entityTypes: [] }}
        availableDatasets={["d1", "d2"]}
        availablePredicates={["p1"]}
        availableTypes={["t1"]}
        onChange={vi.fn()}
      />
    );
    expect(screen.getByLabelText(/Dataset/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Prädikat/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Typ/i)).toBeInTheDocument();
  });

  it("calls onChange when dataset selected", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(
      <GraphFilter
        filter={{ datasets: [], predicates: [], entityTypes: [] }}
        availableDatasets={["d1", "d2"]}
        availablePredicates={[]}
        availableTypes={[]}
        onChange={onChange}
      />
    );
    await user.selectOptions(screen.getByLabelText(/Dataset/i), "d1");
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ datasets: ["d1"] })
    );
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && pnpm test src/__tests__/components/graph/GraphFilter.test.tsx`
Expected: FAIL — component missing.

- [ ] **Step 3: Implement GraphFilter**

Create `frontend/src/components/graph/GraphFilter.tsx`:

```tsx
"use client";

import { GraphFilter as FilterType } from "@/types/graph";

interface GraphFilterProps {
  filter: FilterType;
  availableDatasets: string[];
  availablePredicates: string[];
  availableTypes: string[];
  onChange: (filter: FilterType) => void;
}

export function GraphFilter({
  filter,
  availableDatasets,
  availablePredicates,
  availableTypes,
  onChange,
}: GraphFilterProps) {
  const handle = (key: keyof FilterType) => (e: React.ChangeEvent<HTMLSelectElement>) => {
    const selected = Array.from(e.target.selectedOptions).map((o) => o.value).filter(Boolean);
    onChange({ ...filter, [key]: selected });
  };

  return (
    <div className="flex flex-wrap gap-4 p-3 border-b bg-gray-50">
      <label className="flex flex-col text-xs text-gray-600">
        Dataset
        <select
          multiple
          aria-label="Dataset"
          value={filter.datasets}
          onChange={handle("datasets")}
          className="text-sm border rounded px-2 py-1 min-w-[140px]"
        >
          {availableDatasets.map((d) => (
            <option key={d} value={d}>{d}</option>
          ))}
        </select>
      </label>
      <label className="flex flex-col text-xs text-gray-600">
        Prädikat
        <select
          multiple
          aria-label="Prädikat"
          value={filter.predicates}
          onChange={handle("predicates")}
          className="text-sm border rounded px-2 py-1 min-w-[140px]"
        >
          {availablePredicates.map((p) => (
            <option key={p} value={p}>{p}</option>
          ))}
        </select>
      </label>
      <label className="flex flex-col text-xs text-gray-600">
        Typ
        <select
          multiple
          aria-label="Typ"
          value={filter.entityTypes}
          onChange={handle("entityTypes")}
          className="text-sm border rounded px-2 py-1 min-w-[140px]"
        >
          {availableTypes.map((t) => (
            <option key={t} value={t}>{t}</option>
          ))}
        </select>
      </label>
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && pnpm test src/__tests__/components/graph/GraphFilter.test.tsx`
Expected: Both tests PASS.

- [ ] **Step 5: Implement GraphControls (no separate test — UI-only sliders)**

Create `frontend/src/components/graph/GraphControls.tsx`:

```tsx
"use client";

import { LayoutConfig } from "@/types/graph";

interface GraphControlsProps {
  config: LayoutConfig;
  onChange: (config: LayoutConfig) => void;
  onResetView: () => void;
}

export const DEFAULT_LAYOUT: LayoutConfig = {
  chargeStrength: -150,
  linkDistance: 80,
  centerStrength: 0.05,
  collisionRadius: 20,
};

export function GraphControls({ config, onChange, onResetView }: GraphControlsProps) {
  return (
    <div className="p-3 border-b bg-gray-50 flex items-center gap-6">
      <label className="flex items-center gap-2 text-sm">
        Abstoßung
        <input
          type="range"
          min={-500}
          max={-10}
          value={config.chargeStrength}
          onChange={(e) =>
            onChange({ ...config, chargeStrength: Number(e.target.value) })
          }
        />
      </label>
      <label className="flex items-center gap-2 text-sm">
        Kantenlänge
        <input
          type="range"
          min={20}
          max={300}
          value={config.linkDistance}
          onChange={(e) =>
            onChange({ ...config, linkDistance: Number(e.target.value) })
          }
        />
      </label>
      <button
        onClick={() => onChange(DEFAULT_LAYOUT)}
        className="text-sm text-gray-600 hover:text-gray-900"
      >
        Zurücksetzen
      </button>
      <button
        onClick={onResetView}
        className="text-sm text-gray-600 hover:text-gray-900"
      >
        Ansicht zentrieren
      </button>
    </div>
  );
}
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/graph/GraphFilter.tsx \
        frontend/src/components/graph/GraphControls.tsx \
        frontend/src/__tests__/components/graph/GraphFilter.test.tsx
git commit -m "$(cat <<'EOF'
feat(frontend): add GraphFilter and GraphControls components

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: EntitySearch component

**Files:**
- Create: `frontend/src/components/graph/EntitySearch.tsx`
- Create: `frontend/src/__tests__/components/graph/EntitySearch.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MockedProvider } from "@apollo/client/testing/react";
import { ENTITY_SEARCH_QUERY } from "@/graphql/graph";
import { EntitySearch } from "@/components/graph/EntitySearch";

const searchMock = {
  request: {
    query: ENTITY_SEARCH_QUERY,
    variables: { collectionId: "c1", prefix: "ali", limit: 20 },
  },
  result: {
    data: {
      entitySearch: ["http://ex.org/Alice", "http://ex.org/Aligator"],
    },
  },
};

describe("EntitySearch", () => {
  it("shows suggestions after typing and triggers onSelect on click", async () => {
    const onSelect = vi.fn();
    const user = userEvent.setup();

    render(
      <MockedProvider mocks={[searchMock]} addTypename={false}>
        <EntitySearch collectionId="c1" onSelect={onSelect} />
      </MockedProvider>
    );

    const input = screen.getByPlaceholderText(/Entity suchen/i);
    await user.type(input, "ali");

    const option = await screen.findByText("http://ex.org/Alice");
    await user.click(option);

    await waitFor(() => {
      expect(onSelect).toHaveBeenCalledWith("http://ex.org/Alice");
    });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && pnpm test src/__tests__/components/graph/EntitySearch.test.tsx`
Expected: FAIL — module missing.

- [ ] **Step 3: Implement the component**

Create `frontend/src/components/graph/EntitySearch.tsx`:

```tsx
"use client";

import { useState } from "react";
import { useLazyQuery } from "@apollo/client/react";
import { ENTITY_SEARCH_QUERY } from "@/graphql/graph";

interface EntitySearchProps {
  collectionId: string;
  onSelect: (entityUri: string) => void;
}

interface SearchData {
  entitySearch: string[];
}

export function EntitySearch({ collectionId, onSelect }: EntitySearchProps) {
  const [value, setValue] = useState("");
  const [search, { data }] = useLazyQuery<SearchData>(ENTITY_SEARCH_QUERY);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const next = e.target.value;
    setValue(next);
    if (next.length >= 2 && collectionId) {
      void search({ variables: { collectionId, prefix: next, limit: 20 } });
    }
  };

  return (
    <div className="relative">
      <input
        type="text"
        value={value}
        onChange={handleChange}
        placeholder="Entity suchen…"
        className="text-sm border rounded px-3 py-1 w-72"
      />
      {data?.entitySearch && data.entitySearch.length > 0 && (
        <ul className="absolute z-10 mt-1 w-full bg-white border rounded shadow text-sm max-h-64 overflow-auto">
          {data.entitySearch.map((uri) => (
            <li
              key={uri}
              onClick={() => {
                onSelect(uri);
                setValue("");
              }}
              className="px-3 py-2 hover:bg-gray-100 cursor-pointer truncate"
            >
              {uri}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && pnpm test src/__tests__/components/graph/EntitySearch.test.tsx`
Expected: Test PASSES.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/graph/EntitySearch.tsx \
        frontend/src/__tests__/components/graph/EntitySearch.test.tsx
git commit -m "$(cat <<'EOF'
feat(frontend): add EntitySearch autocomplete

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: GraphCanvas component

**Files:**
- Create: `frontend/src/components/graph/GraphCanvas.tsx`
- Create: `frontend/src/__tests__/components/graph/GraphCanvas.test.tsx`

- [ ] **Step 1: Implement GraphCanvas**

Create `frontend/src/components/graph/GraphCanvas.tsx`:

```tsx
"use client";

import dynamic from "next/dynamic";
import { useEffect, useImperativeHandle, useRef, forwardRef, useCallback } from "react";
import { GraphData, GraphNode, LayoutConfig } from "@/types/graph";

// react-force-graph-2d touches `window` at module load time -> ssr disabled.
const ForceGraph2D = dynamic(() => import("react-force-graph-2d"), { ssr: false });

export interface GraphCanvasHandle {
  zoomToFit: (ms?: number) => void;
  centerOnNode: (nodeId: string) => void;
}

interface GraphCanvasProps {
  data: GraphData;
  layoutConfig: LayoutConfig;
  selectedNodeId: string | null;
  onNodeClick: (node: GraphNode) => void;
  onNodeRightClick: (node: GraphNode) => void;
}

const NODE_COLORS: Record<string, string> = {
  URI: "#4F46E5",
  LITERAL: "#059669",
  BLANK_NODE: "#D97706",
  QUOTED_TRIPLE: "#7C3AED",
};

export const GraphCanvas = forwardRef<GraphCanvasHandle, GraphCanvasProps>(function GraphCanvas(
  { data, layoutConfig, selectedNodeId, onNodeClick, onNodeRightClick },
  ref
) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const fgRef = useRef<any>(null);

  useEffect(() => {
    const fg = fgRef.current;
    if (!fg) return;
    fg.d3Force("charge")?.strength(layoutConfig.chargeStrength);
    fg.d3Force("link")?.distance(layoutConfig.linkDistance);
    fg.d3Force("center")?.strength(layoutConfig.centerStrength);
  }, [layoutConfig]);

  useImperativeHandle(ref, () => ({
    zoomToFit: (ms = 400) => {
      fgRef.current?.zoomToFit(ms);
    },
    centerOnNode: (nodeId: string) => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const node = (data.nodes as any[]).find((n) => n.id === nodeId);
      if (node && typeof node.x === "number") {
        fgRef.current?.centerAt(node.x, node.y, 600);
        fgRef.current?.zoom(2, 600);
      }
    },
  }));

  const nodeCanvasObject = useCallback(
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (node: any, ctx: CanvasRenderingContext2D, globalScale: number) => {
      const label = node.label.length > 20 ? node.label.slice(0, 20) + "…" : node.label;
      const fontSize = 12 / globalScale;
      const isSelected = node.id === selectedNodeId;

      ctx.beginPath();
      ctx.arc(node.x, node.y, node.size, 0, 2 * Math.PI);
      ctx.fillStyle = isSelected ? "#EF4444" : NODE_COLORS[node.type] ?? "#6B7280";
      ctx.fill();

      if (isSelected) {
        ctx.strokeStyle = "#EF4444";
        ctx.lineWidth = 2 / globalScale;
        ctx.stroke();
      }

      ctx.font = `${fontSize}px Sans-Serif`;
      ctx.textAlign = "center";
      ctx.textBaseline = "top";
      ctx.fillStyle = "#1F2937";
      ctx.fillText(label, node.x, node.y + node.size + 2);
    },
    [selectedNodeId]
  );

  return (
    <div data-testid="graph-canvas" className="w-full h-full">
      <ForceGraph2D
        // eslint-disable-next-line @typescript-eslint/ban-ts-comment
        // @ts-ignore - dynamic import loses ref typing
        ref={fgRef}
        graphData={data}
        nodeCanvasObject={nodeCanvasObject}
        nodePointerAreaPaint={(node: any, color: string, ctx: CanvasRenderingContext2D) => {
          ctx.fillStyle = color;
          ctx.beginPath();
          ctx.arc(node.x, node.y, node.size + 2, 0, 2 * Math.PI);
          ctx.fill();
        }}
        onNodeClick={onNodeClick}
        onNodeRightClick={onNodeRightClick}
        linkLabel={(l: any) => l.label}
        linkDirectionalArrowLength={4}
        linkDirectionalArrowRelPos={1}
        linkColor={() => "#9CA3AF"}
      />
    </div>
  );
});
```

- [ ] **Step 2: Write the test**

Create `frontend/src/__tests__/components/graph/GraphCanvas.test.tsx`. The test depends on whether `canvas` polyfill installed in Task 6.

**If `canvas` package was installed successfully**, use this test:

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { GraphCanvas } from "@/components/graph/GraphCanvas";
import { GraphData } from "@/types/graph";

const data: GraphData = {
  nodes: [
    { id: "a", label: "a", type: "URI", isSubject: true, expanded: false, size: 6 },
    { id: "b", label: "b", type: "URI", isSubject: false, expanded: false, size: 6 },
  ],
  links: [
    { id: "a|p|b|d", source: "a", target: "b", predicate: "p", dataset: "d", label: "p" },
  ],
};

describe("GraphCanvas", () => {
  it("renders the canvas wrapper without throwing", async () => {
    render(
      <GraphCanvas
        data={data}
        layoutConfig={{ chargeStrength: -150, linkDistance: 80, centerStrength: 0.05, collisionRadius: 20 }}
        selectedNodeId={null}
        onNodeClick={vi.fn()}
        onNodeRightClick={vi.fn()}
      />
    );
    expect(await screen.findByTestId("graph-canvas")).toBeInTheDocument();
  });
});
```

**If `canvas` failed to install (Task 6 fallback)**, use this mocked variant instead:

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";

vi.mock("next/dynamic", () => ({
  default: () => {
    const Mock = (props: any) => <div data-testid="force-graph-mock" data-nodes={props.graphData.nodes.length} />;
    return Mock;
  },
}));

import { GraphCanvas } from "@/components/graph/GraphCanvas";
import { GraphData } from "@/types/graph";

const data: GraphData = {
  nodes: [
    { id: "a", label: "a", type: "URI", isSubject: true, expanded: false, size: 6 },
    { id: "b", label: "b", type: "URI", isSubject: false, expanded: false, size: 6 },
  ],
  links: [
    { id: "a|p|b|d", source: "a", target: "b", predicate: "p", dataset: "d", label: "p" },
  ],
};

describe("GraphCanvas", () => {
  it("forwards graphData to ForceGraph2D", async () => {
    render(
      <GraphCanvas
        data={data}
        layoutConfig={{ chargeStrength: -150, linkDistance: 80, centerStrength: 0.05, collisionRadius: 20 }}
        selectedNodeId={null}
        onNodeClick={vi.fn()}
        onNodeRightClick={vi.fn()}
      />
    );
    const mock = await screen.findByTestId("force-graph-mock");
    expect(mock.getAttribute("data-nodes")).toBe("2");
  });
});
```

- [ ] **Step 3: Run test**

Run: `cd frontend && pnpm test src/__tests__/components/graph/GraphCanvas.test.tsx`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/graph/GraphCanvas.tsx \
        frontend/src/__tests__/components/graph/GraphCanvas.test.tsx
git commit -m "$(cat <<'EOF'
feat(frontend): add GraphCanvas force-directed visualization

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: Graph page

**Files:**
- Create: `frontend/src/app/graph/page.tsx`

- [ ] **Step 1: Implement the page**

Create `frontend/src/app/graph/page.tsx`:

```tsx
"use client";

import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@apollo/client/react";
import { GraphCanvas, GraphCanvasHandle } from "@/components/graph/GraphCanvas";
import { GraphControls, DEFAULT_LAYOUT } from "@/components/graph/GraphControls";
import { GraphFilter } from "@/components/graph/GraphFilter";
import { NodeDetail } from "@/components/graph/NodeDetail";
import { EntitySearch } from "@/components/graph/EntitySearch";
import { CollectionSelector } from "@/components/documents/CollectionSelector";
import { useGraphData } from "@/hooks/useGraphData";
import { useActiveCollection } from "@/lib/collection-store";
import { GRAPH_METADATA_QUERY } from "@/graphql/graph";
import { GraphFilter as FilterType, GraphNode, LayoutConfig } from "@/types/graph";

interface MetaData {
  graphMetadata: {
    datasets: string[];
    predicates: string[];
    entityTypes: string[];
  };
}

export default function GraphPage() {
  const { collectionId } = useActiveCollection();
  const searchParams = useSearchParams();
  const initialEntity = searchParams.get("entity");

  const [selectedNode, setSelectedNode] = useState<GraphNode | null>(null);
  const [layoutConfig, setLayoutConfig] = useState<LayoutConfig>(DEFAULT_LAYOUT);
  const [filter, setFilter] = useState<FilterType>({
    datasets: [],
    predicates: [],
    entityTypes: [],
  });

  const canvasRef = useRef<GraphCanvasHandle | null>(null);
  const { graphData, loadInitial, expandNode } = useGraphData(collectionId ?? "");

  const { data: metaData } = useQuery<MetaData>(GRAPH_METADATA_QUERY, {
    variables: { collectionId },
    skip: !collectionId,
  });

  // Initial load whenever collection or filter changes.
  useEffect(() => {
    if (collectionId) {
      void loadInitial(filter);
    }
  }, [collectionId, filter, loadInitial]);

  // If ?entity=... is set, expand it once after initial load.
  useEffect(() => {
    if (collectionId && initialEntity && graphData.nodes.length > 0) {
      void expandNode(initialEntity).then(() => {
        canvasRef.current?.centerOnNode(initialEntity);
      });
    }
    // intentionally only on mount-ish: collection + initialEntity
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [collectionId, initialEntity]);

  return (
    <main className="h-screen flex flex-col">
      <header className="flex items-center gap-4 p-4 border-b">
        <h1 className="text-xl font-bold">Graph Explorer</h1>
        <CollectionSelector />
        {collectionId && (
          <EntitySearch
            collectionId={collectionId}
            onSelect={(uri) => {
              void expandNode(uri).then(() => canvasRef.current?.centerOnNode(uri));
            }}
          />
        )}
      </header>
      <GraphFilter
        filter={filter}
        availableDatasets={metaData?.graphMetadata.datasets ?? []}
        availablePredicates={metaData?.graphMetadata.predicates ?? []}
        availableTypes={metaData?.graphMetadata.entityTypes ?? []}
        onChange={setFilter}
      />
      <GraphControls
        config={layoutConfig}
        onChange={setLayoutConfig}
        onResetView={() => canvasRef.current?.zoomToFit(400)}
      />
      <div className="flex-1 flex overflow-hidden">
        <div className="flex-1 relative">
          <GraphCanvas
            ref={canvasRef}
            data={graphData}
            layoutConfig={layoutConfig}
            selectedNodeId={selectedNode?.id ?? null}
            onNodeClick={setSelectedNode}
            onNodeRightClick={(node) => void expandNode(node.id)}
          />
        </div>
        {selectedNode && collectionId && (
          <NodeDetail
            node={selectedNode}
            collectionId={collectionId}
            onExpand={(uri) => void expandNode(uri)}
            onClose={() => setSelectedNode(null)}
          />
        )}
      </div>
    </main>
  );
}
```

- [ ] **Step 2: Build verification**

Run: `cd frontend && pnpm build`
Expected: Build succeeds. If TypeScript errors, fix them inline.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/graph/page.tsx
git commit -m "$(cat <<'EOF'
feat(frontend): add /graph explorer page

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 16: Final verification

- [ ] **Step 1: Run frontend test suite**

Run: `cd frontend && pnpm test`
Expected: All tests pass.

- [ ] **Step 2: Run backend test suite for changed packages**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.*" --tests "com.agentwork.graphmesh.api.*"`
Expected: All tests pass; pre-existing failures (if any) match the baseline tracked in memory.

- [ ] **Step 3: Manual smoke check (optional, requires running stack)**

If docker-compose stack is running:
1. Start backend: `./gradlew bootRun`
2. Start frontend: `cd frontend && pnpm dev`
3. Visit `http://localhost:3000/graph`
4. Pick a collection that has triples → see force-graph render
5. Click a node → detail panel
6. Click "Nachbarn laden" → graph expands

- [ ] **Step 4: Write Done file**

Create `docs/features/34-graph-explorer-ui-done.md`:

```markdown
# Feature 34: Graph Explorer UI — Done

## Zusammenfassung

Interaktive Force-Directed-Graphvisualisierung in Next.js, gestützt auf drei neue
GraphQL-Endpunkte (`triples` mit `limit`, `entitySearch`, `graphMetadata`).
Knoten-Klick öffnet Detail-Panel; Rechtsklick / „Nachbarn laden" expandiert den
Subgraphen ohne Duplikate; Filter-, Layout- und Suchsteuerung funktionieren.

## Abweichungen vom Plan

- Quad-Schema ist flach (subject/predicate/object: String, dataset, objectType, …)
  — verschachtelte RdfTerm-Struktur des Feature-Dokuments wurde nicht übernommen.
- Feldname `dataset` statt `graph`.
- Entity-Suche und Filter-Optionen werden über die neuen Backend-Queries bedient
  (anstatt vectorSearch oder Client-Aggregation).
- `/graph?entity=<uri>` als Query-Param statt zweite Route.
{{ZUSÄTZLICHE ABWEICHUNGEN BEI BEDARF DOKUMENTIEREN}}

## Tech Debt / Offen

- `findSubjects` und `aggregateMetadata` machen Full-Scans über alle Quads einer
  Collection. Für große Collections später durch CQL-Materialized-Views ersetzen.
- Backend-`triples` unterstützt nur ein Dataset/Prädikat als Filter; Multi-Select
  im Frontend fällt teilweise auf Client-Side-Filterung zurück.
- Falls `canvas`-Polyfill installiert wurde: System-Lib-Abhängigkeit für CI prüfen.
```

- [ ] **Step 5: Commit Done file**

```bash
git add docs/features/34-graph-explorer-ui-done.md
git commit -m "$(cat <<'EOF'
docs(features): mark Feature 34 Graph Explorer UI as done

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review Notes

- **Spec coverage:** all 14 acceptance criteria → tasks 1–15 (backend: 1–5; frontend: 6–15).
- **Type consistency:** `GraphMetadataView` (Kotlin) ↔ `GraphMetadata` (GraphQL) ↔ `metaData.graphMetadata` (frontend) — names cross-checked. `quadsToGraphData` / `mergeGraphData` / `extractLabel` / `inferSubjectType` / `quadToEdgeId` referenced consistently.
- **Placeholder scan:** No TBDs. The Done file template has one `{{...}}` placeholder for ad-hoc divergences — this is intentional.
- **Parallelizable tasks:** Tasks 7, 8, 9 are independent after Task 1 (schema). Task 11 (NodeDetail), 12 (Filter/Controls), 13 (EntitySearch) only depend on Tasks 7+9. Task 14 (GraphCanvas) depends on Task 7. Task 15 depends on everything.
