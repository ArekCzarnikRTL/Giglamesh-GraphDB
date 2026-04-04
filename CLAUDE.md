# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

GraphMesh is an Enterprise Knowledge Graph Platform built with Spring Boot + Kotlin. It extracts structured knowledge (RDF/SPO triples) from documents using LLMs, stores them in a graph, and enables semantic search and RAG queries.

## Build Commands

```bash
./gradlew build              # Full build (compile + test)
./gradlew test               # Run all tests
./gradlew test --tests "com.agentwork.graphmesh.SomeTest"  # Run single test class
./gradlew bootRun            # Run the application
./gradlew generateJava       # Generate GraphQL client code from schemas in src/main/resources/graphql-client/
```

No linter is configured yet.

## Tech Stack

- **Language**: Kotlin on Java 21
- **Build**: Gradle 9.4.1 with Kotlin DSL
- **Framework**: Spring Boot 4.0.5, Spring Modulith 2.0.5, Spring AI 2.0.0-M4
- **Storage**: Apache Cassandra (entity-centric RDF quads), Qdrant (vector embeddings), S3/MinIO (blobs)
- **Messaging**: Apache Kafka (event-driven pub/sub)
- **API**: GraphQL (Netflix DGS codegen) + MCP (Model Context Protocol)
- **LLM**: Provider-agnostic via Spring AI (OpenAI, Anthropic, Ollama, etc.)

## Architecture

### Core Principles (from `docs/base/architecture-principles.md`)

1. **SPO/RDF Graph Model** — Subject-Predicate-Object triples as core knowledge representation
2. **LLM-Native** — Graph structure optimized for LLM interaction
3. **Embedding-Based Navigation** — NLP queries map to graph nodes via vector embeddings (`NLP Query → Embeddings → Graph Nodes`)
4. **Deterministic Entity Resolution** — Parallel knowledge extraction with deterministic identifiers
5. **Event-Driven** — Kafka pub/sub for loose coupling between processing stages

### Modular Structure (Spring Modulith)

The project uses Spring Modulith for internal module boundaries. Each feature is a self-contained module under `com.agentwork.graphmesh`. Features are implemented in phases following a dependency DAG documented in `docs/features/00-feature-set-overview.md`.

### Key Pipeline Flow

Documents → PDF Decode → Chunk → Extract (LLM) → RDF Triples → Cassandra + Qdrant embeddings → GraphQL/MCP query → RAG responses

## Documentation

- `docs/features/` — 35 feature specifications organized in 5 implementation phases with dependency ordering
- `docs/base/` — Architectural analysis, infrastructure decisions, and design documents (in German and English)

## Kotlin Conventions

- Compiler flags: `-Xjsr305=strict` (strict null-safety for Spring annotations), `-Xannotation-default-target=param-property`
- Tests use JUnit 5 via `kotlin-test-junit5`
