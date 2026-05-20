# Hexagonal Architecture Rules for GraphMesh

**Date:** 2026-05-19
**Status:** Approved
**Scope:** All backend feature packages under `com.agentwork.graphmesh`

## Motivation

The current flat feature-per-package structure has no enforced dependency direction. Services call other services directly, controllers depend on concrete classes, and infrastructure details leak into application logic. This makes isolated unit testing hard and cross-feature coupling invisible.

Adopting hexagonal architecture within each Spring Modulith feature package gives us:
- Testable application logic without infrastructure
- Explicit ports as stable test seams — split tests by interface, refactor internals freely
- Visible cross-feature coupling (all inter-module calls go through input port interfaces)
- Clear place for everything: models, ports, adapters

## Canonical Package Structure

Every feature package must follow this layout:

```
com.agentwork.graphmesh.<feature>/
  domain/
    <Entity>.kt                        # Aggregates, entities, value objects
    <Feature>Exception.kt              # Domain-specific exceptions
  application/
    port/
      in/
        <Feature>UseCases.kt           # Grouped input port (CRUD-style, multiple methods)
        <Action><Feature>UseCase.kt    # Single-method input port (complex flows)
      out/
        <Feature>Store.kt              # Output port: persistence
        <Feature>EventPort.kt          # Output port: messaging/events
        <Feature>Port.kt               # Output port: external services
    <Feature>Service.kt                # Implements port/in/ interface(s)
  adapter/
    in/                                # Driving adapters — call application/port/in/
      <Feature>KafkaConsumer.kt
      <Feature>Controller.kt           # (moved here from global api/ in PR4)
      <Feature>McpTools.kt
    out/                               # Driven adapters — implement application/port/out/
      Cassandra<Feature>Store.kt
      Kafka<Feature>Producer.kt
      S3<Feature>Store.kt
```

### Shared Domain (`common/domain/`)

Types shared across multiple features live in `com.agentwork.graphmesh.common.domain`. No feature-specific logic. No Spring annotations. No inward dependencies on any feature.

Current contents:
- `rdf/`: `Quad`, `RdfTerm`, `QuadConverter`, `EntityIdGenerator`, `NamespaceRegistry`
- `tenant/`: `TenantContext`

## Dependency Rules

```
adapter/in/  →  application/port/in/
                       ↓
                application/<Feature>Service
                       ↓
                application/port/out/
                       ↑
                  adapter/out/

All layers  →  domain/  →  common/domain/
```

1. **Inward only.** Dependency direction always points toward the domain. No layer may depend on a layer outside it.

2. **`domain/`** depends only on `common/domain`. No Spring, no infrastructure, no other features.

3. **`application/`** depends on its own `domain/` and `common/domain`. May use shared infrastructure utilities (`llm/`, `tenant/`). Never depends on `adapter/`.

4. **`adapter/out/`** implements `application/port/out/` interfaces. May use `domain/` models. Never depends on `application/` services directly.

5. **`adapter/in/`** depends on `application/port/in/` interfaces only. Never instantiates concrete `*Service` classes.

6. **Cross-feature calls:** Only via the target feature's `application/port/in/` interfaces. No direct calls into another feature's `domain/`, `adapter/`, or concrete service class.

7. **Use cases do not call other use cases.** Shared logic belongs in a domain service or a shared `application/` component. Cross-feature orchestration uses Spring events or a dedicated orchestration feature.

8. **Global `api/` package (transitional):** Controllers here must depend on feature input port interfaces, never on concrete `*Service` classes.

## Naming Conventions

### Domain (`domain/`)
- Entities/aggregates: `<Entity>.kt` — no suffix
- Exceptions: `<Condition>Exception.kt`
- No `Impl`, no `Manager`, no `Helper` suffixes

### Input ports (`application/port/in/`)
- Grouped interface: `<Feature>UseCases` — multiple methods, CRUD-style operations
- Single-method interface: `<Action><Feature>UseCase` — one `invoke(command): Result` method
- Commands: `<Action><Feature>Command` data class alongside the interface

### Output ports (`application/port/out/`)
- Persistence: `<Feature>Store`
- Messaging: `<Feature>EventPort`
- External services: `<Feature>Port`

### Application services (`application/`)
- `<Feature>Service` — implements one or more `port/in/` interfaces

### Adapters
- `adapter/in/`: `<Feature>Controller`, `<Feature>KafkaConsumer`, `<Feature>McpTools`, `<Feature>CliCommand`
- `adapter/out/`: `Cassandra<Feature>Store`, `Kafka<Feature>Producer`, `S3<Feature>Store`

## Classifying Infrastructure Classes

Some classes don't map cleanly to entities, services, or adapters. The deciding question is:

> **Does this class just drive infrastructure, or does it make decisions about what to do?**

- **Drives infrastructure only → `adapter/out/`** — no business logic, no conditional behaviour, just bootstrapping or translating. Examples: schema initializers (`CollectionSchemaInitializer`), `@Configuration` beans, Kafka producers, registries.
- **Makes decisions about what to do → `application/`** — contains logic about which steps to take, how to handle partial failures, what to react to. Example: `CollectionLifecycleManager` (decides what to purge and handles failures) belongs in `application/`, not `adapter/out/`.

**Event listeners** (`@EventListener`) call inward into use cases when triggered — they are driving adapters and belong in `adapter/in/`.

**GraphQL/REST input types** (e.g., `CreateCollectionInput`) live in `adapter/in/` (or in the global `api/` during the transitional period). Controllers translate them into domain calls — no commands required for grouped CRUD ports. Commands (`<Action><Feature>Command` data classes) are only introduced alongside single-method use case interfaces.

**Cross-feature shared infrastructure** (`storage/`, `QuadStore`, `VectorStore`, `BlobStore`) must be accessed via output port interfaces defined in `application/port/out/`, not injected directly. No feature may depend on another feature's concrete store or service class.

**Global orchestration services** (e.g., `PurgeService`) have no exemption from rule 6. They must call other features exclusively via `application/port/in/` interfaces.

## When to Use Grouped vs. Single-Method Ports

- **Grouped `<Feature>UseCases`**: CRUD-style operations that naturally belong together — find, create, update, delete. One interface, multiple methods. The service class implementing it is the natural test double boundary.
- **Single `<Action><Feature>UseCase`**: Complex multi-step flows, orchestration logic, anything that warrants independent testability or may be split into its own service class later. One interface, one uniquely named method.

A feature may have both. A `*Service` may implement several `*UseCase` interfaces because each method has a unique name.

**Single-method interface rules:**
- Use a descriptive, unique method name (not `invoke`) so a service can implement multiple interfaces without collision.
- Use a `<Action><Feature>Command` data class only when the method would otherwise have **six or more parameters**. For fewer parameters, pass them directly.

**When to split a method out of the grouped interface:** A method warrants its own interface if it has 3 or more of: (a) orchestrates multiple sub-services, (b) publishes Spring events or Kafka messages, (c) manages cascading deletes/updates, (d) parses or validates external input, (e) reads/writes multiple storage backends. Simple delegation, caching, and lookups stay grouped.

Example for `collection` — mutations are complex (event publishing + cascade), reads are simple:
```kotlin
// application/port/in/CollectionUseCases.kt  — reads only
interface CollectionUseCases {
    fun findById(id: String): Collection?
    fun findByName(name: String): Collection?
    fun findAll(tags: Set<String> = emptySet()): List<Collection>
    fun requireExists(id: String)
}

// application/port/in/CreateCollectionUseCase.kt
interface CreateCollectionUseCase {
    fun createCollection(name: String, description: String = "", tags: Set<String> = emptySet(), metadata: Map<String, String> = emptyMap()): Collection
}

// application/port/in/UpdateCollectionUseCase.kt
interface UpdateCollectionUseCase {
    fun updateCollection(id: String, name: String? = null, description: String? = null, tags: Set<String>? = null, metadata: Map<String, String>? = null): Collection
}

// application/port/in/DeleteCollectionUseCase.kt
interface DeleteCollectionUseCase {
    fun deleteCollection(id: String)
}
```

`CollectionService implements CollectionUseCases, CreateCollectionUseCase, UpdateCollectionUseCase, DeleteCollectionUseCase`.

**Features with mixed design (grouped reads + separate action interfaces):**
- `collection`: reads grouped; `create`, `update`, `delete` split
- `librarian`: reads + `updateState` grouped; `uploadDocument`, `createChildDocument`, `deleteDocument` split
- `ontology`: reads + `delete` + `validate` + exports grouped; `save`, `importTurtle`, `importRdfXml` split
- `contextcore`: reads + `delete` + `tag` grouped; `build`, `import` split
- `config`: reads + `delete` + `history` grouped; `save` split

**Features with grouped-only design:**
- `agent`, `query/*`, `skos`, `structured`, `provenance`, `rdfimport`, `storage`

## Migration Plan

### Step 1 — Feature branch + draft PR

Create branch `feat/layer-to-hexarch-switch`. Commit this document and the updated `.claude/rules/` to it. Open a **draft PR** `feat/layer-to-hexarch-switch` → `main`. No code changes yet — this PR is the running diff for the entire refactor.

### PR 1 → `feat/layer-to-hexarch-switch` — Input port interfaces

For every feature: introduce `application/port/in/` interfaces. Existing `*Service` classes implement them. All callers in `api/` and cross-feature references updated to use the interface. No directory restructuring yet.

### PR 2 → `feat/layer-to-hexarch-switch` — Output port interfaces

Introduce `application/port/out/`. Existing store/producer interfaces move here. Implementations already comply — package declarations and imports updated.

### PR 3 → `feat/layer-to-hexarch-switch` — Cross-feature call audit

Find and eliminate all cases where one feature calls directly into another feature's service class or domain type. Route through `application/port/in/` interfaces only.

### PR 4 → `feat/layer-to-hexarch-switch` — Structural reorganization

Move files into `domain/`, `application/`, `adapter/in/`, `adapter/out/` per feature. Can be split into one sub-PR per feature. Move feature-specific controllers from global `api/` into their feature's `adapter/in/`.

### Final step — Mark draft PR ready, merge to `main`

Once all sub-PRs are merged into the feature branch, mark the draft PR ready for review and merge.

## What Does Not Change

- Spring Modulith feature boundaries — one package per feature, no new Gradle modules
- `llm/`, `messaging/` (shared infra utilities) — remain as-is unless they grow to warrant their own hexagonal structure
- `tenant/TenantFilter` — stays as infrastructure config, not moved to `common/domain/`
