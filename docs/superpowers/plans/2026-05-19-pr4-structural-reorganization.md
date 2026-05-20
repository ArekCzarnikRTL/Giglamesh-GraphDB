# PR 4 — Structural Reorganization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move all source files into the canonical hexagonal subdirectory layout (`domain/`, `application/`, `adapter/in/`, `adapter/out/`) within each feature package. Move feature-specific controllers from the global `api/` package into their feature's `adapter/in/`. After this PR the flat-package structure is fully replaced by hexagonal layers.

**Architecture:** Pure file moves — no logic changes. Each file gets a new package declaration and all import sites referencing the old package are updated. The `api/` package is emptied of feature controllers; only cross-cutting concerns (`GraphMeshExceptionResolver`, `CorsConfig`, `PurgeController`, `PurgeService`) may remain temporarily if they lack a natural home.

**Tech Stack:** Kotlin, Spring Boot 4.0.5, Spring Modulith 2.0.5, Gradle 9.4.1. Build: `./gradlew build`.

**Prerequisites:** PR 1, PR 2, and PR 3 merged into `feat/layer-to-hexarch-switch`.

---

## Classification Rules (quick reference)

| Class type | Destination |
|---|---|
| Entity, value object, exception, domain event | `<feature>/domain/` |
| Use case interface (`*UseCases`, `*UseCase`) | `<feature>/application/port/in/` (already there from PR 1) |
| Output port interface (`*Store`, `*EventPort`, `*Port`) | `<feature>/application/port/out/` (already there from PR 2) |
| `*Service` implementing use case interfaces | `<feature>/application/` |
| Controller, Kafka consumer, MCP tools, CLI command | `<feature>/adapter/in/` |
| Cassandra/Kafka/S3 adapter, schema initializer, `@Configuration` | `<feature>/adapter/out/` |
| Schema initializer, event producer class | `<feature>/adapter/out/` |
| `@EventListener` class (calls use case inward) | `<feature>/adapter/in/` |

For ambiguous classes: **does it just drive infrastructure, or does it make decisions?** Infrastructure-only → `adapter/out/`. Decision-making → `application/`.

---

## Global `api/` controllers to move

Each controller moves from `api/` into its feature's `adapter/in/`:

| Controller | Move to |
|---|---|
| `api/CollectionController.kt` | `collection/adapter/in/CollectionController.kt` |
| `api/CollectionDataController.kt` | `collection/adapter/in/CollectionDataController.kt` |
| `api/DocumentController.kt` | `librarian/adapter/in/DocumentController.kt` |
| `api/DocumentHierarchyController.kt` | `librarian/adapter/in/DocumentHierarchyController.kt` |
| `api/ConfigGraphQlController.kt` | `config/adapter/in/ConfigGraphQlController.kt` |
| `api/AgentController.kt` | `agent/adapter/in/AgentController.kt` |
| `api/StreamingController.kt` | `agent/adapter/in/StreamingController.kt` |
| `api/ContextCoreController.kt` | `contextcore/adapter/in/ContextCoreController.kt` |
| `api/DocumentRagController.kt` | `query/adapter/in/DocumentRagController.kt` |
| `api/GraphRagController.kt` | `query/adapter/in/GraphRagController.kt` |
| `api/NlpQueryController.kt` | `query/adapter/in/NlpQueryController.kt` |
| `api/SearchController.kt` | `query/adapter/in/SearchController.kt` |
| `api/GraphController.kt` | `storage/adapter/in/GraphController.kt` |
| `api/ExplainabilityController.kt` | `provenance/adapter/in/ExplainabilityController.kt` |
| `api/PurgeController.kt` | `api/` (stays — `PurgeService` is still in `api/`; move together with PurgeService in a later cleanup, or move to a new `purge/` feature) |
| `api/mcp/GraphMeshMcpTools.kt` | `agent/adapter/in/GraphMeshMcpTools.kt` (or `api/mcp/` stays if it aggregates multiple features) |

Moving a controller changes:
1. The file's `package` declaration
2. All `import` statements referencing types from the old `api/` package (e.g., `api.InputTypes`)
3. Any other file that imports that controller class by name (rare — controllers are not typically imported)

---

## Task 1: `collection` — full hexagonal layout

**Source files and their destinations:**

| Current file | Destination | Layer |
|---|---|---|
| `collection/Collection.kt` | `collection/domain/Collection.kt` | domain |
| `collection/CollectionNotFoundException.kt` | `collection/domain/CollectionNotFoundException.kt` | domain |
| `collection/CollectionEvent.kt` (if not moved to `common/domain` in PR 3) | `collection/domain/CollectionEvent.kt` | domain |
| `collection/CollectionService.kt` | `collection/application/CollectionService.kt` | application |
| `collection/CollectionOntologyService.kt` | `collection/application/CollectionOntologyService.kt` | application |
| `collection/CollectionLifecycleManager.kt` | `collection/application/CollectionLifecycleManager.kt` | application (makes decisions) |
| `collection/CassandraCollectionStore.kt` | `collection/adapter/out/CassandraCollectionStore.kt` | adapter/out |
| `collection/CollectionSchemaInitializer.kt` | `collection/adapter/out/CollectionSchemaInitializer.kt` | adapter/out (infra only) |
| `collection/CollectionEventProducer.kt` | `collection/adapter/out/CollectionEventProducer.kt` | adapter/out |
| `api/CollectionController.kt` | `collection/adapter/in/CollectionController.kt` | adapter/in |
| `api/CollectionDataController.kt` | `collection/adapter/in/CollectionDataController.kt` | adapter/in |
| Relevant entries from `api/InputTypes.kt` | Stay in `api/InputTypes.kt` for now (global input types are moved in a later iteration) |

**Files:**
- Create all files listed above (copy + new package declaration)
- Delete all old files

- [ ] **Step 1: Move domain files**

For each domain file:
1. Create the new file with updated `package com.agentwork.graphmesh.collection.domain`
2. Delete the old file
3. Update all imports across the project

Run after each move:
```bash
./gradlew build -x test
```

Domain files: `Collection.kt`, `CollectionNotFoundException.kt` (and `CollectionEvent.kt` if still in collection/).

- [ ] **Step 2: Move application service files**

Move `CollectionService.kt`, `CollectionOntologyService.kt`, `CollectionLifecycleManager.kt` to `collection/application/`.
Update package to `com.agentwork.graphmesh.collection.application`.
Find and update all imports:
```bash
grep -rn "graphmesh.collection.CollectionService\|graphmesh.collection.CollectionOntologyService\|graphmesh.collection.CollectionLifecycleManager" \
  src/ --include="*.kt"
```

- [ ] **Step 3: Move adapter/out files**

Move `CassandraCollectionStore.kt`, `CollectionSchemaInitializer.kt`, `CollectionEventProducer.kt` to `collection/adapter/out/`.
Package: `com.agentwork.graphmesh.collection.adapter.out`.

No callers should be importing these directly (they're injected by Spring). Confirm with:
```bash
grep -rn "CassandraCollectionStore\|CollectionSchemaInitializer\|CollectionEventProducer" src/ --include="*.kt" | grep "import "
```

- [ ] **Step 4: Move adapter/in files (controllers from api/)**

Move `api/CollectionController.kt` and `api/CollectionDataController.kt` to `collection/adapter/in/`.
Package: `com.agentwork.graphmesh.collection.adapter.in`.

Update any `import com.agentwork.graphmesh.api.CollectionController` references (rare).

- [ ] **Step 5: Build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL. Fix any "class file for X not found" errors by tracking down missed imports.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/collection/
git commit -m "refactor(collection): apply hexagonal package layout"
```

---

## Task 2: `librarian` — full hexagonal layout

| Current file | Destination | Layer |
|---|---|---|
| `librarian/Document.kt` | `librarian/domain/Document.kt` | domain |
| `librarian/DocumentNotFoundException.kt` | `librarian/domain/DocumentNotFoundException.kt` | domain |
| `librarian/DocumentState.kt` | `librarian/domain/DocumentState.kt` | domain |
| `librarian/DocumentType.kt` | `librarian/domain/DocumentType.kt` | domain |
| `librarian/DocumentFilterCriteria.kt` (if separate) | `librarian/domain/DocumentFilterCriteria.kt` | domain |
| `librarian/DocumentPageResult.kt` (if separate) | `librarian/domain/DocumentPageResult.kt` | domain |
| `librarian/LibrarianService.kt` | `librarian/application/LibrarianService.kt` | application |
| `librarian/CassandraDocumentStore.kt` | `librarian/adapter/out/CassandraDocumentStore.kt` | adapter/out |
| `librarian/DocumentSchemaInitializer.kt` | `librarian/adapter/out/DocumentSchemaInitializer.kt` | adapter/out |
| `api/DocumentController.kt` | `librarian/adapter/in/DocumentController.kt` | adapter/in |
| `api/DocumentHierarchyController.kt` | `librarian/adapter/in/DocumentHierarchyController.kt` | adapter/in |

Note: `DocumentFilterCriteria` and `DocumentPageResult` are currently defined at the top of `LibrarianService.kt`. Extract them to separate files in `librarian/domain/` before moving `LibrarianService.kt`.

- [ ] **Step 1: Extract domain data classes from LibrarianService.kt**

Move `DocumentFilterCriteria` and `DocumentPageResult` (defined inline in `LibrarianService.kt`) to separate files:
- `librarian/DocumentFilterCriteria.kt` (temporary flat location, then move with domain files)
- `librarian/DocumentPageResult.kt`

Remove them from `LibrarianService.kt`, add imports.

- [ ] **Step 2: Move all files using the same pattern as Task 1**

Follow steps 1–6 from Task 1, adapted for `librarian/`.

- [ ] **Step 3: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/librarian/
git commit -m "refactor(librarian): apply hexagonal package layout"
```

---

## Task 3: `ontology` — full hexagonal layout

| Current file | Destination | Layer |
|---|---|---|
| `ontology/Ontology.kt` (domain model) | `ontology/domain/Ontology.kt` | domain |
| `ontology/OntologyInfoPayload.kt` (if DTO) | `ontology/adapter/in/OntologyInfoPayload.kt` | adapter/in |
| `ontology/DefaultOntologyValidator.kt` | `ontology/application/DefaultOntologyValidator.kt` | application (makes decisions) |
| `ontology/OntologyService.kt` | `ontology/application/OntologyService.kt` | application |
| `ontology/OntologyStore.kt` | `ontology/adapter/out/OntologyStore.kt` | adapter/out (drives infra) |
| `ontology/OntologyCache.kt` | `ontology/application/OntologyCache.kt` | application (caching decision logic) |
| `ontology/JenaAdapter.kt` | `ontology/adapter/out/JenaAdapter.kt` | adapter/out (Jena infra) |
| `ontology/OntologyController.kt` | `ontology/adapter/in/OntologyController.kt` | adapter/in |

Note: `OntologyInfoPayload` — check if it's a REST DTO or a domain type. If it's only used as a GraphQL/REST response shape, it belongs in `adapter/in/`. If it's used as a domain read model, it stays in `domain/`.

- [ ] **Step 1: Move all files using the same pattern as Task 1**

- [ ] **Step 2: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/ontology/
git commit -m "refactor(ontology): apply hexagonal package layout"
```

---

## Task 4: `config` — full hexagonal layout

| Current file | Destination | Layer |
|---|---|---|
| `config/ConfigItem.kt` | `config/domain/ConfigItem.kt` | domain |
| `config/ConfigType.kt` | `config/domain/ConfigType.kt` | domain |
| `config/ConfigAction.kt` | `config/domain/ConfigAction.kt` | domain |
| `config/ConfigChangedEvent.kt` | `config/domain/ConfigChangedEvent.kt` | domain |
| `config/ConfigService.kt` | `config/application/ConfigService.kt` | application |
| `config/CassandraConfigStore.kt` | `config/adapter/out/CassandraConfigStore.kt` | adapter/out |
| `config/ConfigSchemaInitializer.kt` | `config/adapter/out/ConfigSchemaInitializer.kt` | adapter/out |
| `config/ConfigChangeProducer.kt` | `config/adapter/out/ConfigChangeProducer.kt` | adapter/out |
| `config/ConfigChangeConsumer.kt` | `config/adapter/in/ConfigChangeConsumer.kt` | adapter/in (`@KafkaListener`) |
| `api/ConfigGraphQlController.kt` | `config/adapter/in/ConfigGraphQlController.kt` | adapter/in |

- [ ] **Step 1: Move all files**

- [ ] **Step 2: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/config/
git commit -m "refactor(config): apply hexagonal package layout"
```

---

## Task 5: `agent` — full hexagonal layout

| Current file | Destination | Layer |
|---|---|---|
| `agent/AgentQueryConfig.kt`, `AgentQueryResult.kt`, `StreamToken.kt`, `ToolGroup.kt`, `ToolInfo.kt` | `agent/domain/` | domain |
| `agent/AgentService.kt` | `agent/application/AgentService.kt` | application |
| `agent/StreamingAgentServiceImpl.kt` | `agent/application/StreamingAgentServiceImpl.kt` | application |
| `agent/ToolGroupRegistry.kt` | `agent/application/ToolGroupRegistry.kt` | application (registry = decision logic) |
| `agent/AgentQueryTools.kt` | `agent/adapter/out/AgentQueryTools.kt` | adapter/out (tool wiring) |
| `agent/StreamingAgentService.kt` (interface) | `agent/application/port/in/StreamingAgentService.kt` | already a port — moves here |
| `api/AgentController.kt` | `agent/adapter/in/AgentController.kt` | adapter/in |
| `api/StreamingController.kt` | `agent/adapter/in/StreamingController.kt` | adapter/in |
| `api/mcp/GraphMeshMcpTools.kt` | `agent/adapter/in/GraphMeshMcpTools.kt` | adapter/in |

Note: `StreamingAgentService` is already an interface that should live in `application/port/in/`. Move it there.

- [ ] **Step 1: Move all files**

- [ ] **Step 2: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/agent/
git commit -m "refactor(agent): apply hexagonal package layout"
```

---

## Task 6: `query` — full hexagonal layout

The `query` feature has sub-packages (`docrag/`, `graphrag/`, `nlp/`). Reorganize each sub-package individually.

| Current file | Destination | Layer |
|---|---|---|
| `query/docrag/DocumentRagQuery.kt`, `DocumentRagResult.kt` | `query/docrag/domain/` | domain |
| `query/docrag/DocumentRagService.kt` | `query/docrag/application/DocumentRagService.kt` | application |
| `query/graphrag/GraphRagQuery.kt`, `GraphRagResult.kt` | `query/graphrag/domain/` | domain |
| `query/graphrag/GraphRagService.kt` | `query/graphrag/application/GraphRagService.kt` | application |
| `query/nlp/NlpQuery.kt`, `NlpQueryResult.kt`, `QueryIntent.kt` | `query/nlp/domain/` | domain |
| `query/nlp/NlpQueryService.kt` | `query/nlp/application/NlpQueryService.kt` | application |
| `query/CachedEmbeddingService.kt` | `query/application/CachedEmbeddingService.kt` | application |
| `query/CollectionContentTypeService.kt` | `query/application/CollectionContentTypeService.kt` | application |
| `api/DocumentRagController.kt` | `query/adapter/in/DocumentRagController.kt` | adapter/in |
| `api/GraphRagController.kt` | `query/adapter/in/GraphRagController.kt` | adapter/in |
| `api/NlpQueryController.kt` | `query/adapter/in/NlpQueryController.kt` | adapter/in |
| `api/SearchController.kt` | `query/adapter/in/SearchController.kt` | adapter/in |

- [ ] **Step 1: Move all files, sub-package by sub-package**

Start with `docrag/`, then `graphrag/`, then `nlp/`, then the root `query/` files.

- [ ] **Step 2: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/query/
git commit -m "refactor(query): apply hexagonal package layout"
```

---

## Task 7: `extraction` — full hexagonal layout

The `extraction` feature is the largest, with many sub-packages. Move by sub-package.

General pattern per sub-package:
- `*Consumer.kt` → `extraction/<sub>/adapter/in/` (`@KafkaListener` = driving adapter)
- `*Producer.kt` → `extraction/<sub>/adapter/out/`
- `*Service.kt` → `extraction/<sub>/application/`
- Domain models → `extraction/<sub>/domain/`

Key sub-packages: `agent/`, `chunker/`, `decoder/`, `definition/`, `embedding/`, `ontology/`, `relationship/`, `structured/`, `topic/`.

- [ ] **Step 1: For each sub-package, apply the pattern**

For `extraction/chunker/`:
- `ChunkerConsumer.kt` → `extraction/chunker/adapter/in/ChunkerConsumer.kt`
- `ChunkCreatedProducer.kt` → `extraction/chunker/adapter/out/ChunkCreatedProducer.kt`
- `ChunkerService.kt` → `extraction/chunker/application/ChunkerService.kt`

Repeat for each sub-package.

- [ ] **Step 2: Build after each sub-package**

```bash
./gradlew build -x test
```

- [ ] **Step 3: Commit after each sub-package**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/chunker/
git commit -m "refactor(extraction/chunker): apply hexagonal package layout"
# repeat for each sub-package
```

---

## Task 8: `provenance`, `skos`, `rdfimport`, `structured`, `contextcore` — full hexagonal layout

Apply the same pattern to the remaining features. Each is smaller; they can each be a single task.

**`provenance`:**
- `ProvenanceService.kt` → `provenance/application/ProvenanceService.kt`
- `SubgraphProvenance.kt` → `provenance/domain/SubgraphProvenance.kt`
- `ProvenanceNamespaces.kt` → `provenance/domain/ProvenanceNamespaces.kt`
- `provenance/query/ExplainabilityRecorder.kt` → `provenance/application/ExplainabilityRecorder.kt`
- `provenance/query/ExplanationChainLoader.kt` → `provenance/application/ExplanationChainLoader.kt`
- `provenance/query/ExplainabilityModels.kt` → `provenance/domain/ExplainabilityModels.kt`
- `api/ExplainabilityController.kt` → `provenance/adapter/in/ExplainabilityController.kt`

**`skos`:**
- `SkosService.kt` → `skos/application/SkosService.kt`
- Domain types (`SkosConcept.kt`, `SkosConceptScheme.kt`) → `skos/domain/` (if not moved to `common/domain/` in PR 3)
- `SkosController.kt` → `skos/adapter/in/SkosController.kt`

**`rdfimport`:**
- `RdfImportService.kt` → `rdfimport/application/RdfImportService.kt`
- `RdfImportController.kt` → `rdfimport/adapter/in/RdfImportController.kt`
- `RdfFormat.kt` (if it exists) → `rdfimport/domain/RdfFormat.kt`

**`structured`:**
- `StructuredDataService.kt` → `structured/application/StructuredDataService.kt`
- `Models.kt` (domain models) → `structured/domain/Models.kt`
- `SchemaStore.kt` → `structured/application/port/out/SchemaStore.kt` (output port)
- `CassandraRowStore.kt` → `structured/adapter/out/CassandraRowStore.kt`
- `CassandraRowSchemaInitializer.kt` → `structured/adapter/out/CassandraRowSchemaInitializer.kt`

**`contextcore`:**
- `ContextCoreService.kt` → `contextcore/application/ContextCoreService.kt`
- `CoreManifest.kt`, `BuildRequest.kt`, etc. → `contextcore/domain/`
- `ContextCoreRegistry.kt` → `contextcore/application/ContextCoreRegistry.kt`
- `api/ContextCoreController.kt` → `contextcore/adapter/in/ContextCoreController.kt`

- [ ] **Step 1: Move each feature in sequence**

One commit per feature:
```bash
git commit -m "refactor(provenance): apply hexagonal package layout"
git commit -m "refactor(skos): apply hexagonal package layout"
git commit -m "refactor(rdfimport): apply hexagonal package layout"
git commit -m "refactor(structured): apply hexagonal package layout"
git commit -m "refactor(contextcore): apply hexagonal package layout"
```

Build after each before committing.

---

## Task 9: Clean up `api/` — what remains

After all controllers are moved to their feature's `adapter/in/`:

- `api/PurgeController.kt` + `api/PurgeService.kt` — consider creating a `purge/` feature package containing `PurgeController` (adapter/in) and `PurgeService` (application). Or leave in `api/` as a transitional package — document the decision.
- `api/GraphMeshExceptionResolver.kt` — stays in `api/` (cross-cutting concern, not feature-specific)
- `api/CorsConfig.kt` — stays in `api/` or moves to `config/adapter/out/`
- `api/InputTypes.kt` — split up: each input type moves to its feature's `adapter/in/` (e.g., `CreateCollectionInput` → `collection/adapter/in/`)

- [ ] **Step 1: Check what remains in api/ after controller moves**

```bash
ls src/main/kotlin/com/agentwork/graphmesh/api/
```

- [ ] **Step 2: Move InputTypes.kt entries to feature adapter/in/**

For each input type in `api/InputTypes.kt`, move its definition to the relevant feature's `adapter/in/` package. If multiple features share a single file, split the file.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/api/
git commit -m "refactor(api): clean up after controller migration"
```

---

## Test strategy for this PR

After all file moves, package paths in test imports must be updated. Additionally, PR 4 is the right place to introduce **integration tests for the adapter/out classes** — they now have stable canonical locations.

Integration test convention (from `tests.md`):
- Requires running `docker-compose up` (Cassandra, Qdrant, MinIO, Kafka).
- Named `*IntegrationTest.kt`.
- File comment at the top documents which services are needed.

Unit test updates:
- Every `*UseCasesTest` from PR 1 that mocks an output port interface must update imports to the new `application/port/out/` path (same as PR 2 migration).
- The service under test also moves to `application/` — update the import in the test.

---

## Task 9: Add integration tests for `adapter/out` classes

Write one integration test per store adapter. Each test exercises the real Cassandra/Qdrant/S3 adapter through the output port interface — this proves the adapter correctly implements the port contract against real infrastructure.

**Files to create (write after their feature's structural move in Tasks 1–8):**
- `src/test/kotlin/com/agentwork/graphmesh/collection/adapter/out/CassandraCollectionStoreIntegrationTest.kt`
- `src/test/kotlin/com/agentwork/graphmesh/librarian/adapter/out/CassandraDocumentStoreIntegrationTest.kt`
- `src/test/kotlin/com/agentwork/graphmesh/config/adapter/out/CassandraConfigStoreIntegrationTest.kt`

- [ ] **Step 1: Write `CassandraCollectionStoreIntegrationTest`**

Create this after Task 1 (collection structural move).

```kotlin
// src/test/kotlin/com/agentwork/graphmesh/collection/adapter/out/CassandraCollectionStoreIntegrationTest.kt
package com.agentwork.graphmesh.collection.adapter.out

// Requires: Cassandra (docker-compose up cassandra)

import com.agentwork.graphmesh.collection.application.port.out.CollectionStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
class CassandraCollectionStoreIntegrationTest {

    @Autowired
    private lateinit var store: CollectionStore

    @Test
    fun `save and findById round-trips a collection`() {
        val id = "inttest-col-${System.currentTimeMillis()}"
        val col = com.agentwork.graphmesh.collection.domain.Collection(
            id = id, name = "integration-test-collection"
        )

        store.save(col)
        val found = store.findById(id)

        assertNotNull(found)
        assertEquals(id, found.id)
        assertEquals("integration-test-collection", found.name)

        // cleanup
        store.delete(id)
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(store.findById("definitely-does-not-exist-${System.currentTimeMillis()}"))
    }

    @Test
    fun `findByName returns null when no collection has that name`() {
        assertNull(store.findByName("no-such-name-${System.currentTimeMillis()}"))
    }

    @Test
    fun `exists returns false for unknown id`() {
        assertTrue(!store.exists("nonexistent-${System.currentTimeMillis()}"))
    }
}
```

Note: adjust the `Collection` import path to its new `domain/` location after Task 1.

- [ ] **Step 2: Write `CassandraDocumentStoreIntegrationTest`**

Create this after Task 2 (librarian structural move). Pattern is identical — inject `DocumentStore` (the output port interface), save a `Document`, assert round-trip, clean up.

```kotlin
// src/test/kotlin/com/agentwork/graphmesh/librarian/adapter/out/CassandraDocumentStoreIntegrationTest.kt
package com.agentwork.graphmesh.librarian.adapter.out

// Requires: Cassandra (docker-compose up cassandra)

import com.agentwork.graphmesh.librarian.application.port.out.DocumentStore
import com.agentwork.graphmesh.librarian.domain.Document
import com.agentwork.graphmesh.librarian.domain.DocumentState
import com.agentwork.graphmesh.librarian.domain.DocumentType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
class CassandraDocumentStoreIntegrationTest {

    @Autowired
    private lateinit var store: DocumentStore

    @Test
    fun `save and findById round-trips a document`() {
        val id = "inttest-doc-${System.currentTimeMillis()}"
        val doc = Document(
            id = id,
            collectionId = "col-inttest",
            title = "integration test doc",
            mimeType = "text/plain",
            contentUri = "doc/$id",
            state = DocumentState.UPLOADED,
            type = DocumentType.SOURCE,
        )

        store.save(doc)
        val found = store.findById(id)

        assertNotNull(found)
        assertEquals(id, found.id)
        assertEquals(DocumentState.UPLOADED, found.state)

        // cleanup
        store.delete(id)
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(store.findById("no-such-doc-${System.currentTimeMillis()}"))
    }

    @Test
    fun `updateState persists new state`() {
        val id = "inttest-state-${System.currentTimeMillis()}"
        val doc = Document(
            id = id, collectionId = "col-inttest", title = "state test",
            mimeType = "text/plain", contentUri = "doc/$id",
            state = DocumentState.UPLOADED, type = DocumentType.SOURCE,
        )
        store.save(doc)

        store.updateState(id, DocumentState.PROCESSING)
        val found = store.findById(id)

        assertEquals(DocumentState.PROCESSING, found?.state)

        // cleanup
        store.delete(id)
    }
}
```

- [ ] **Step 3: Run integration tests (requires docker-compose up)**

```bash
docker-compose up -d cassandra qdrant minio kafka
./gradlew test --tests "com.agentwork.graphmesh.collection.adapter.out.CassandraCollectionStoreIntegrationTest" \
               --tests "com.agentwork.graphmesh.librarian.adapter.out.CassandraDocumentStoreIntegrationTest"
```

Expected: all integration tests pass against real Cassandra.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/collection/adapter/out/ \
        src/test/kotlin/com/agentwork/graphmesh/librarian/adapter/out/
git commit -m "test: add adapter/out integration tests for collection and librarian stores"
```

---

## Task 10: Update test imports after file moves

Every `*UseCasesTest` from PR 1 mocks output port interfaces and constructs the service under test. After PR 4 moves those classes to new packages, all test imports must be updated.

- [ ] **Step 1: Find all broken test imports**

```bash
./gradlew build 2>&1 | grep "error:" | grep "src/test"
```

This will list every unresolved import in test files.

- [ ] **Step 2: Update each broken import to the new package path**

Pattern: if `CollectionService` moved to `collection/application/`, tests that import it change from:
```kotlin
import com.agentwork.graphmesh.collection.CollectionService
```
to:
```kotlin
import com.agentwork.graphmesh.collection.application.CollectionService
```

Similarly for domain types that moved to `domain/`:
```kotlin
// before
import com.agentwork.graphmesh.collection.Collection
// after
import com.agentwork.graphmesh.collection.domain.Collection
```

Run `./gradlew build -x test` after each batch of import fixes to check progress.

- [ ] **Step 3: Run full test suite**

```bash
./gradlew test
```

Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/
git commit -m "test: update test imports after PR 4 structural reorganization"
```

---

## Task 12: Full build, test, and final verification

- [ ] **Step 1: Full build with tests**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 2: Verify directory structure matches spec**

```bash
find src/main/kotlin/com/agentwork/graphmesh -type d | sort
```

Each feature package should show subdirectories: `domain/`, `application/`, `application/port/in/`, `application/port/out/`, `adapter/in/`, `adapter/out/`.

- [ ] **Step 3: Verify no flat-package source files remain**

```bash
find src/main/kotlin/com/agentwork/graphmesh -maxdepth 2 -name "*.kt" | \
  grep -v "/common/\|/api/\|/llm/\|/messaging/\|/tenant/\|/rdf/\|/storage/\|/pipeline/"
```

Expected: no output. All feature source files should now be in subdirectories.

- [ ] **Step 4: Verify no flat-package test files remain**

```bash
find src/test/kotlin/com/agentwork/graphmesh -maxdepth 2 -name "*.kt" | \
  grep -v "/common/\|/storage/\|/query/"
```

Review any remaining flat-location test files and move them to match their subject class's new location.

- [ ] **Step 5: Final commit**

```bash
git commit -m "refactor: PR4 structural reorganization complete"
```
