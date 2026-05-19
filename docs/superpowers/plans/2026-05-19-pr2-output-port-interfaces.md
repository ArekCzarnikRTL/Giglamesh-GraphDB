# PR 2 — Output Port Interfaces Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the three existing `*Store` interfaces (`CollectionStore`, `DocumentStore`, `ConfigStore`) from their flat feature package into `application/port/out/` subdirectories. Update package declarations in the interface files, the `implements` clause in the adapter (implementation) classes, and all import sites.

**Architecture:** Pure rename — no logic changes, no new abstractions. Each interface moves one level deeper into `<feature>/application/port/out/`. The implementing `Cassandra*Store` classes stay in their current location and update their `implements` imports. Callers (service classes) update their imports only.

**Tech Stack:** Kotlin, Spring Boot 4.0.5, Gradle 9.4.1. Build: `./gradlew build`. Tests: `./gradlew test`.

**Prerequisites:** PR 1 merged into `feat/layer-to-hexarch-switch`.

---

## Scope

Only the three store interfaces that currently live at the feature package root are moved:

| Current location | New location |
|---|---|
| `collection/CollectionStore.kt` | `collection/application/port/out/CollectionStore.kt` |
| `librarian/DocumentStore.kt` | `librarian/application/port/out/DocumentStore.kt` |
| `config/ConfigStore.kt` | `config/application/port/out/ConfigStore.kt` |

Shared infrastructure interfaces (`storage/QuadStore.kt`, `storage/blob/BlobStore.kt`, `storage/vector/VectorStore.kt`) are **not** moved — they are shared infra utilities, not feature-owned output ports.

Kafka producers (`CollectionEventProducer`, `ConfigChangeProducer`, `DocumentIngestedProducer`) are concrete classes with no existing interface — introducing output port interfaces for them is deferred to PR 3/PR 4 if needed.

---

## File Map

**New files (interface with updated package):**
- Create: `src/main/kotlin/com/agentwork/graphmesh/collection/application/port/out/CollectionStore.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/librarian/application/port/out/DocumentStore.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/config/application/port/out/ConfigStore.kt`

**Files to delete (old interface location):**
- Delete: `src/main/kotlin/com/agentwork/graphmesh/collection/CollectionStore.kt`
- Delete: `src/main/kotlin/com/agentwork/graphmesh/librarian/DocumentStore.kt`
- Delete: `src/main/kotlin/com/agentwork/graphmesh/config/ConfigStore.kt`

**Modified (update package/imports):**
- `src/main/kotlin/com/agentwork/graphmesh/collection/CassandraCollectionStore.kt` — implements CollectionStore
- `src/main/kotlin/com/agentwork/graphmesh/collection/CollectionService.kt` — injects CollectionStore
- `src/main/kotlin/com/agentwork/graphmesh/librarian/CassandraDocumentStore.kt` — implements DocumentStore
- `src/main/kotlin/com/agentwork/graphmesh/librarian/LibrarianService.kt` — injects DocumentStore
- `src/main/kotlin/com/agentwork/graphmesh/api/PurgeService.kt` — imports `librarian.DocumentStore` directly
- `src/main/kotlin/com/agentwork/graphmesh/collection/CollectionLifecycleManager.kt` — injects DocumentStore (cross-feature)
- `src/main/kotlin/com/agentwork/graphmesh/config/CassandraConfigStore.kt` — implements ConfigStore
- `src/main/kotlin/com/agentwork/graphmesh/config/ConfigService.kt` — injects ConfigStore

---

## Task 1: Move `CollectionStore` to output port

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/collection/application/port/out/CollectionStore.kt`
- Delete: `src/main/kotlin/com/agentwork/graphmesh/collection/CollectionStore.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/collection/CassandraCollectionStore.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/collection/CollectionService.kt`

- [ ] **Step 1: Create the interface at the new location**

Copy the content of the current `CollectionStore.kt` verbatim, updating only the `package` line:

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/collection/application/port/out/CollectionStore.kt
package com.agentwork.graphmesh.collection.application.port.out

import com.agentwork.graphmesh.collection.Collection

interface CollectionStore {
    fun save(collection: Collection)
    fun findById(id: String): Collection?
    fun findByName(name: String): Collection?
    fun findAll(): List<Collection>
    fun delete(id: String)
    fun exists(id: String): Boolean
}
```

- [ ] **Step 2: Delete the old file**

```bash
rm src/main/kotlin/com/agentwork/graphmesh/collection/CollectionStore.kt
```

- [ ] **Step 3: Update CassandraCollectionStore**

In `collection/CassandraCollectionStore.kt`:
- Replace `import com.agentwork.graphmesh.collection.CollectionStore` with `import com.agentwork.graphmesh.collection.application.port.out.CollectionStore`
- The `class CassandraCollectionStore(...) : CollectionStore` declaration remains unchanged.

- [ ] **Step 4: Update CollectionService**

In `collection/CollectionService.kt`:
- Replace `import com.agentwork.graphmesh.collection.CollectionStore` (if explicit) with `import com.agentwork.graphmesh.collection.application.port.out.CollectionStore`
- If `CollectionStore` is referenced in the constructor parameter, the type reference now resolves to the new package. No change to the parameter name needed.

- [ ] **Step 5: Build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL. If you see "unresolved reference: CollectionStore", you missed an import site — run `grep -rn "CollectionStore" src/ --include="*.kt"` to find it.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/collection/
git commit -m "refactor(collection): move CollectionStore to application/port/out"
```

---

## Task 2: Move `DocumentStore` to output port

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/librarian/application/port/out/DocumentStore.kt`
- Delete: `src/main/kotlin/com/agentwork/graphmesh/librarian/DocumentStore.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/librarian/CassandraDocumentStore.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/librarian/LibrarianService.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/api/PurgeService.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/collection/CollectionLifecycleManager.kt`

- [ ] **Step 1: Create the interface at the new location**

Copy content from `librarian/DocumentStore.kt`, update package only:

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/librarian/application/port/out/DocumentStore.kt
package com.agentwork.graphmesh.librarian.application.port.out

import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentState
import com.agentwork.graphmesh.librarian.DocumentType

interface DocumentStore {
    fun save(document: Document)
    fun findById(id: String): Document?
    fun findByCollection(collectionId: String, type: DocumentType? = null): List<Document>
    fun findChildren(parentId: String): List<Document>
    fun updateState(id: String, state: DocumentState)
    fun delete(id: String)
    fun deleteWithChildren(id: String)
}
```

- [ ] **Step 2: Delete the old file**

```bash
rm src/main/kotlin/com/agentwork/graphmesh/librarian/DocumentStore.kt
```

- [ ] **Step 3: Update all files that imported `librarian.DocumentStore`**

Find every import site:
```bash
grep -rn "import com.agentwork.graphmesh.librarian.DocumentStore" src/ --include="*.kt"
```

For each file found, replace `import com.agentwork.graphmesh.librarian.DocumentStore` with `import com.agentwork.graphmesh.librarian.application.port.out.DocumentStore`.

Confirmed files to update:
- `librarian/CassandraDocumentStore.kt` — implements the interface
- `librarian/LibrarianService.kt` — injects DocumentStore (now via port; PR 1 left it here)
- `api/PurgeService.kt` — imports DocumentStore to call `documentStore.findByCollection(...)` and `documentStore.deleteWithChildren(...)`
- `collection/CollectionLifecycleManager.kt` — injects DocumentStore

Note: `api/PurgeService.kt` injects `DocumentStore` directly. This is a PR 3 concern (cross-feature access to librarian's output port). For now, just update the import path so it still compiles.

- [ ] **Step 4: Build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/librarian/ \
        src/main/kotlin/com/agentwork/graphmesh/api/PurgeService.kt \
        src/main/kotlin/com/agentwork/graphmesh/collection/CollectionLifecycleManager.kt
git commit -m "refactor(librarian): move DocumentStore to application/port/out"
```

---

## Task 3: Move `ConfigStore` to output port

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/config/application/port/out/ConfigStore.kt`
- Delete: `src/main/kotlin/com/agentwork/graphmesh/config/ConfigStore.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/config/CassandraConfigStore.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/config/ConfigService.kt`

- [ ] **Step 1: Create the interface at the new location**

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/config/application/port/out/ConfigStore.kt
package com.agentwork.graphmesh.config.application.port.out

import com.agentwork.graphmesh.config.ConfigItem
import com.agentwork.graphmesh.config.ConfigType

interface ConfigStore {
    fun save(item: ConfigItem): ConfigItem
    fun findById(id: String): ConfigItem?
    fun findByType(type: ConfigType): List<ConfigItem>
    fun findByTypeAndKey(type: ConfigType, key: String): ConfigItem?
    fun delete(id: String)
    fun history(id: String, limit: Int = 10): List<ConfigItem>
}
```

- [ ] **Step 2: Delete the old file**

```bash
rm src/main/kotlin/com/agentwork/graphmesh/config/ConfigStore.kt
```

- [ ] **Step 3: Update import sites**

```bash
grep -rn "import com.agentwork.graphmesh.config.ConfigStore" src/ --include="*.kt"
```

For each file found: replace the import. Confirmed files:
- `config/CassandraConfigStore.kt` — implements ConfigStore
- `config/ConfigService.kt` — injects ConfigStore

- [ ] **Step 4: Build + Commit**

```bash
./gradlew build -x test
git add src/main/kotlin/com/agentwork/graphmesh/config/
git commit -m "refactor(config): move ConfigStore to application/port/out"
```

---

## Test strategy for this PR

Moving interfaces to new packages breaks existing tests that import them. No new test logic is needed — the move itself is verified by the build. The one required test action is updating import paths in existing `*UseCasesTest` files written in PR 1.

## Task 4: Update test imports and run full suite

- [ ] **Step 1: Find tests that import the old store package paths**

```bash
grep -rn "graphmesh.collection.CollectionStore\|graphmesh.librarian.DocumentStore\|graphmesh.config.ConfigStore" \
  src/test/ --include="*.kt"
```

- [ ] **Step 2: Update each import to the new `application.port.out` path**

For each test file found:
- `com.agentwork.graphmesh.collection.CollectionStore` → `com.agentwork.graphmesh.collection.application.port.out.CollectionStore`
- `com.agentwork.graphmesh.librarian.DocumentStore` → `com.agentwork.graphmesh.librarian.application.port.out.DocumentStore`
- `com.agentwork.graphmesh.config.ConfigStore` → `com.agentwork.graphmesh.config.application.port.out.ConfigStore`

The mock type name stays identical; only the import line changes:
```kotlin
// before
import com.agentwork.graphmesh.librarian.DocumentStore

// after
import com.agentwork.graphmesh.librarian.application.port.out.DocumentStore
```

- [ ] **Step 3: Full build with tests**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 4: Verify no stale package references remain**

```bash
grep -rn "graphmesh.collection.CollectionStore\|graphmesh.librarian.DocumentStore\|graphmesh.config.ConfigStore" \
  src/ --include="*.kt" | grep -v "application/port/out"
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add src/test/
git commit -m "test: update store imports to application/port/out after PR 2"
```
