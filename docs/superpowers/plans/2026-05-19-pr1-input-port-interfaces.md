# PR 1 — Input Port Interfaces Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce `application/port/in/` interfaces for every feature whose service is called cross-feature or from `api/`, make each `*Service` implement the interface, and update all callers to inject the interface type instead of the concrete class.

**Architecture:** Each interface file lives at `src/main/kotlin/com/agentwork/graphmesh/<feature>/application/port/in/<Name>.kt` (package `com.agentwork.graphmesh.<feature>.application.port.in`). No files are moved — the existing `*Service` classes stay in their current locations and gain an `implements` clause. Callers change only the injected type and import.

**Tech Stack:** Kotlin, Spring Boot 4.0.5, Spring Modulith 2.0.5, Gradle 9.4.1. Build: `./gradlew build`. Tests: `./gradlew test`.

---

## File Map

**New files (interfaces):**

`collection/`:
- `Create: src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/CollectionUseCases.kt` — reads only
- `Create: src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/CreateCollectionUseCase.kt`
- `Create: src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/UpdateCollectionUseCase.kt`
- `Create: src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/DeleteCollectionUseCase.kt`
- `Create: src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/CollectionOntologyUseCases.kt`

`librarian/`:
- `Create: src/main/kotlin/com/agentwork/graphmesh/librarian/application/port/in/LibrarianUseCases.kt` — reads + updateState
- `Create: src/main/kotlin/com/agentwork/graphmesh/librarian/application/port/in/UploadDocumentUseCase.kt`
- `Create: src/main/kotlin/com/agentwork/graphmesh/librarian/application/port/in/CreateChildDocumentUseCase.kt`
- `Create: src/main/kotlin/com/agentwork/graphmesh/librarian/application/port/in/DeleteDocumentUseCase.kt`

`ontology/`:
- `Create: src/main/kotlin/com/agentwork/graphmesh/ontology/application/port/in/OntologyUseCases.kt` — reads + delete + validate + exports
- `Create: src/main/kotlin/com/agentwork/graphmesh/ontology/application/port/in/SaveOntologyUseCase.kt`
- `Create: src/main/kotlin/com/agentwork/graphmesh/ontology/application/port/in/ImportTurtleOntologyUseCase.kt`
- `Create: src/main/kotlin/com/agentwork/graphmesh/ontology/application/port/in/ImportRdfXmlOntologyUseCase.kt`

`config/`:
- `Create: src/main/kotlin/com/agentwork/graphmesh/config/application/port/in/ConfigUseCases.kt` — reads + delete + history
- `Create: src/main/kotlin/com/agentwork/graphmesh/config/application/port/in/SaveConfigUseCase.kt`

`contextcore/`:
- `Create: src/main/kotlin/com/agentwork/graphmesh/contextcore/application/port/in/ContextCoreUseCases.kt` — reads + delete + tag
- `Create: src/main/kotlin/com/agentwork/graphmesh/contextcore/application/port/in/BuildContextCoreUseCase.kt`
- `Create: src/main/kotlin/com/agentwork/graphmesh/contextcore/application/port/in/ImportContextCoreUseCase.kt`

Others (grouped-only):
- `Create: src/main/kotlin/com/agentwork/graphmesh/agent/application/port/in/AgentUseCases.kt`
- `Create: src/main/kotlin/com/agentwork/graphmesh/query/docrag/application/port/in/DocumentRagUseCase.kt`
- `Create: src/main/kotlin/com/agentwork/graphmesh/query/graphrag/application/port/in/GraphRagUseCase.kt`
- `Create: src/main/kotlin/com/agentwork/graphmesh/query/nlp/application/port/in/NlpQueryUseCase.kt`
- `Create: src/main/kotlin/com/agentwork/graphmesh/skos/application/port/in/SkosUseCases.kt`
- `Create: src/main/kotlin/com/agentwork/graphmesh/structured/application/port/in/StructuredDataUseCases.kt`
- `Create: src/main/kotlin/com/agentwork/graphmesh/provenance/application/port/in/ProvenanceUseCases.kt`
- `Create: src/main/kotlin/com/agentwork/graphmesh/storage/application/port/in/OrphanSweepUseCase.kt`

**Modified files (implement interface):**
- `Modify: src/main/kotlin/com/agentwork/graphmesh/collection/CollectionService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/collection/CollectionOntologyService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/librarian/LibrarianService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/ontology/OntologyService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/config/ConfigService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/agent/AgentService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/contextcore/ContextCoreService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/skos/SkosService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/structured/StructuredDataService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/provenance/ProvenanceService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/storage/OrphanSweepService.kt`

**Modified files (update callers — inject interface, not concrete class):**
- `Modify: src/main/kotlin/com/agentwork/graphmesh/api/CollectionController.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/api/CollectionDataController.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/api/DocumentController.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/api/DocumentHierarchyController.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/api/SearchController.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/api/ConfigGraphQlController.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/api/AgentController.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/api/StreamingController.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/api/ContextCoreController.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/api/DocumentRagController.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/api/GraphRagController.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/api/NlpQueryController.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/api/PurgeService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/api/mcp/GraphMeshMcpTools.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlController.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlSchemaBuilder.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/contextcore/ContextCoreService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/librarian/LibrarianService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/agent/AgentService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/agent/AgentQueryTools.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/agent/StreamingAgentServiceImpl.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/extraction/agent/AgentExtractorService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/extraction/agent/ExtractionTools.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/extraction/chunker/ChunkerService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/PdfDecoderService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/TextDecoderService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/extraction/embedding/EmbeddingConsumer.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/extraction/embedding/EmbeddingService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/extraction/ontology/OntologyGuidedExtractorService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/extraction/relationship/RelationshipExtractorService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicOntologyMatcher.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/query/CollectionContentTypeService.kt`
- `Modify: src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt`

---

## Task 1: `collection` — CollectionUseCases + mutation use cases

`CollectionService` implements four interfaces: reads are grouped; each mutation (create/update/delete) gets its own interface because all three orchestrate event publishing (Spring + Kafka) and delete additionally cascades through `CollectionLifecycleManager`.

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/CollectionUseCases.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/CreateCollectionUseCase.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/UpdateCollectionUseCase.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/DeleteCollectionUseCase.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/collection/CollectionService.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/api/CollectionController.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/api/PurgeService.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/librarian/LibrarianService.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/extraction/ontology/OntologyGuidedExtractorService.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlController.kt`

- [ ] **Step 1: Create the four interface files**

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/CollectionUseCases.kt
package com.agentwork.graphmesh.collection.application.port.`in`

import com.agentwork.graphmesh.collection.Collection

interface CollectionUseCases {
    fun findById(id: String): Collection?
    fun findByName(name: String): Collection?
    fun findAll(tags: Set<String> = emptySet()): List<Collection>
    fun requireExists(id: String)
}
```

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/CreateCollectionUseCase.kt
package com.agentwork.graphmesh.collection.application.port.`in`

import com.agentwork.graphmesh.collection.Collection

interface CreateCollectionUseCase {
    fun createCollection(
        name: String,
        description: String = "",
        tags: Set<String> = emptySet(),
        metadata: Map<String, String> = emptyMap()
    ): Collection
}
```

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/UpdateCollectionUseCase.kt
package com.agentwork.graphmesh.collection.application.port.`in`

import com.agentwork.graphmesh.collection.Collection

interface UpdateCollectionUseCase {
    fun updateCollection(
        id: String,
        name: String? = null,
        description: String? = null,
        tags: Set<String>? = null,
        metadata: Map<String, String>? = null
    ): Collection
}
```

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/DeleteCollectionUseCase.kt
package com.agentwork.graphmesh.collection.application.port.`in`

interface DeleteCollectionUseCase {
    fun deleteCollection(id: String)
}
```

- [ ] **Step 2: Make CollectionService implement all four interfaces**

In `CollectionService.kt`:
- Add imports for all four interfaces.
- Change declaration: `class CollectionService(...) : CollectionUseCases, CreateCollectionUseCase, UpdateCollectionUseCase, DeleteCollectionUseCase {`
- Rename `fun create(...)` → `override fun createCollection(...)`, add `override` to reads.
- Rename `fun update(...)` → `override fun updateCollection(...)`.
- Rename `fun delete(id: String)` → `override fun deleteCollection(id: String)`.

All existing callers of `CollectionService.create(...)`, `.update(...)`, `.delete(...)` will now need to be updated in Step 3 since the method names changed.

- [ ] **Step 3: Update callers — inject the correct interface per usage**

Each caller only needs the interface(s) for the methods it actually calls. Check what each caller uses:

**`api/CollectionController.kt`** — likely calls `create`, `update`, `delete`, `findById`, `findAll`. Inject multiple interfaces:
```kotlin
class CollectionController(
    private val collectionUseCases: CollectionUseCases,
    private val createCollectionUseCase: CreateCollectionUseCase,
    private val updateCollectionUseCase: UpdateCollectionUseCase,
    private val deleteCollectionUseCase: DeleteCollectionUseCase,
)
```
Update call sites: `collectionService.create(...)` → `createCollectionUseCase.createCollection(...)`, etc.

**`api/PurgeService.kt`** — calls `findAll()` and `delete(id)`. Inject:
```kotlin
private val collectionUseCases: CollectionUseCases,
private val deleteCollectionUseCase: DeleteCollectionUseCase,
```
Update: `collectionService.findAll()` → `collectionUseCases.findAll()`, `collectionService.delete(id)` → `deleteCollectionUseCase.deleteCollection(id)`.

**`librarian/LibrarianService.kt`** — calls `requireExists(collectionId)`. Inject `CollectionUseCases` only.

**`extraction/ontology/OntologyGuidedExtractorService.kt`** — check what it calls with `grep -n "collectionService\." src/main/kotlin/com/agentwork/graphmesh/extraction/ontology/OntologyGuidedExtractorService.kt`. Inject the matching interface(s).

**`dynamicgraphql/DynamicGraphQlController.kt`** — check with the same grep. Inject what it uses.

- [ ] **Step 4: Build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL. If you see "unresolved reference: create" it means a caller still uses the old method name — rename the call site to `createCollection`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/collection/
git add src/main/kotlin/com/agentwork/graphmesh/api/CollectionController.kt \
        src/main/kotlin/com/agentwork/graphmesh/api/PurgeService.kt \
        src/main/kotlin/com/agentwork/graphmesh/librarian/LibrarianService.kt \
        src/main/kotlin/com/agentwork/graphmesh/extraction/ontology/OntologyGuidedExtractorService.kt \
        src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlController.kt
git commit -m "refactor(collection): split CollectionUseCases into reads + mutation use cases"
```

---

## Task 2: `collection` — CollectionOntologyUseCases input port

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/CollectionOntologyUseCases.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/collection/CollectionOntologyService.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/api/CollectionDataController.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlSchemaBuilder.kt`

- [ ] **Step 1: Create the interface**

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/CollectionOntologyUseCases.kt
package com.agentwork.graphmesh.collection.application.port.`in`

import com.agentwork.graphmesh.collection.CollectionOntologyRecord

interface CollectionOntologyUseCases {
    fun assign(collectionId: String, ontologyKey: String, role: String, assignedBy: String): CollectionOntologyRecord
    fun unassign(collectionId: String, ontologyKey: String)
    fun listForCollection(collectionId: String): List<CollectionOntologyRecord>
}
```

- [ ] **Step 2: Make CollectionOntologyService implement the interface**

In `CollectionOntologyService.kt`:
- Add import: `import com.agentwork.graphmesh.collection.application.port.`in`.CollectionOntologyUseCases`
- Change class declaration to: `class CollectionOntologyService(...) : CollectionOntologyUseCases {`
- Add `override` to: `fun assign(...)`, `fun unassign(...)`, `fun listForCollection(...)`

- [ ] **Step 3: Update callers**

**`api/CollectionDataController.kt`** — replace import and change `private val collectionOntologyService: CollectionOntologyService` → `private val collectionOntologyService: CollectionOntologyUseCases`

**`dynamicgraphql/DynamicGraphQlSchemaBuilder.kt`** — same: replace import and change `private val collectionOntologyService: CollectionOntologyService` → `private val collectionOntologyService: CollectionOntologyUseCases`

- [ ] **Step 4: Build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/collection/application/port/in/CollectionOntologyUseCases.kt \
        src/main/kotlin/com/agentwork/graphmesh/collection/CollectionOntologyService.kt \
        src/main/kotlin/com/agentwork/graphmesh/api/CollectionDataController.kt \
        src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlSchemaBuilder.kt
git commit -m "refactor(collection): introduce CollectionOntologyUseCases input port"
```

---

## Task 3: `librarian` — LibrarianUseCases + mutation use cases

Reads + `updateState` stay grouped. `uploadDocument`, `createChildDocument`, `deleteDocument` are split: upload publishes a Kafka event and writes to blob store; createChild validates parent + generates URIs; delete is recursive.

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/librarian/application/port/in/LibrarianUseCases.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/librarian/application/port/in/UploadDocumentUseCase.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/librarian/application/port/in/CreateChildDocumentUseCase.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/librarian/application/port/in/DeleteDocumentUseCase.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/librarian/LibrarianService.kt`
- Modify (callers): `api/DocumentController.kt`, `api/DocumentHierarchyController.kt`, `api/SearchController.kt`, `api/mcp/GraphMeshMcpTools.kt`, and all `extraction/` + `query/` files identified in Task 3 analysis

- [ ] **Step 1: Create the four interface files**

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/librarian/application/port/in/LibrarianUseCases.kt
package com.agentwork.graphmesh.librarian.application.port.`in`

import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentFilterCriteria
import com.agentwork.graphmesh.librarian.DocumentPageResult
import com.agentwork.graphmesh.librarian.DocumentState
import com.agentwork.graphmesh.librarian.DocumentType

interface LibrarianUseCases {
    fun getContent(documentId: String): ByteArray
    fun findById(id: String): Document?
    fun findByCollection(collectionId: String, type: DocumentType? = null): List<Document>
    fun findByCollectionPaginated(
        collectionId: String,
        criteria: DocumentFilterCriteria = DocumentFilterCriteria(),
        first: Int = 20,
        after: String? = null
    ): DocumentPageResult
    fun findChunksOf(documentId: String): List<Document>
    fun findChildren(parentId: String): List<Document>
    fun updateState(documentId: String, state: DocumentState)
}
```

Note: verify `findByCollectionPaginated` parameter names against the actual `LibrarianService.kt` signature.

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/librarian/application/port/in/UploadDocumentUseCase.kt
package com.agentwork.graphmesh.librarian.application.port.`in`

import com.agentwork.graphmesh.librarian.Document

interface UploadDocumentUseCase {
    fun uploadDocument(
        collectionId: String,
        title: String,
        mimeType: String,
        content: ByteArray,
        metadata: Map<String, String> = emptyMap()
    ): Document
}
```

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/librarian/application/port/in/CreateChildDocumentUseCase.kt
package com.agentwork.graphmesh.librarian.application.port.`in`

import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentType

interface CreateChildDocumentUseCase {
    fun createChildDocument(
        parentId: String,
        type: DocumentType,
        title: String,
        content: ByteArray,
        mimeType: String = "text/plain"
    ): Document
}
```

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/librarian/application/port/in/DeleteDocumentUseCase.kt
package com.agentwork.graphmesh.librarian.application.port.`in`

interface DeleteDocumentUseCase {
    fun deleteDocument(documentId: String)
}
```

- [ ] **Step 2: Make LibrarianService implement all four interfaces**

In `LibrarianService.kt`:
- Add imports for all four interfaces.
- Change: `class LibrarianService(...) : LibrarianUseCases, UploadDocumentUseCase, CreateChildDocumentUseCase, DeleteDocumentUseCase {`
- Add `override` to every method appearing in the interfaces. Method names are unchanged (they already match the interface method names).

- [ ] **Step 3: Update all callers — inject the correct interface(s) per usage**

Run this to find all injection sites:
```bash
grep -rn "private val.*: LibrarianService\|private val.*librarianService" \
  src/main/kotlin/com/agentwork/graphmesh/ --include="*.kt" | grep -v "LibrarianService.kt"
```

For each caller, determine which methods it calls (grep for `librarianService\.`) and inject only the interfaces it needs:
- Callers that only do reads/updateState: inject `LibrarianUseCases`
- Callers that upload: inject `UploadDocumentUseCase`
- Callers that delete: inject `DeleteDocumentUseCase`
- Callers that do multiple: inject multiple interfaces

Key callers confirmed:
- `api/DocumentController.kt` — uploads + reads + deletes → inject all four
- `extraction/*/` services — mostly reads + updateState → inject `LibrarianUseCases`
- `extraction/agent/AgentExtractorService.kt` — check what it calls
- `query/docrag/DocumentRagService.kt` — reads only → inject `LibrarianUseCases`
- `api/PurgeService.kt` — calls `findByCollection` + `deleteDocument` → inject `LibrarianUseCases` + `DeleteDocumentUseCase`

- [ ] **Step 4: Build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/librarian/ \
        src/main/kotlin/com/agentwork/graphmesh/api/ \
        src/main/kotlin/com/agentwork/graphmesh/extraction/ \
        src/main/kotlin/com/agentwork/graphmesh/query/
git commit -m "refactor(librarian): split LibrarianUseCases into reads + mutation use cases"
```

---

## Task 4: `ontology` — OntologyUseCases + import/save use cases

Reads, `delete`, `validate`, `exportTurtle`, `exportRdfXml` stay grouped. `save`, `importTurtle`, `importRdfXml` are split — each runs parse → validate → persist.

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/ontology/application/port/in/OntologyUseCases.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/ontology/application/port/in/SaveOntologyUseCase.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/ontology/application/port/in/ImportTurtleOntologyUseCase.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/ontology/application/port/in/ImportRdfXmlOntologyUseCase.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/ontology/OntologyService.kt`
- Modify (callers): `api/PurgeService.kt`, `contextcore/ContextCoreService.kt`, `extraction/ontology/OntologyGuidedExtractorService.kt`, `extraction/topic/TopicOntologyMatcher.kt`, `dynamicgraphql/DynamicGraphQlSchemaBuilder.kt`

- [ ] **Step 1: Create the four interface files**

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/ontology/application/port/in/OntologyUseCases.kt
package com.agentwork.graphmesh.ontology.application.port.`in`

import com.agentwork.graphmesh.ontology.Ontology
import com.agentwork.graphmesh.ontology.ValidationError

interface OntologyUseCases {
    fun get(key: String): Ontology?
    fun list(): List<String>
    fun delete(key: String)
    fun exportTurtle(key: String): String
    fun exportRdfXml(key: String): String
    fun validate(ontology: Ontology): List<ValidationError>
}
```

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/ontology/application/port/in/SaveOntologyUseCase.kt
package com.agentwork.graphmesh.ontology.application.port.`in`

import com.agentwork.graphmesh.ontology.Ontology
import com.agentwork.graphmesh.ontology.ValidationError

interface SaveOntologyUseCase {
    fun saveOntology(key: String, ontology: Ontology): List<ValidationError>
}
```

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/ontology/application/port/in/ImportTurtleOntologyUseCase.kt
package com.agentwork.graphmesh.ontology.application.port.`in`

import com.agentwork.graphmesh.ontology.Ontology
import com.agentwork.graphmesh.ontology.OntologyMetadata

interface ImportTurtleOntologyUseCase {
    fun importTurtle(key: String, content: String, metadata: OntologyMetadata): Ontology
}
```

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/ontology/application/port/in/ImportRdfXmlOntologyUseCase.kt
package com.agentwork.graphmesh.ontology.application.port.`in`

import com.agentwork.graphmesh.ontology.Ontology
import com.agentwork.graphmesh.ontology.OntologyMetadata

interface ImportRdfXmlOntologyUseCase {
    fun importRdfXml(key: String, content: String, metadata: OntologyMetadata): Ontology
}
```

- [ ] **Step 2: Make OntologyService implement all four interfaces**

In `OntologyService.kt`:
- Add imports for all four interfaces.
- Change: `class OntologyService(...) : OntologyUseCases, SaveOntologyUseCase, ImportTurtleOntologyUseCase, ImportRdfXmlOntologyUseCase {`
- Rename: `fun save(...)` → `override fun saveOntology(...)` (method name change — update callers too).
- Add `override` to all other methods listed in the interfaces.

- [ ] **Step 3: Update callers — inject only the interface(s) they use**

Find all injection sites:
```bash
grep -rn "OntologyService\|OntologyCache" src/main/kotlin/com/agentwork/graphmesh/ \
  --include="*.kt" | grep "private val\|import"
```

Key callers and what they use:
- `api/PurgeService.kt` — `ontologyService.list()` + `ontologyService.delete(key)` → inject `OntologyUseCases`
- `contextcore/ContextCoreService.kt` — `ontologyService.importTurtle(...)` → inject `ImportTurtleOntologyUseCase`
- `extraction/ontology/OntologyGuidedExtractorService.kt` — `ontologyService.get(key)` → inject `OntologyUseCases`
- `extraction/topic/TopicOntologyMatcher.kt` — `ontologyService.get(key)` → inject `OntologyUseCases`
- `dynamicgraphql/DynamicGraphQlSchemaBuilder.kt` — currently injects `OntologyCache` and calls `get(key)` → replace with `OntologyUseCases`
- `api/OntologyController.kt` (in ontology/ itself) — calls everything → inject all four interfaces

- [ ] **Step 4: Build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/ontology/ \
        src/main/kotlin/com/agentwork/graphmesh/api/PurgeService.kt \
        src/main/kotlin/com/agentwork/graphmesh/contextcore/ContextCoreService.kt \
        src/main/kotlin/com/agentwork/graphmesh/extraction/ontology/ \
        src/main/kotlin/com/agentwork/graphmesh/extraction/topic/ \
        src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlSchemaBuilder.kt
git commit -m "refactor(ontology): split OntologyUseCases into reads + import/save use cases"
```

---

## Task 5: `config` — ConfigUseCases + SaveConfigUseCase

Reads + `delete` + `history` stay grouped. `save` is split — it publishes a Spring event + Kafka message.

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/config/application/port/in/ConfigUseCases.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/config/application/port/in/SaveConfigUseCase.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/config/ConfigService.kt`
- Modify (callers): all files injecting `ConfigService`

- [ ] **Step 1: Create the two interface files**

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/config/application/port/in/ConfigUseCases.kt
package com.agentwork.graphmesh.config.application.port.`in`

import com.agentwork.graphmesh.config.ConfigItem
import com.agentwork.graphmesh.config.ConfigType

interface ConfigUseCases {
    fun findById(id: String): ConfigItem?
    fun findByType(type: ConfigType): List<ConfigItem>
    fun findByTypeAndKey(type: ConfigType, key: String): ConfigItem?
    fun findAll(type: ConfigType? = null): List<ConfigItem>
    fun history(id: String, limit: Int = 10): List<ConfigItem>
    fun delete(id: String)
}
```

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/config/application/port/in/SaveConfigUseCase.kt
package com.agentwork.graphmesh.config.application.port.`in`

import com.agentwork.graphmesh.config.ConfigItem

interface SaveConfigUseCase {
    fun saveConfig(item: ConfigItem): ConfigItem
}
```

- [ ] **Step 2: Make ConfigService implement both interfaces**

In `ConfigService.kt`:
- Add imports for both interfaces.
- Change: `class ConfigService(...) : ConfigUseCases, SaveConfigUseCase {`
- Rename `fun save(item: ConfigItem): ConfigItem` → `override fun saveConfig(item: ConfigItem): ConfigItem`.
- Add `override` to all other methods.

- [ ] **Step 3: Update callers**

```bash
grep -rn "ConfigService" src/main/kotlin/com/agentwork/graphmesh/ --include="*.kt" | grep "private val\|import"
```

For each caller, inject `ConfigUseCases` for reads and/or `SaveConfigUseCase` for saves. Update call sites: `configService.save(...)` → `saveConfigUseCase.saveConfig(...)`.

- [ ] **Step 4: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/config/
git commit -m "refactor(config): split ConfigUseCases into reads + SaveConfigUseCase"
```

---

## Task 6: `agent` — AgentUseCases input port

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/agent/application/port/in/AgentUseCases.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/agent/AgentService.kt`
- Modify (callers): `api/AgentController.kt`, `api/mcp/GraphMeshMcpTools.kt`

Note: `StreamingAgentService` is already an interface — no changes needed for it.

- [ ] **Step 1: Create the interface**

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/agent/application/port/in/AgentUseCases.kt
package com.agentwork.graphmesh.agent.application.port.`in`

import com.agentwork.graphmesh.agent.AgentQueryConfig
import com.agentwork.graphmesh.agent.AgentQueryResult
import com.agentwork.graphmesh.agent.ToolGroup
import com.agentwork.graphmesh.agent.ToolInfo

interface AgentUseCases {
    fun query(
        question: String,
        collectionId: String,
        config: AgentQueryConfig = AgentQueryConfig()
    ): AgentQueryResult

    fun getAvailableTools(): List<ToolInfo>
    fun getToolGroups(): List<ToolGroup>
}
```

Note: verify the exact signature of `query(...)` in `AgentService.kt` before finalizing.

- [ ] **Step 2: Make AgentService implement the interface**

In `AgentService.kt`:
- Add import: `import com.agentwork.graphmesh.agent.application.port.`in`.AgentUseCases`
- Change: `class AgentService(...) : AgentUseCases {`
- Add `override` to `fun query(...)`, `fun getAvailableTools()`, `fun getToolGroups()`.

- [ ] **Step 3: Update callers**

In `api/AgentController.kt` and `api/mcp/GraphMeshMcpTools.kt`: change `AgentService` → `AgentUseCases` (import + injection type).

- [ ] **Step 4: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/agent/ \
        src/main/kotlin/com/agentwork/graphmesh/api/AgentController.kt \
        src/main/kotlin/com/agentwork/graphmesh/api/mcp/GraphMeshMcpTools.kt
git commit -m "refactor(agent): introduce AgentUseCases input port"
```

---

## Task 7: `contextcore` — ContextCoreUseCases + heavy operation use cases

Reads + `delete` + `tag` stay grouped. `build` and `import` are split — both are heavyweight multi-step operations involving quads, vectors, embeddings, ontology, and registry writes.

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/contextcore/application/port/in/ContextCoreUseCases.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/contextcore/application/port/in/BuildContextCoreUseCase.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/contextcore/application/port/in/ImportContextCoreUseCase.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/contextcore/ContextCoreService.kt`
- Modify (callers): `api/ContextCoreController.kt`

- [ ] **Step 1: Create the three interface files**

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/contextcore/application/port/in/ContextCoreUseCases.kt
package com.agentwork.graphmesh.contextcore.application.port.`in`

import com.agentwork.graphmesh.contextcore.CoreManifest

interface ContextCoreUseCases {
    fun list(): List<CoreManifest>
    fun find(coreId: String, version: String): CoreManifest?
    fun findByTag(coreId: String, tag: String): CoreManifest?
    fun delete(coreId: String, version: String)
    fun tag(coreId: String, version: String, tag: String): CoreManifest?
}
```

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/contextcore/application/port/in/BuildContextCoreUseCase.kt
package com.agentwork.graphmesh.contextcore.application.port.`in`

import com.agentwork.graphmesh.contextcore.BuildRequest
import com.agentwork.graphmesh.contextcore.CoreManifest

interface BuildContextCoreUseCase {
    fun buildContextCore(request: BuildRequest): CoreManifest
}
```

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/contextcore/application/port/in/ImportContextCoreUseCase.kt
package com.agentwork.graphmesh.contextcore.application.port.`in`

import com.agentwork.graphmesh.contextcore.ImportRequest
import com.agentwork.graphmesh.contextcore.ImportResult

interface ImportContextCoreUseCase {
    fun importContextCore(request: ImportRequest): ImportResult
}
```

- [ ] **Step 2: Make ContextCoreService implement all three interfaces**

In `ContextCoreService.kt`:
- Add imports for all three interfaces.
- Change: `class ContextCoreService(...) : ContextCoreUseCases, BuildContextCoreUseCase, ImportContextCoreUseCase {`
- Rename `fun build(request: BuildRequest)` → `override fun buildContextCore(request: BuildRequest)`.
- Rename `fun import(request: ImportRequest)` → `override fun importContextCore(request: ImportRequest)`.
- Add `override` to reads.

- [ ] **Step 3: Update callers**

In `api/ContextCoreController.kt`: inject three interfaces and update call sites to use the renamed methods (`build(...)` → `buildContextCore(...)`, `import(...)` → `importContextCore(...)`).

- [ ] **Step 4: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/contextcore/ \
        src/main/kotlin/com/agentwork/graphmesh/api/ContextCoreController.kt
git commit -m "refactor(contextcore): split ContextCoreUseCases into reads + operation use cases"
```

---

## Task 8: `query/docrag` — DocumentRagUseCase input port

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/query/docrag/application/port/in/DocumentRagUseCase.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt`
- Modify (callers): `api/DocumentRagController.kt`, `agent/AgentService.kt`, `agent/AgentQueryTools.kt`, `agent/StreamingAgentServiceImpl.kt`, `extraction/agent/AgentExtractorService.kt`, `extraction/agent/ExtractionTools.kt`, `query/nlp/NlpQueryService.kt`

- [ ] **Step 1: Create the interface**

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/query/docrag/application/port/in/DocumentRagUseCase.kt
package com.agentwork.graphmesh.query.docrag.application.port.`in`

import com.agentwork.graphmesh.query.docrag.DocumentRagQuery
import com.agentwork.graphmesh.query.docrag.DocumentRagResult

interface DocumentRagUseCase {
    fun query(query: DocumentRagQuery): DocumentRagResult
}
```

- [ ] **Step 2: Make DocumentRagService implement the interface**

In `DocumentRagService.kt`:
- Add import: `import com.agentwork.graphmesh.query.docrag.application.port.`in`.DocumentRagUseCase`
- Change: `class DocumentRagService(...) : DocumentRagUseCase {`
- Add `override` to `fun query(query: DocumentRagQuery): DocumentRagResult`.

- [ ] **Step 3: Update callers**

In each caller file (7 files): replace `DocumentRagService` import with `DocumentRagUseCase` import and change the injection type.

- [ ] **Step 4: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/query/docrag/ \
        src/main/kotlin/com/agentwork/graphmesh/api/DocumentRagController.kt \
        src/main/kotlin/com/agentwork/graphmesh/agent/ \
        src/main/kotlin/com/agentwork/graphmesh/extraction/agent/
git commit -m "refactor(query/docrag): introduce DocumentRagUseCase input port"
```

---

## Task 9: `query/graphrag` — GraphRagUseCase input port

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/application/port/in/GraphRagUseCase.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`
- Modify (callers): `api/GraphRagController.kt`, `agent/AgentService.kt`, `agent/AgentQueryTools.kt`, `agent/StreamingAgentServiceImpl.kt`, `extraction/agent/AgentExtractorService.kt`, `extraction/agent/ExtractionTools.kt`, `query/nlp/NlpQueryService.kt`

- [ ] **Step 1: Create the interface**

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/query/graphrag/application/port/in/GraphRagUseCase.kt
package com.agentwork.graphmesh.query.graphrag.application.port.`in`

import com.agentwork.graphmesh.query.graphrag.GraphRagQuery
import com.agentwork.graphmesh.query.graphrag.GraphRagResult

interface GraphRagUseCase {
    fun query(query: GraphRagQuery): GraphRagResult
}
```

- [ ] **Step 2: Make GraphRagService implement the interface**

In `GraphRagService.kt`: add import, change class to `: GraphRagUseCase`, add `override` to `fun query(...)`.

- [ ] **Step 3: Update callers** — same pattern as Task 8.

- [ ] **Step 4: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/query/graphrag/ \
        src/main/kotlin/com/agentwork/graphmesh/api/GraphRagController.kt \
        src/main/kotlin/com/agentwork/graphmesh/agent/ \
        src/main/kotlin/com/agentwork/graphmesh/extraction/agent/
git commit -m "refactor(query/graphrag): introduce GraphRagUseCase input port"
```

---

## Task 10: `query/nlp` — NlpQueryUseCase input port

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/query/nlp/application/port/in/NlpQueryUseCase.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt`
- Modify (callers): `api/NlpQueryController.kt`

- [ ] **Step 1: Create the interface**

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/query/nlp/application/port/in/NlpQueryUseCase.kt
package com.agentwork.graphmesh.query.nlp.application.port.`in`

import com.agentwork.graphmesh.query.nlp.NlpQuery
import com.agentwork.graphmesh.query.nlp.NlpQueryResult

interface NlpQueryUseCase {
    fun query(query: NlpQuery): NlpQueryResult
}
```

- [ ] **Step 2: Make NlpQueryService implement the interface + update caller**

Same pattern as Tasks 8 and 9.

- [ ] **Step 3: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/query/nlp/ \
        src/main/kotlin/com/agentwork/graphmesh/api/NlpQueryController.kt
git commit -m "refactor(query/nlp): introduce NlpQueryUseCase input port"
```

---

## Task 11: `skos` — SkosUseCases input port

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/skos/application/port/in/SkosUseCases.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/skos/SkosService.kt`
- Modify (callers): `extraction/topic/TopicOntologyMatcher.kt`, any `api/` or `skos/` controller injecting SkosService

- [ ] **Step 1: Create the interface**

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/skos/application/port/in/SkosUseCases.kt
package com.agentwork.graphmesh.skos.application.port.`in`

import com.agentwork.graphmesh.skos.SkosConcept
import com.agentwork.graphmesh.skos.SkosConceptScheme

interface SkosUseCases {
    fun getConceptSchemes(collectionId: String): List<SkosConceptScheme>
    fun getConcepts(collectionId: String, schemeUri: String): List<SkosConcept>
    fun getTopConcepts(collectionId: String, schemeUri: String): List<SkosConcept>
    fun getNarrower(collectionId: String, conceptUri: String): List<SkosConcept>
    fun getBroader(collectionId: String, conceptUri: String): List<SkosConcept>
    fun getRelated(collectionId: String, conceptUri: String): List<SkosConcept>
    fun findByLabel(collectionId: String, label: String): List<SkosConcept>
    fun getConcept(collectionId: String, conceptUri: String): SkosConcept?
    fun countConcepts(collectionId: String, schemeUri: String): Int
}
```

- [ ] **Step 2: Make SkosService implement the interface + update caller (TopicOntologyMatcher)**

Same pattern.

- [ ] **Step 3: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/skos/ \
        src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicOntologyMatcher.kt
git commit -m "refactor(skos): introduce SkosUseCases input port"
```

---

## Task 12: `structured` — StructuredDataUseCases input port

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/structured/application/port/in/StructuredDataUseCases.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/structured/StructuredDataService.kt`
- Modify (callers): `extraction/structured/StructuredDataExtractorService.kt`

- [ ] **Step 1: Create the interface**

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/structured/application/port/in/StructuredDataUseCases.kt
package com.agentwork.graphmesh.structured.application.port.`in`

import com.agentwork.graphmesh.structured.DataRow
import com.agentwork.graphmesh.structured.QueryResult
import com.agentwork.graphmesh.structured.StoreResult
import com.agentwork.graphmesh.structured.StructuredQuery
import com.agentwork.graphmesh.structured.TableSchema

interface StructuredDataUseCases {
    fun store(row: DataRow): StoreResult
    fun storeBatch(rows: List<DataRow>): List<StoreResult>
    fun query(query: StructuredQuery): QueryResult
    fun saveSchema(schema: TableSchema)
    fun getSchema(name: String): TableSchema?
    fun listSchemas(): List<String>
    fun deleteSchema(name: String)
}
```

Note: verify `QueryResult`, `StoreResult`, `StructuredQuery` exist in `structured/Models.kt` before creating.

- [ ] **Step 2: Make StructuredDataService implement the interface + update caller**

Same pattern.

- [ ] **Step 3: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/structured/ \
        src/main/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorService.kt
git commit -m "refactor(structured): introduce StructuredDataUseCases input port"
```

---

## Task 13: `provenance` — ProvenanceUseCases input port

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/provenance/application/port/in/ProvenanceUseCases.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/provenance/ProvenanceService.kt`
- Modify (callers): `extraction/agent/AgentExtractorService.kt`, `extraction/definition/DefinitionExtractorService.kt`, `extraction/relationship/RelationshipExtractorService.kt`, `extraction/topic/TopicExtractorService.kt`

- [ ] **Step 1: Create the interface**

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/provenance/application/port/in/ProvenanceUseCases.kt
package com.agentwork.graphmesh.provenance.application.port.`in`

import com.agentwork.graphmesh.provenance.SubgraphProvenance
import com.agentwork.graphmesh.rdf.Quad

interface ProvenanceUseCases {
    fun buildSubgraphQuads(provenance: SubgraphProvenance): List<Quad>
}
```

Note: verify the return type `Quad` (check if it's `com.agentwork.graphmesh.rdf.Quad` or `com.agentwork.graphmesh.storage.StoredQuad`) in `ProvenanceService.kt`.

- [ ] **Step 2: Make ProvenanceService implement + update 4 callers**

Same pattern. In each extraction service: change `ProvenanceService` → `ProvenanceUseCases`.

- [ ] **Step 3: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/provenance/ \
        src/main/kotlin/com/agentwork/graphmesh/extraction/
git commit -m "refactor(provenance): introduce ProvenanceUseCases input port"
```

---

## Task 14: `storage` — OrphanSweepUseCase input port

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/application/port/in/OrphanSweepUseCase.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/storage/OrphanSweepService.kt`
- Modify (caller): `src/main/kotlin/com/agentwork/graphmesh/api/PurgeService.kt`

- [ ] **Step 1: Create the interface and move `Result` type into it**

The nested `OrphanSweepService.Result` becomes a standalone data class alongside the interface so the port doesn't reference its own implementation:

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/storage/application/port/in/OrphanSweepUseCase.kt
package com.agentwork.graphmesh.storage.application.port.`in`

data class OrphanSweepResult(
    val orphanedBlobs: Int,
    val orphanedVectors: Int,
    val orphanedQuads: Int
)

interface OrphanSweepUseCase {
    fun sweep(): OrphanSweepResult
}
```

Note: check the actual fields of `OrphanSweepService.Result` (run `cat src/main/kotlin/com/agentwork/graphmesh/storage/OrphanSweepService.kt`) and match them exactly in `OrphanSweepResult`.

- [ ] **Step 2: Update OrphanSweepService**

In `OrphanSweepService.kt`:
- Add import: `import com.agentwork.graphmesh.storage.application.port.`in`.OrphanSweepUseCase`, `import com.agentwork.graphmesh.storage.application.port.`in`.OrphanSweepResult`
- Change class to: `class OrphanSweepService(...) : OrphanSweepUseCase {`
- Change `fun sweep(): Result {` to `override fun sweep(): OrphanSweepResult {`
- Change any internal `Result(...)` constructor calls to `OrphanSweepResult(...)`
- Remove or keep the old nested `data class Result` (remove it to avoid confusion)

- [ ] **Step 3: Update api/PurgeService.kt**

- Replace `import com.agentwork.graphmesh.storage.OrphanSweepService` with `import com.agentwork.graphmesh.storage.application.port.`in`.OrphanSweepUseCase`
- Also add: `import com.agentwork.graphmesh.storage.application.port.`in`.OrphanSweepResult`
- Change `private val orphanSweepService: OrphanSweepService` → `private val orphanSweepService: OrphanSweepUseCase`
- Update the return type of `purgeOrphansOnly()` from `OrphanSweepService.Result` to `OrphanSweepResult`

- [ ] **Step 4: Build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/application/ \
        src/main/kotlin/com/agentwork/graphmesh/storage/OrphanSweepService.kt \
        src/main/kotlin/com/agentwork/graphmesh/api/PurgeService.kt
git commit -m "refactor(storage): introduce OrphanSweepUseCase input port"
```

---

## Test strategy for this PR

Tests are split by interface boundary:

- **`*UseCasesTest.kt` (unit)** — tests the `*Service` implementation against its input port contract. The service is constructed with all output ports mocked (`CollectionStore`, `BlobStore`, etc.). The service is assigned to the interface type — this proves it compiles as the port. Tests cover happy path and failure paths for each method exposed by the interface.
- **`Cassandra*StoreIntegrationTest.kt` (integration)** — tests the adapter against real Cassandra. These live in PR 2 / PR 4 when the structural layout is in place.

The rule: **never test through a mock of the port** — the mock IS the port in downstream tests. Test the service that implements the port, mocking the ports it depends on downstream.

---

## Task 15: Update existing tests that mock concrete service classes

Callers in existing tests use `mockk<LibrarianService>()`. After PR 1 the injected constructor type is `LibrarianUseCases`, so mockk must use the interface type or the code won't compile.

**Files:**
- Modify: all test files that call `mockk<XxxService>()` for a service that now has an input port interface

- [ ] **Step 1: Find all affected tests**

```bash
grep -rn "mockk<.*Service>" src/test/ --include="*.kt"
```

For each hit, check if that service gained an interface in this PR. If yes: update the mock type and import.

- [ ] **Step 2: Update CollectionContentTypeServiceTest**

`src/test/kotlin/com/agentwork/graphmesh/query/CollectionContentTypeServiceTest.kt` currently mocks `LibrarianService`. Change:

```kotlin
// before
import com.agentwork.graphmesh.librarian.LibrarianService
private val librarianService = mockk<LibrarianService>()

// after
import com.agentwork.graphmesh.librarian.application.port.`in`.LibrarianUseCases
private val librarianService = mockk<LibrarianUseCases>()
```

The `CollectionContentTypeService` constructor now takes `LibrarianUseCases` (changed in Task 3), so `mockk<LibrarianUseCases>()` is the correct type.

- [ ] **Step 3: Apply the same pattern to every test found in Step 1**

For each: `mockk<XxxService>()` → `mockk<XxxUseCases>()` (or `mockk<XxxUseCase>()` for single-method ports). Update the import line.

- [ ] **Step 4: Run tests**

```bash
./gradlew test
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/test/
git commit -m "test: update mocks to input port interface types after PR 1"
```

---

## Task 16: Add `CollectionUseCasesTest`

One test class per input port interface. The service under test is assigned to the interface type — this proves the service satisfies the interface contract and lets us refactor the implementation freely as long as the interface tests remain green.

**File:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/collection/CollectionUseCasesTest.kt`

- [ ] **Step 1: Write the test class**

```kotlin
// src/test/kotlin/com/agentwork/graphmesh/collection/CollectionUseCasesTest.kt
package com.agentwork.graphmesh.collection

import com.agentwork.graphmesh.collection.application.port.`in`.CollectionUseCases
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CollectionUseCasesTest {

    private val store = mockk<CollectionStore>(relaxed = true)
    private val lifecycleManager = mockk<CollectionLifecycleManager>(relaxed = true)
    private val eventPublisher = mockk<org.springframework.context.ApplicationEventPublisher>(relaxed = true)
    private val eventProducer = mockk<CollectionEventProducer>(relaxed = true)

    // Assigned to the interface type — proves CollectionService satisfies CollectionUseCases
    private val useCases: CollectionUseCases = CollectionService(
        collectionStore = store,
        lifecycleManager = lifecycleManager,
        eventPublisher = eventPublisher,
        collectionEventProducer = eventProducer,
    )

    @Test
    fun `create saves collection and returns it`() {
        every { store.findByName("my-collection") } returns null
        every { store.save(any()) } returns Unit

        val result = useCases.create("my-collection")

        assertEquals("my-collection", result.name)
        verify { store.save(any()) }
    }

    @Test
    fun `create throws when name already exists`() {
        val existing = Collection(id = "col-1", name = "duplicate")
        every { store.findByName("duplicate") } returns existing

        assertFailsWith<IllegalArgumentException> {
            useCases.create("duplicate")
        }
    }

    @Test
    fun `findById returns null when collection does not exist`() {
        every { store.findById("missing") } returns null

        assertNull(useCases.findById("missing"))
    }

    @Test
    fun `findAll returns all collections`() {
        val cols = listOf(
            Collection(id = "col-1", name = "first"),
            Collection(id = "col-2", name = "second"),
        )
        every { store.findAll() } returns cols

        val result = useCases.findAll()

        assertEquals(2, result.size)
    }

    @Test
    fun `delete calls lifecycleManager`() {
        val col = Collection(id = "col-1", name = "to-delete")
        every { store.findById("col-1") } returns col
        every { store.exists("col-1") } returns true

        useCases.delete("col-1")

        verify { lifecycleManager.purge("col-1") }
    }
}
```

Note: adjust constructor parameter names to match `CollectionService.kt` exactly. If `CollectionService` has a different parameter name for the store (e.g., `collectionStore` vs `store`), use the actual name.

- [ ] **Step 2: Run the failing test first**

```bash
./gradlew test --tests "com.agentwork.graphmesh.collection.CollectionUseCasesTest"
```

If it fails with a compilation error: fix the constructor parameter names. If a test assertion fails: inspect why and fix the test expectation (not the production code) unless there is a genuine bug.

- [ ] **Step 3: Write `LibrarianUseCasesTest`**

**File:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/librarian/LibrarianUseCasesTest.kt`

```kotlin
// src/test/kotlin/com/agentwork/graphmesh/librarian/LibrarianUseCasesTest.kt
package com.agentwork.graphmesh.librarian

import com.agentwork.graphmesh.collection.application.port.`in`.CollectionUseCases
import com.agentwork.graphmesh.librarian.application.port.`in`.LibrarianUseCases
import com.agentwork.graphmesh.librarian.application.port.out.DocumentStore
import com.agentwork.graphmesh.messaging.DocumentIngestedProducer
import com.agentwork.graphmesh.storage.blob.BlobStore
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibrarianUseCasesTest {

    private val documentStore = mockk<DocumentStore>(relaxed = true)
    private val blobStore = mockk<BlobStore>(relaxed = true)
    private val collectionUseCases = mockk<CollectionUseCases>(relaxed = true)
    private val producer = mockk<DocumentIngestedProducer>(relaxed = true)

    private val useCases: LibrarianUseCases = LibrarianService(
        documentStore = documentStore,
        blobStore = blobStore,
        collectionService = collectionUseCases,
        documentIngestedProducer = producer,
        defaultBucket = "test-bucket",
    )

    @Test
    fun `findById returns null when document does not exist`() {
        every { documentStore.findById("missing") } returns null
        assertNull(useCases.findById("missing"))
    }

    @Test
    fun `findByCollection delegates to documentStore`() {
        // Construct a minimal Document — check Document.kt for the actual constructor
        val doc = mockk<Document>()
        every { doc.id } returns "doc-1"
        every { documentStore.findByCollection("col-1", null) } returns listOf(doc)

        val result = useCases.findByCollection("col-1")

        assertEquals(1, result.size)
    }

    @Test
    fun `updateState delegates to documentStore`() {
        useCases.updateState("doc-1", DocumentState.PROCESSING)
        io.mockk.verify { documentStore.updateState("doc-1", DocumentState.PROCESSING) }
    }
}
```

- [ ] **Step 4: Run both new test classes**

```bash
./gradlew test --tests "com.agentwork.graphmesh.collection.CollectionUseCasesTest" \
               --tests "com.agentwork.graphmesh.librarian.LibrarianUseCasesTest"
```

Expected: all cases pass.

- [ ] **Step 5: Add `*UseCasesTest` for remaining interfaces (abbreviated pattern)**

For each remaining interface introduced in Tasks 4–14, create a corresponding test file following the same pattern. The minimum bar per test class is **two tests: one happy path, one failure/null path**.

Test files to create:
- `src/test/kotlin/com/agentwork/graphmesh/ontology/OntologyUseCasesTest.kt`
- `src/test/kotlin/com/agentwork/graphmesh/config/ConfigUseCasesTest.kt`
- `src/test/kotlin/com/agentwork/graphmesh/skos/SkosUseCasesTest.kt`
- `src/test/kotlin/com/agentwork/graphmesh/provenance/ProvenanceUseCasesTest.kt` (rename/extend the existing `ProvenanceServiceTest.kt` to assign the service to `ProvenanceUseCases`)
- `src/test/kotlin/com/agentwork/graphmesh/structured/StructuredDataUseCasesTest.kt`

For each: mock all output ports the service injects, assign the service to the `*UseCases` interface type, write two tests minimum.

- [ ] **Step 6: Run all new tests**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 7: Commit**

```bash
git add src/test/
git commit -m "test: add UseCases unit tests — one per input port interface"
```

---

## Task 17: Full build and test

- [ ] **Step 1: Full build with tests**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 2: Final PR 1 commit (if any clean-up needed)**

```bash
git add -p
git commit -m "refactor: PR1 cleanup — input port wiring"
```
