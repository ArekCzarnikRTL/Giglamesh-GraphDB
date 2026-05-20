# PR 3 — Cross-Feature Call Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate all remaining cross-feature rule violations after PR 1 and PR 2. Every cross-feature call must go through the target feature's `application/port/in/` interface. No feature may inject a concrete service class, internal domain type, or internal implementation detail from another feature.

**Architecture:** This PR is an audit-and-fix pass. For each violation found, either (a) update the caller to use an existing input port interface from PR 1, or (b) introduce a new narrow interface or move a shared type to `common/domain/` where there is no suitable port yet.

**Tech Stack:** Kotlin, Spring Boot 4.0.5, Gradle 9.4.1. Build: `./gradlew build`. Tests: `./gradlew test`.

**Prerequisites:** PR 1 and PR 2 merged into `feat/layer-to-hexarch-switch`.

---

## Known Violations (from static analysis of imports)

Run this to get a fresh list after PR 1 and PR 2:

```bash
grep -rn "import com.agentwork.graphmesh\." src/main/kotlin/com/agentwork/graphmesh/ --include="*.kt" | python3 -c "
import sys, re
for line in sys.stdin:
    m = re.match(r'(src/main/kotlin/com/agentwork/graphmesh/(\w+)/.*?\.kt):\d+:(import com\.agentwork\.graphmesh\.(\w+)\..*)', line.strip())
    if m:
        src_feature, dep_feature, dep = m.group(2), m.group(4), m.group(3).replace('import com.agentwork.graphmesh.', '')
        skip = {'rdf', 'tenant', 'messaging', 'llm', 'storage', 'common'}
        if src_feature != dep_feature and dep_feature not in skip and src_feature not in skip:
            print(f'{src_feature} -> {dep_feature}: {dep}')
" | sort -u
```

After PR 1 and PR 2, the remaining violations are expected to be:

| Source feature | Violates | Dep class | Fix |
|---|---|---|---|
| `api.PurgeService` | rule 6 | `librarian.application.port.out.DocumentStore` (output port of another feature) | Introduce `PurgeLibrarianUseCase` output port in `librarian` or have PurgeService use `LibrarianUseCases` from PR 1 |
| `collection.CollectionLifecycleManager` | rule 6 | `librarian.application.port.out.DocumentStore` | Same — `CollectionLifecycleManager` must use `LibrarianUseCases` |
| `rdfimport.RdfImportService` | rule 6 | `dynamicgraphql.DynamicGraphQlSchemaBuilder` (concrete class) | Add `DynamicSchemaUseCase` input port to `dynamicgraphql`; make `DynamicGraphQlSchemaBuilder` implement it |
| `rdfimport.RdfImportService` | rule 6 | `ontology.JenaAdapter` (internal adapter) | Expose parsing via `OntologyUseCases.parseTurtle(content, format): ParsedRdf` or move the parser to shared `rdf/` utility |
| `ontology.*` | rule 6 | `skos.SkosConcept`, `skos.SkosConceptScheme` (domain types) | Move these types to `common/domain/skos/` or accept them as a declared shared type |
| `dynamicgraphql.*` | rule 6 | `collection.CollectionEvent`, `collection.CollectionEventType` (domain events) | Move to `common/domain/` or expose via collection's port |

Verify each violation by running the script above before implementing.

---

## Task 1: Fix `api/PurgeService` — stop injecting `DocumentStore` directly

**Context:** `api/PurgeService` currently injects `librarian.DocumentStore` (an output port of `librarian`) to call `findByCollection` and `deleteWithChildren`. After PR 1, `LibrarianUseCases` already exposes `findByCollection` and `deleteDocument` — use those instead.

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/api/PurgeService.kt`

- [ ] **Step 1: Replace DocumentStore injection with LibrarianUseCases**

In `api/PurgeService.kt`:

Current constructor:
```kotlin
class PurgeService(
    private val collectionService: CollectionUseCases,       // already from PR 1
    private val documentStore: DocumentStore,                 // ← VIOLATION
    private val ontologyUseCases: OntologyUseCases,          // already from PR 1
    private val kafkaAdmin: KafkaAdmin,
    private val orphanSweepService: OrphanSweepUseCase,      // already from PR 1
)
```

Replace `documentStore: DocumentStore` with `librarianUseCases: LibrarianUseCases`:
```kotlin
class PurgeService(
    private val collectionService: CollectionUseCases,
    private val librarianUseCases: LibrarianUseCases,
    private val ontologyUseCases: OntologyUseCases,
    private val kafkaAdmin: KafkaAdmin,
    private val orphanSweepService: OrphanSweepUseCase,
)
```

- [ ] **Step 2: Update usage in `purgeAll()`**

Current usage:
```kotlin
val docs = documentStore.findByCollection(collection.id, DocumentType.SOURCE)
for (doc in docs) {
    documentStore.deleteWithChildren(doc.id)
    ...
}
```

Replace with:
```kotlin
val docs = librarianUseCases.findByCollection(collection.id, DocumentType.SOURCE)
for (doc in docs) {
    librarianUseCases.deleteDocument(doc.id)
    ...
}
```

Note: `LibrarianUseCases.deleteDocument` calls `documentStore.deleteWithChildren` internally, so this is semantically equivalent.

- [ ] **Step 3: Remove stale DocumentStore import**

Remove: `import com.agentwork.graphmesh.librarian.application.port.out.DocumentStore`
Add (if not already present): `import com.agentwork.graphmesh.librarian.application.port.`in`.LibrarianUseCases`

- [ ] **Step 4: Build**

```bash
./gradlew build -x test
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/api/PurgeService.kt
git commit -m "refactor(api): PurgeService uses LibrarianUseCases instead of DocumentStore"
```

---

## Task 2: Fix `collection/CollectionLifecycleManager` — stop injecting `DocumentStore` directly

**Context:** `CollectionLifecycleManager` is in `collection/` but injects `librarian.DocumentStore` (another feature's output port). It must use `LibrarianUseCases` instead.

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/collection/CollectionLifecycleManager.kt`

- [ ] **Step 1: Check what CollectionLifecycleManager does with DocumentStore**

```bash
grep -n "documentStore\." src/main/kotlin/com/agentwork/graphmesh/collection/CollectionLifecycleManager.kt
```

Expected usage: finds documents in a collection and deletes them with their children during `purge(collectionId)`.

- [ ] **Step 2: Replace `DocumentStore` with `LibrarianUseCases` in the constructor**

In `CollectionLifecycleManager.kt`:
- Remove import: `import com.agentwork.graphmesh.librarian.application.port.out.DocumentStore`
- Add import: `import com.agentwork.graphmesh.librarian.application.port.`in`.LibrarianUseCases`
- Change constructor param: `private val documentStore: DocumentStore` → `private val librarianUseCases: LibrarianUseCases`
- Update call sites: `documentStore.findByCollection(...)` → `librarianUseCases.findByCollection(...)`, `documentStore.deleteWithChildren(...)` → `librarianUseCases.deleteDocument(...)`

- [ ] **Step 3: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/collection/CollectionLifecycleManager.kt
git commit -m "refactor(collection): CollectionLifecycleManager uses LibrarianUseCases"
```

---

## Task 3: Fix `rdfimport` — stop injecting `DynamicGraphQlSchemaBuilder` directly

**Context:** `rdfimport.RdfImportService` calls `dynamicgraphql.DynamicGraphQlSchemaBuilder.rebuild(collectionId)` after importing RDF, so that the GraphQL schema picks up the new ontology. `DynamicGraphQlSchemaBuilder` is a concrete class — `rdfimport` must use an input port interface instead.

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/application/port/in/SchemaRefreshUseCase.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlSchemaBuilder.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt`

- [ ] **Step 1: Verify the method being called**

```bash
grep -n "DynamicGraphQlSchemaBuilder\|schemaBuilder\." src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt
```

Expected: something like `schemaBuilder.rebuild(collectionId)` or `schemaBuilder.refreshSchema(collectionId)`. Note the exact method name.

- [ ] **Step 2: Create the input port interface**

Use the exact method name found in Step 1 (replace `refreshSchema` if the actual name differs):

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/application/port/in/SchemaRefreshUseCase.kt
package com.agentwork.graphmesh.dynamicgraphql.application.port.`in`

interface SchemaRefreshUseCase {
    fun refreshSchema(collectionId: String)
}
```

- [ ] **Step 3: Make DynamicGraphQlSchemaBuilder implement the interface**

In `DynamicGraphQlSchemaBuilder.kt`:
- Add import: `import com.agentwork.graphmesh.dynamicgraphql.application.port.`in`.SchemaRefreshUseCase`
- Change: `class DynamicGraphQlSchemaBuilder(...) : SchemaRefreshUseCase {`
- Add `override` to the method exposed by the interface.

- [ ] **Step 4: Update RdfImportService**

In `RdfImportService.kt`:
- Replace `import com.agentwork.graphmesh.dynamicgraphql.DynamicGraphQlSchemaBuilder` with `import com.agentwork.graphmesh.dynamicgraphql.application.port.`in`.SchemaRefreshUseCase`
- Change constructor param: `private val schemaBuilder: DynamicGraphQlSchemaBuilder` → `private val schemaRefreshUseCase: SchemaRefreshUseCase`
- Update call sites: `schemaBuilder.rebuild(...)` → `schemaRefreshUseCase.refreshSchema(...)`

- [ ] **Step 5: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/ \
        src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt
git commit -m "refactor(rdfimport): use SchemaRefreshUseCase instead of DynamicGraphQlSchemaBuilder"
```

---

## Task 4: Fix `rdfimport` — stop using `ontology.JenaAdapter` directly

**Context:** `rdfimport.RdfImportService` imports `ontology.JenaAdapter` to parse RDF formats. `JenaAdapter` is an internal adapter class of the `ontology` feature — it should not be accessible cross-feature.

`RdfImportService` uses it for two things: (1) parsing Turtle/RDF-XML string content into a Jena `Model`, and (2) converting a Jena `Model` to quads/triples. Both are pure parsing operations with no storage side effects.

**Decision:** Move the parsing utilities that `rdfimport` needs into `rdf/` (the shared infra package for RDF utilities), rather than exposing them via an ontology port (which would leak Jena types into the port interface).

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt`
- Check: `src/main/kotlin/com/agentwork/graphmesh/rdf/` for existing Jena utility classes

- [ ] **Step 1: Audit what JenaAdapter methods RdfImportService calls**

```bash
grep -n "jenaAdapter\." src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt
```

And read the method signatures in `OntologyService.kt` / `JenaAdapter.kt`:
```bash
grep -n "fun " src/main/kotlin/com/agentwork/graphmesh/ontology/JenaAdapter.kt
```

Note the exact methods used (e.g., `parseTurtle(content)`, `parseRdfXml(content)`, `fromJenaModel(model, metadata)`).

- [ ] **Step 2: Decide based on what you find**

**If `RdfImportService` only calls parse methods** (no `fromJenaModel` with OntologyMetadata):
- The Jena parsing calls can be done directly using the Jena API (`ModelFactory.createDefaultModel()`, `model.read(...)`) without going through `JenaAdapter`. Jena is already a compile-time dependency. Move the two private `parseContent` methods in `RdfImportService` to use Jena directly and remove the `JenaAdapter` injection.

**If `RdfImportService` calls `fromJenaModel` for ontology persistence** (needs `OntologyMetadata`):
- This use is a legitimate ontology operation — route it through `OntologyUseCases.importTurtle(key, content, metadata)` instead. Remove the direct `JenaAdapter` call.

- [ ] **Step 3: Implement the fix chosen in Step 2**

Remove `private val jenaAdapter: JenaAdapter` from `RdfImportService`'s constructor. Remove the corresponding import. Implement the chosen alternative (inline Jena calls or OntologyUseCases delegation).

- [ ] **Step 4: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt
git commit -m "refactor(rdfimport): remove direct JenaAdapter dependency"
```

---

## Task 5: Fix `ontology` — resolve cross-feature domain type usage of `skos.*`

**Context:** `ontology/` imports `skos.SkosConcept` and `skos.SkosConceptScheme` as return types. This means `ontology` depends on `skos`'s domain model. Per rule 6, no feature may access another feature's domain types directly.

- [ ] **Step 1: Audit where skos types appear in ontology/**

```bash
grep -rn "SkosConcept\|SkosConceptScheme" src/main/kotlin/com/agentwork/graphmesh/ontology/ --include="*.kt"
```

Identify which methods return these types and whether they belong to a method on an ontology service/store or are passed in as parameters.

- [ ] **Step 2: Choose resolution**

**Option A (preferred if skos types are used as read-only view data):** Move `SkosConcept` and `SkosConceptScheme` to `com.agentwork.graphmesh.common.domain.skos`. Both `skos` and `ontology` features import from `common.domain` instead of each other.

**Option B (if the coupling is minimal):** If `ontology/` only uses these types in one or two places as simple data transfer objects with no domain logic, introduce a local copy of the needed fields (e.g., a local `OntologyConceptRef(uri: String, label: String)`) and have `SkosService` produce that type when called from `ontology/`.

- [ ] **Step 3: Implement the chosen resolution**

If Option A: create `src/main/kotlin/com/agentwork/graphmesh/common/domain/skos/SkosConcept.kt` and `SkosConceptScheme.kt` with the same fields. Update imports in both `skos/` and `ontology/`.

If Option B: define the local type in `ontology/` and update the caller to map to it.

- [ ] **Step 4: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/ontology/ \
        src/main/kotlin/com/agentwork/graphmesh/skos/ \
        src/main/kotlin/com/agentwork/graphmesh/common/  # if Option A
git commit -m "refactor(ontology/skos): eliminate cross-feature domain type coupling"
```

---

## Task 6: Fix `dynamicgraphql` — stop using `collection.CollectionEvent` domain types

**Context:** `dynamicgraphql/` imports `collection.CollectionEvent` and `collection.CollectionEventType` to react to collection events via a `@EventListener`. These are domain types from another feature.

- [ ] **Step 1: Audit the event listener**

```bash
grep -rn "CollectionEvent\|CollectionEventType" src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/ --include="*.kt"
```

Identify the `@EventListener` method and its parameter type.

- [ ] **Step 2: Decide resolution**

Spring `ApplicationEventPublisher` events are a legitimate cross-feature communication mechanism. The listener receives the event type to react to it. The issue is that `dynamicgraphql` depends on `collection`'s internal domain event class.

**Preferred fix:** Move `CollectionEvent` (and `CollectionEventType`) to `com.agentwork.graphmesh.common.domain` since it is intended for cross-feature consumption. `collection` publishes to it; `dynamicgraphql` listens to it. Both import from `common.domain`.

- [ ] **Step 3: Move CollectionEvent to common/domain**

Create:
```
src/main/kotlin/com/agentwork/graphmesh/common/domain/CollectionEvent.kt
```

with the same content as the current `collection/CollectionEvent.kt` (or wherever it currently lives), updating only the package to `com.agentwork.graphmesh.common.domain`.

Update imports in:
- `collection/CollectionService.kt` (publishes the event)
- `collection/CollectionEventProducer.kt` (if it uses the type)
- `dynamicgraphql/` files that listen to the event

Delete the old file.

- [ ] **Step 4: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/common/ \
        src/main/kotlin/com/agentwork/graphmesh/collection/ \
        src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/
git commit -m "refactor: move CollectionEvent to common/domain for cross-feature use"
```

---

## Task 7: Add unit tests for the fixed cross-feature boundaries

Each fix in Tasks 1–6 removed a direct cross-feature dependency and routed through an input port. A unit test for each fix proves the wiring is correct and prevents regression.

Test strategy: construct the class under test with mocked input port interfaces (not concrete service classes). Verify it delegates correctly.

**Files to create:**
- `src/test/kotlin/com/agentwork/graphmesh/api/PurgeServiceTest.kt`
- `src/test/kotlin/com/agentwork/graphmesh/collection/CollectionLifecycleManagerTest.kt`
- `src/test/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportServiceTest.kt`

- [ ] **Step 1: Write `PurgeServiceTest`**

Verifies that `PurgeService.purgeAll()` calls `CollectionUseCases` and `LibrarianUseCases`, not `DocumentStore` directly (the Task 1 fix).

```kotlin
// src/test/kotlin/com/agentwork/graphmesh/api/PurgeServiceTest.kt
package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.collection.Collection
import com.agentwork.graphmesh.collection.application.port.`in`.CollectionUseCases
import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.librarian.application.port.`in`.LibrarianUseCases
import com.agentwork.graphmesh.ontology.application.port.`in`.OntologyUseCases
import com.agentwork.graphmesh.storage.application.port.`in`.OrphanSweepUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaAdmin
import kotlin.test.assertEquals

class PurgeServiceTest {

    private val collectionUseCases = mockk<CollectionUseCases>(relaxed = true)
    private val librarianUseCases = mockk<LibrarianUseCases>(relaxed = true)
    private val ontologyUseCases = mockk<OntologyUseCases>(relaxed = true)
    private val orphanSweepUseCase = mockk<OrphanSweepUseCase>(relaxed = true)
    private val kafkaAdmin = mockk<KafkaAdmin>(relaxed = true)

    // Adjust parameter names to match the final PurgeService constructor after Task 1
    private val purgeService = PurgeService(
        collectionService = collectionUseCases,
        librarianUseCases = librarianUseCases,
        ontologyUseCases = ontologyUseCases,
        kafkaAdmin = kafkaAdmin,
        orphanSweepService = orphanSweepUseCase,
    )

    @Test
    fun `purgeAll calls deleteDocument for each source document in each collection`() {
        val col = Collection(id = "col-1", name = "test")
        val doc = mockk<Document>()
        every { doc.id } returns "doc-1"
        every { collectionUseCases.findAll() } returns listOf(col)
        every { librarianUseCases.findByCollection("col-1", DocumentType.SOURCE) } returns listOf(doc)
        every { ontologyUseCases.list() } returns emptyList()

        purgeService.purgeAll()

        verify { librarianUseCases.deleteDocument("doc-1") }
        verify { collectionUseCases.delete("col-1") }
    }

    @Test
    fun `purgeAll with empty collections returns zero counts`() {
        every { collectionUseCases.findAll() } returns emptyList()
        every { ontologyUseCases.list() } returns emptyList()

        val result = purgeService.purgeAll()

        assertEquals(0, result.collectionsDeleted)
        assertEquals(0, result.documentsDeleted)
    }

    @Test
    fun `purgeAll deletes all ontologies`() {
        every { collectionUseCases.findAll() } returns emptyList()
        every { ontologyUseCases.list() } returns listOf("ontology-a", "ontology-b")

        purgeService.purgeAll()

        verify { ontologyUseCases.delete("ontology-a") }
        verify { ontologyUseCases.delete("ontology-b") }
    }
}
```

- [ ] **Step 2: Write `CollectionLifecycleManagerTest`**

Verifies that `CollectionLifecycleManager.purge(collectionId)` calls `LibrarianUseCases` (the Task 2 fix).

```kotlin
// src/test/kotlin/com/agentwork/graphmesh/collection/CollectionLifecycleManagerTest.kt
package com.agentwork.graphmesh.collection

import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.librarian.application.port.`in`.LibrarianUseCases
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.blob.BlobStore
import com.agentwork.graphmesh.storage.vector.VectorStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class CollectionLifecycleManagerTest {

    private val librarianUseCases = mockk<LibrarianUseCases>(relaxed = true)
    private val quadStore = mockk<QuadStore>(relaxed = true)
    private val vectorStore = mockk<VectorStore>(relaxed = true)
    private val blobStore = mockk<BlobStore>(relaxed = true)

    // Adjust constructor parameters to match CollectionLifecycleManager after Task 2 fix
    private val manager = CollectionLifecycleManager(
        librarianUseCases = librarianUseCases,
        quadStore = quadStore,
        vectorStore = vectorStore,
        blobStore = blobStore,
    )

    @Test
    fun `purge deletes all source documents via LibrarianUseCases`() {
        val doc = mockk<Document>()
        every { doc.id } returns "doc-1"
        every { librarianUseCases.findByCollection("col-1", DocumentType.SOURCE) } returns listOf(doc)

        manager.purge("col-1")

        verify { librarianUseCases.deleteDocument("doc-1") }
    }

    @Test
    fun `purge with no documents succeeds`() {
        every { librarianUseCases.findByCollection("empty", DocumentType.SOURCE) } returns emptyList()

        val result = manager.purge("empty")

        assertTrue(result.failures.isEmpty())
    }
}
```

Note: check `CollectionLifecycleManager`'s actual constructor parameters — it also injects `QuadStore`, `VectorStore`, `BlobStore` for direct storage cleanup. Adjust the test constructor call to match exactly.

- [ ] **Step 3: Write `RdfImportSchemaRefreshTest`**

Verifies that `RdfImportService.importRdf(...)` calls `SchemaRefreshUseCase.refreshSchema(collectionId)` after import (the Task 3 fix).

```kotlin
// src/test/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportSchemaRefreshTest.kt
package com.agentwork.graphmesh.rdfimport

import com.agentwork.graphmesh.dynamicgraphql.application.port.`in`.SchemaRefreshUseCase
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.vector.VectorStore
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class RdfImportSchemaRefreshTest {

    private val quadStore = mockk<QuadStore>(relaxed = true)
    private val vectorStore = mockk<VectorStore>(relaxed = true)
    private val schemaRefreshUseCase = mockk<SchemaRefreshUseCase>(relaxed = true)

    // Adjust constructor to match RdfImportService after Task 3 fix
    private val service = RdfImportService(
        quadStore = quadStore,
        vectorStore = vectorStore,
        schemaRefreshUseCase = schemaRefreshUseCase,
    )

    @Test
    fun `importRdf triggers schema refresh for the target collection`() {
        val ttlContent = """
            @prefix ex: <http://example.org/> .
            ex:subject ex:predicate ex:object .
        """.trimIndent()

        service.importRdf("col-1", ttlContent, RdfFormat.TURTLE)

        verify { schemaRefreshUseCase.refreshSchema("col-1") }
    }
}
```

Note: `RdfImportService` likely has more constructor dependencies (for embedding generation etc.). Use `relaxed = true` mocks for any you don't need to control in this test. Check the actual constructor parameters after Task 3 and add them.

- [ ] **Step 4: Run all three new tests**

```bash
./gradlew test --tests "com.agentwork.graphmesh.api.PurgeServiceTest" \
               --tests "com.agentwork.graphmesh.collection.CollectionLifecycleManagerTest" \
               --tests "com.agentwork.graphmesh.rdfimport.RdfImportSchemaRefreshTest"
```

Expected: all pass. Fix any constructor mismatch errors by checking the actual service constructors.

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/api/PurgeServiceTest.kt \
        src/test/kotlin/com/agentwork/graphmesh/collection/CollectionLifecycleManagerTest.kt \
        src/test/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportSchemaRefreshTest.kt
git commit -m "test: unit tests for cross-feature boundary fixes in PR 3"
```

---

## Task 8: Audit pass — verify no remaining violations

- [ ] **Step 1: Re-run the violation scan**

```bash
grep -rn "import com.agentwork.graphmesh\." src/main/kotlin/com/agentwork/graphmesh/ --include="*.kt" | python3 -c "
import sys, re
for line in sys.stdin:
    m = re.match(r'(src/main/kotlin/com/agentwork/graphmesh/(\w+)/.*?\.kt):\d+:(import com\.agentwork\.graphmesh\.(\w+)\..*)', line.strip())
    if m:
        src_feature, dep_feature, dep = m.group(2), m.group(4), m.group(3).replace('import com.agentwork.graphmesh.', '')
        skip = {'rdf', 'tenant', 'messaging', 'llm', 'storage', 'common'}
        if src_feature != dep_feature and dep_feature not in skip and src_feature not in skip:
            print(f'{src_feature} -> {dep_feature}: {dep}')
" | sort -u
```

- [ ] **Step 2: Assess remaining entries**

For each entry in the output, classify it:
- **Acceptable:** importing from `<feature>.application.port.in.*` — this IS the correct pattern.
- **Acceptable:** importing shared domain types from `common.domain.*`.
- **Violation:** importing from `<feature>.domain.*`, `<feature>.adapter.*`, or a concrete `*Service` class from another feature.

Fix any remaining violations using the same patterns from Tasks 1–6.

- [ ] **Step 3: Full build and test**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 4: Final commit**

```bash
git add -p
git commit -m "refactor: PR3 cross-feature audit complete"
```
