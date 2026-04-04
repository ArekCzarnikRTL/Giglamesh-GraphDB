# GraphMesh Feature-Set Design Spec

## Kontext

GraphMesh ist eine Enterprise Knowledge Graph Plattform, die von Grund auf neu gebaut wird. Dieses Dokument definiert
die Feature-Dokumentationsstruktur und Implementierungsreihenfolge fuer den MVP und darueber hinaus.

## Entscheidungen

| Bereich               | Entscheidung                                                    |
|-----------------------|-----------------------------------------------------------------|
| Plattform             | Spring Boot + Kotlin + Gradle Kotlin DSL                        |
| Storage               | Cassandra + Qdrant + S3/MinIO + Kafka                           |
| LLM                   | Provider-agnostisch (OpenAI, Anthropic, Ollama, etc.)           |
| API                   | GraphQL + MCP (Model Context Protocol)                          |
| Frontend              | Next.js (React + TypeScript)                                    |
| Feature-Granularitaet | Fein (jeder Extraktor-Typ eigenes Feature)                      |
| Abhaengigkeitsmodell  | DAG (Directed Acyclic Graph)                                    |
| Feature-Detail        | Voll ausgefuellt (inkl. Dateipfade, Code-Beispiele, Interfaces) |

## Ansatz: Vertical Slice

Features sind so geordnet, dass frueh ein End-to-End-Ergebnis entsteht (Dokument rein, Wissen raus, Frage beantworten).
Jede Phase liefert sichtbaren Mehrwert.

## Feature-DAG (35 Features, 5 Phasen)

### Phase 1: Foundation (01-07)

| #  | Feature                        | Datei                            | Abhaengig von | Aufwand |
|----|--------------------------------|----------------------------------|---------------|---------|
| 01 | Kafka Messaging Infrastructure | `01-kafka-messaging.md`          | —             | L       |
| 02 | Cassandra Storage Layer        | `02-cassandra-storage.md`        | —             | L       |
| 03 | S3/MinIO Blob Storage          | `03-s3-blob-storage.md`          | —             | M       |
| 04 | Qdrant Vector Store            | `04-qdrant-vector-store.md`      | —             | M       |
| 05 | LLM Provider Abstraction       | `05-llm-provider-abstraction.md` | —             | L       |
| 06 | Configuration Service          | `06-configuration-service.md`    | 01, 02        | M       |
| 07 | RDF Graph Model                | `07-rdf-graph-model.md`          | 02            | L       |

**Meilenstein:** Alle Infrastruktur-Bausteine stehen. Kafka, Storage-Backends, LLM-Schicht und das RDF-Datenmodell sind
einsatzbereit.

### Phase 2: Minimal Pipeline (08-14)

| #  | Feature                         | Datei                          | Abhaengig von      | Aufwand |
|----|---------------------------------|--------------------------------|--------------------|---------|
| 08 | Collection Management           | `08-collection-management.md`  | 02, 03, 04, 06     | M       |
| 09 | Document Management (Librarian) | `09-document-management.md`    | 02, 03, 08         | L       |
| 10 | PDF Decoder                     | `10-pdf-decoder.md`            | 01, 09             | M       |
| 11 | Document Chunker                | `11-document-chunker.md`       | 01, 09, 10         | M       |
| 12 | Relationship Extractor          | `12-relationship-extractor.md` | 01, 05, 07, 11     | L       |
| 13 | Document Embeddings             | `13-document-embeddings.md`    | 01, 04, 05, 11     | M       |
| 14 | GraphQL API                     | `14-graphql-api.md`            | 02, 04, 07, 08, 09 | L       |

**Meilenstein:** Erste lauffaehige Pipeline — PDF hochladen, Chunks erzeugen, Triples + Embeddings extrahieren, per
GraphQL abfragen.

### Phase 3: Query & RAG (15-18)

| #  | Feature            | Datei                      | Abhaengig von      | Aufwand |
|----|--------------------|----------------------------|--------------------|---------|
| 15 | Graph RAG          | `15-graph-rag.md`          | 05, 07, 12, 13, 14 | XL      |
| 16 | Document RAG       | `16-document-rag.md`       | 04, 05, 09, 13, 14 | L       |
| 17 | MCP Tool Interface | `17-mcp-tool-interface.md` | 05, 14             | M       |
| 18 | NLP Query Service  | `18-nlp-query-service.md`  | 05, 14, 15         | M       |

**Meilenstein:** Nutzer koennen Fragen stellen und Antworten aus dem Knowledge Graph erhalten. MCP macht Tools fuer LLMs
verfuegbar.

### Phase 4: Extraction-Tiefe (19-24)

| #  | Feature                   | Datei                             | Abhaengig von  | Aufwand |
|----|---------------------------|-----------------------------------|----------------|---------|
| 19 | Definition Extractor      | `19-definition-extractor.md`      | 01, 05, 07, 11 | M       |
| 20 | Ontology System           | `20-ontology-system.md`           | 02, 06, 07     | XL      |
| 21 | Ontology-guided Extractor | `21-ontology-guided-extractor.md` | 05, 11, 20     | L       |
| 22 | Structured Data Storage   | `22-structured-data-storage.md`   | 02, 06         | L       |
| 23 | Structured Data Extractor | `23-structured-data-extractor.md` | 05, 11, 22     | L       |
| 24 | Agent-based Extractor     | `24-agent-based-extractor.md`     | 05, 11, 17     | L       |

**Meilenstein:** Alle Extraktor-Typen verfuegbar. Ontologie-gesteuerte und Structured-Data-Extraktion erweitern die
Wissensqualitaet.

### Phase 5: Erweiterungen (25-35)

| #  | Feature                     | Datei                         | Abhaengig von  | Aufwand |
|----|-----------------------------|-------------------------------|----------------|---------|
| 25 | Agent System (ReAct Loop)   | `25-agent-system.md`          | 05, 15, 16, 17 | XL      |
| 26 | Tool Groups & Tool Services | `26-tool-groups.md`           | 25             | M       |
| 27 | Streaming (LLM, RAG, Agent) | `27-streaming.md`             | 05, 15, 16, 25 | L       |
| 28 | Multi-Tenancy               | `28-multi-tenancy.md`         | 02, 08, 09     | L       |
| 29 | Extraction-Time Provenance  | `29-extraction-provenance.md` | 07, 10, 11, 12 | L       |
| 30 | Query-Time Explainability   | `30-query-explainability.md`  | 07, 15, 16, 25 | L       |
| 31 | CLI Tools                   | `31-cli-tools.md`             | 08, 09, 14     | M       |
| 32 | Document UI                 | `32-document-ui.md`           | 09, 14         | L       |
| 33 | Query UI                    | `33-query-ui.md`              | 14, 15, 16     | L       |
| 34 | Graph Explorer UI           | `34-graph-explorer-ui.md`     | 07, 14         | XL      |
| 35 | Admin UI                    | `35-admin-ui.md`              | 06, 08, 14     | M       |

**Meilenstein:** Vollstaendige Plattform mit Agent-System, Streaming, Multi-Tenancy, Explainability, CLI und Web-UI.

## Feature-Template

Jedes Feature nutzt das Template aus `docs/features/feature-template.md`:

- **Problem** — Was fehlt, warum reicht der aktuelle Stand nicht
- **Ziel** — Nummerierte Kern-Deliverables
- **Voraussetzungen** — Abhaengigkeiten mit Status und Blocker
- **Architektur** — Subsections mit Kotlin-Code-Beispielen, Interfaces, Datenmodelle
- **Betroffene Dateien** — Backend, Frontend, Tests mit konkreten Pfaden
- **Platform-Einschraenkungen** — Spring Boot (JVM) fokussiert
- **Akzeptanzkriterien** — Konkret und testbar

## DAG-Visualisierung

```
Phase 1 (Foundation)
  01(Kafka)──┐     02(Cassandra)──┬──┐  03(S3)  04(Qdrant)  05(LLM)
             │          │  │      │  │     │        │           │
             │          │  ▼      │  │     │        │           │
             │          │ 07(RDF) │  │     │        │           │
             │          │  │      │  │     │        │           │
             └──────────┤  │      ▼  │     │        │           │
                        ▼  │  06(Config) │  │        │           │
                        │  │      │      │  │        │           │
Phase 2 (Pipeline)      │  │      ▼      │  │        │           │
                        ├──┼──08(Collections)◄───────┘           │
                        │  │      │                              │
                        ▼  ▼      ▼                              │
                     09(Librarian)◄──────────────────────────────┤
                        │                                        │
                        ▼                                        │
                     10(PDF Decoder)                             │
                        │                                        │
                        ▼                                        │
                     11(Chunker)─────────────────────────────────┤
                        │        │                               │
                        ▼        ▼                               ▼
                     12(Rel.)  13(Emb.)                    19(Def.)
                        │        │
                        ▼        ▼
Phase 3 (Query)      14(GraphQL)
                      │  │  │  │
                      ▼  ▼  ▼  ▼
                 15(GRAG) 16(DRAG) 17(MCP) 18(NLP)
                      │     │        │
Phase 4 (Extract)    │     │        ▼
                 20(Onto)  │    24(Agent-Ex)
                    │      │
                    ▼      │    22(Struct.Store)
                 21(OntoEx)│        │
                           │        ▼
                           │    23(Struct.Ex)
Phase 5 (Extend)           │
                      25(Agent)──► 26(ToolGroups)
                        │
                   27(Streaming)
                   28(Multi-Tenancy)
                   29(Extr.Provenance)
                   30(Query.Explain)
                   31(CLI)
                   32(Doc UI)  33(Query UI)  34(Graph UI)  35(Admin UI)
```

## Naechste Schritte

1. Feature-Overview (`docs/features/00-feature-set-overview.md`) aktualisieren
2. Alle 35 Feature-Docs nach Template erstellen
3. Implementation Plan pro Phase erstellen
