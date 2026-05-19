---
name: Backend Coding Standards
scope: backend
layer: coding
priority: high
appliesTo: spring-modulith
description: Hexagonal architecture structure, dependency rules, and naming conventions.
---

# Backend Coding Rules

## Package Structure

```
<feature>/
  domain/          # entities, value objects, exceptions — no Spring
  application/
    port/
      in/          # input port interfaces (use cases)
      out/         # output port interfaces (stores, events, external)
    <Feature>Service.kt   # implements port/in/ interface(s)
  adapter/
    in/            # driving adapters: call application/port/in/
    out/           # driven adapters: implement application/port/out/
```

Shared types used by multiple features → `com.agentwork.graphmesh.common.domain`. No Spring, no feature logic.

## Dependency Rules

1. **Inward only.** Dependencies always point toward the domain.
2. **`domain/`** → `common/domain` only. No Spring, no infrastructure.
3. **`application/`** → own `domain/` and `common/domain`. May use `llm/`, `tenant/`. Never depends on `adapter/`.
4. **`adapter/out/`** → implements `application/port/out/`. May use `domain/` models. Never calls `application/` services.
5. **`adapter/in/`** → `application/port/in/` interfaces only. Never instantiates concrete `*Service` classes.
6. **Cross-feature** → only via target feature's `application/port/in/`. No access to foreign `domain/`, `adapter/`, or service class. No exemptions (including global orchestrators like `PurgeService`).
7. **Use cases don't call use cases.** Cross-feature orchestration via Spring events or a dedicated orchestration feature.
8. **Global `api/` (transitional)** → controllers depend on `port/in/` interfaces only, never concrete services.

## Naming

- **Domain models:** `<Entity>.kt` — no suffix
- **Domain exceptions:** `<Condition>Exception.kt`
- **Input port (grouped):** `<Feature>UseCases` — CRUD-style, multiple methods
- **Input port (single):** `<Action><Feature>UseCase` — one `invoke(command): Result`; add `<Action><Feature>Command` data class alongside
- **Output ports:** `<Feature>Store` (persistence), `<Feature>EventPort` (messaging), `<Feature>Port` (external)
- **Application service:** `<Feature>Service`
- **`adapter/in/`:** `<Feature>Controller`, `<Feature>KafkaConsumer`, `<Feature>McpTools`, `<Feature>CliCommand`
- **`adapter/out/`:** `Cassandra<Feature>Store`, `Kafka<Feature>Producer`, `S3<Feature>Store`
- No `Impl`, `Manager`, or `Helper` suffixes

## Classifying Ambiguous Classes

**Test:** does it just drive infrastructure, or does it decide what to do?

- **Drives infra only → `adapter/out/`**: schema initializers, `@Configuration`, producers, registries
- **Makes decisions → `application/`**: conditional logic, failure handling, orchestration steps
- **Event listeners** (`@EventListener`) → `adapter/in/` (they call inward)
- **GraphQL/REST input types** → `adapter/in/`; controllers translate to domain calls; commands only needed for single-method ports
- **Cross-feature infra** (`storage/`, `QuadStore`, etc.) → must be accessed via `application/port/out/` interfaces

## Use Case Style

- **Grouped** (`<Feature>UseCases`): CRUD operations that belong together
- **Single-method** (`<Action><Feature>UseCase`): complex flows, or anything that may later split into its own service
- A feature may have both

## Error Handling

- Domain errors: specific exceptions or Result types, not `RuntimeException`
- API layer translates to HTTP codes/error responses
