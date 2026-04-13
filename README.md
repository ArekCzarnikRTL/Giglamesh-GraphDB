# GraphMesh

KI-Knowledge-Graph-Plattform zur automatischen Wissensextraktion aus Dokumenten. GraphMesh analysiert Dokumente mit LLMs, extrahiert strukturiertes Wissen als Wissensgraph (SPO-Tripel) und ermoeglicht semantische Suche sowie RAG-Abfragen ueber den gesamten Wissensbestand.

## Features

- **Dokumentenverwaltung** -- PDF-Upload, automatisches Chunking und Verarbeitung
- **Wissensextraktion** -- LLM-basierte Extraktion von Entitaeten, Relationen und Themen
- **Semantische Suche** -- NLP-Abfragen, Graph-RAG und Document-RAG mit Quellenangaben
- **Wissensmodellierung** -- Ontologien (OWL/Turtle), SKOS-Taxonomien, RDF-Datenimport
- **Graph-Visualisierung** -- Interaktiver Graph-Explorer mit Sigma.js
- **KI-Agenten** -- ReAct-Agenten mit Tool-Binding und Echtzeit-Streaming
- **Context Cores** -- Versionierte Wissens-Bundles (ZIP) fuer Export, Import und Promotion
- **Multi-Tenancy** -- Mandantenfaehige Datentrennung
- **MCP-Interface** -- Model Context Protocol fuer KI-Tool-Integration

## Tech Stack

| Komponente | Technologie |
|---|---|
| Backend | Kotlin, Spring Boot 4, Spring Modulith |
| Frontend | Next.js 14, Apollo Client v4, Tailwind, shadcn/base-ui |
| API | GraphQL (Spring GraphQL), MCP, CLI |
| LLM | Spring AI (OpenAI, Anthropic, Ollama) |
| Graphspeicher | Apache Cassandra 5 (entity-centric RDF Quads) |
| Vektordatenbank | Qdrant |
| Objektspeicher | S3 / MinIO |
| Messaging | Apache Kafka + Schema Registry (Avro) |
| Build | Gradle 9 (Kotlin DSL), Java 21 |

## Quickstart

### Voraussetzungen

- Docker & Docker Compose
- JDK 21
- Node.js 20+ und pnpm
- LLM-API-Key (OpenAI oder Anthropic)

### 1. Infrastruktur starten

```bash
docker compose up -d
```

Startet Cassandra, Qdrant, MinIO, Kafka und Schema Registry.

### 2. LLM konfigurieren

```bash
export OPENAI_API_KEY=sk-...
```

### 3. Anwendung starten

```bash
# Backend + Frontend zusammen:
./start.sh

# Oder einzeln:
./gradlew bootRun          # Backend auf :8083
pnpm -C frontend dev       # Frontend auf :3002
```

### 4. Oeffnen

- **Frontend:** http://localhost:3002
- **GraphiQL:** http://localhost:8083/graphiql
- **MinIO Console:** http://localhost:9001 (minioadmin/minioadmin)

## Projektstruktur

```
src/main/kotlin/com/agentwork/graphmesh/
  api/                  # GraphQL-Controller
  contextcore/          # Context Cores (Export/Import)
  extraction/           # LLM-Wissensextraktion
  nlp/                  # NLP-Query, Intent-Erkennung
  ontology/             # Ontologie-System (OWL, SKOS)
  rdf/                  # RDF-Modell (Quad, StoredQuad)
  storage/              # Cassandra, Qdrant, S3-Adapter
  streaming/            # Agent-Streaming, GraphQL-Subscriptions

frontend/src/
  app/                  # Next.js Pages (cores, documents, graph, query, admin)
  components/           # React-Komponenten
  graphql/              # Apollo Queries & Mutations

docs/
  features/             # Feature-Spezifikationen (46 Features, 5 Phasen)
  product/              # Produktdokumentation (Deutsch)
```

## Build & Test

```bash
./gradlew build                    # Full Build (compile + test)
./gradlew test                     # Nur Tests
./gradlew test --tests "*.SomeTest"  # Einzelner Test
./gradlew generateJava             # GraphQL-Codegen
```

## Dokumentation

Die ausfuehrliche Produktdokumentation liegt unter [`docs/product/`](docs/product/index.md) und ist auch als [HTML-Seite](docs/product/index.html) verfuegbar.

## Lizenz

Proprietary -- All rights reserved.
