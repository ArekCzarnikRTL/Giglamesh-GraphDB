# GraphMesh Feature-Dokumentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:
> executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Alle 35 Feature-Docs in `docs/features/` erstellen, basierend auf dem Feature-Template und den Base-Docs.

**Architecture:** Jedes Feature-Doc folgt dem Template aus `docs/features/feature-template.md`. Inhalte werden aus den
zugeordneten Base-Docs in `docs/base/` abgeleitet und in das Template-Format ueberfuehrt. Features ohne Base-Docs werden
aus dem Architekturverstaendnis des Gesamtsystems abgeleitet.

**Tech Stack:** Spring Boot + Kotlin + Gradle Kotlin DSL, Cassandra, Qdrant, S3/MinIO, Kafka, Next.js

---

## Konventionen

- **Sprache:** Deutsch (wie Template)
- **Dateiname:** `XX-feature-name.md` (XX = Feature-Nummer, zweistellig)
- **Code-Beispiele:** Kotlin (Backend), TypeScript (Frontend)
- **Platform:** Spring Boot (JVM) = Ja fuer alle Backend-Features; Frontend-Features = Next.js
- **Keine Umlaute in Dateinamen**, Umlaute im Text als ae/oe/ue

## Referenz: Base-Doc Zuordnung

| Feature                        | Base-Docs                                                                                                                                 |
|--------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| 01 Kafka Messaging             | `pubsub.md`, `import-export-graceful-shutdown.md`                                                                                         |
| 02 Cassandra Storage           | `cassandra-consolidation.md`, `cassandra-performance-refactor.md`, `entity-centric-graph.md`                                              |
| 03 S3/MinIO Blob Storage       | `minio-to-s3-migration.md`                                                                                                                |
| 04 Qdrant Vector Store         | `vector-store-lifecycle.md`                                                                                                               |
| 05 LLM Provider Abstraction    | *(keine — aus architecture-principles.md ableiten)*                                                                                       |
| 06 Configuration Service       | `ARCHITEKTUR-ANALYSE.md`, `logging-strategy.md`, `schema-refactoring-proposal.md`                                                         |
| 07 RDF Graph Model             | `architecture-principles.md`, `graph-contexts.md`, `entity-centric-graph.md`                                                              |
| 08 Collection Management       | `collection-management.md`                                                                                                                |
| 09 Document Management         | `extraction-flows.md`, `large-document-loading.md`                                                                                        |
| 10 PDF Decoder                 | `extraction-flows.md`, `universal-decoder.md`                                                                                             |
| 11 Document Chunker            | `extraction-flows.md`                                                                                                                     |
| 12 Relationship Extractor      | *(keine — aus extraction-flows.md ableiten)*                                                                                              |
| 13 Document Embeddings         | `document-embeddings-chunk-id.md`, `embeddings-batch-processing.md`                                                                       |
| 14 GraphQL API                 | `graphql-query.md`, `openapi-spec.md`                                                                                                     |
| 15 Graph RAG                   | `graphrag-performance-optimization.md`, `rag-streaming-support.md`                                                                        |
| 16 Document RAG                | *(keine — aus graphrag-performance-optimization.md ableiten)*                                                                             |
| 17 MCP Tool Interface          | `mcp-tool-arguments.md`, `mcp-tool-bearer-token.md`                                                                                       |
| 18 NLP Query Service           | *(keine — aus graphql-query.md ableiten)*                                                                                                 |
| 19 Definition Extractor        | *(keine — aus extraction-flows.md ableiten)*                                                                                              |
| 20 Ontology System             | `ontology.md`                                                                                                                             |
| 21 Ontology-guided Extractor   | `ontology-extract-phase-2.md`, `ontorag.md`                                                                                               |
| 22 Structured Data Storage     | `structured-data.md`, `structured-data-2.md`, `structured-data-schemas.md`, `structured-data-descriptor.md`, `structured-diag-service.md` |
| 23 Structured Data Extractor   | `structured-data.md`, `structured-data-2.md`, `structured-data-descriptor.md`, `structured-diag-service.md`                               |
| 24 Agent-based Extractor       | `jsonl-prompt-output.md`                                                                                                                  |
| 25 Agent System                | *(keine — aus architecture-principles.md und tool-services.md ableiten)*                                                                  |
| 26 Tool Groups & Tool Services | `tool-group.md`, `tool-services.md`, `flow-class-definition.md`, `flow-configurable-parameters.md`                                        |
| 27 Streaming                   | `streaming-llm-responses.md`, `rag-streaming-support.md`, `python-api-refactor.md`, `large-document-loading.md`                           |
| 28 Multi-Tenancy               | `multi-tenant-support.md`, `neo4j-user-collection-isolation.md`                                                                           |
| 29 Extraction-Time Provenance  | `extraction-time-provenance.md`, `extraction-provenance-subgraph.md`                                                                      |
| 30 Query-Time Explainability   | `query-time-explainability.md`, `agent-explainability.md`                                                                                 |
| 31 CLI Tools                   | `explainability-cli.md`, `more-config-cli.md`                                                                                             |
| 32 Document UI                 | *(keine)*                                                                                                                                 |
| 33 Query UI                    | *(keine)*                                                                                                                                 |
| 34 Graph Explorer UI           | *(keine)*                                                                                                                                 |
| 35 Admin UI                    | *(keine)*                                                                                                                                 |

---

## Task 1: Feature-Set Overview aktualisieren

**Files:**

- Modify: `docs/features/00-feature-set-overview.md`

- [ ] **Step 1: Overview-Datei mit allen 35 Features befuellen**

Inhalt der Datei `docs/features/00-feature-set-overview.md`:

```markdown
# Feature Set: GraphMesh

Jedes Feature ist eine eigenstaendige, implementierbare Einheit.
Die Reihenfolge ist so gewaehlt, dass jedes Feature auf dem vorherigen aufbaut.
Abhaengigkeiten folgen einem DAG (Directed Acyclic Graph) — manche Features koennen parallel implementiert werden.

## Tech Stack

| Bereich | Technologie |
|---------|-------------|
| Backend | Spring Boot + Kotlin + Gradle Kotlin DSL |
| Messaging | Apache Kafka |
| Graph Storage | Apache Cassandra (Entity-Centric RDF Quads) |
| Vector Store | Qdrant |
| Blob Storage | S3 / MinIO |
| LLM | Provider-agnostisch (OpenAI, Anthropic, Ollama, etc.) |
| API | GraphQL + MCP (Model Context Protocol) |
| Frontend | Next.js (React + TypeScript) |

## Reihenfolge

### Phase 1: Foundation

| # | Feature | Datei | Abhaengig von | Aufwand |
|---|---------|-------|---------------|---------|
| 01 | Kafka Messaging Infrastructure | [01-kafka-messaging.md](01-kafka-messaging.md) | — | L |
| 02 | Cassandra Storage Layer | [02-cassandra-storage.md](02-cassandra-storage.md) | — | L |
| 03 | S3/MinIO Blob Storage | [03-s3-blob-storage.md](03-s3-blob-storage.md) | — | M |
| 04 | Qdrant Vector Store | [04-qdrant-vector-store.md](04-qdrant-vector-store.md) | — | M |
| 05 | LLM Provider Abstraction | [05-llm-provider-abstraction.md](05-llm-provider-abstraction.md) | — | L |
| 06 | Configuration Service | [06-configuration-service.md](06-configuration-service.md) | 01, 02 | M |
| 07 | RDF Graph Model | [07-rdf-graph-model.md](07-rdf-graph-model.md) | 02 | L |

### Phase 2: Minimal Pipeline

| # | Feature | Datei | Abhaengig von | Aufwand |
|---|---------|-------|---------------|---------|
| 08 | Collection Management | [08-collection-management.md](08-collection-management.md) | 02, 03, 04, 06 | M |
| 09 | Document Management (Librarian) | [09-document-management.md](09-document-management.md) | 02, 03, 08 | L |
| 10 | PDF Decoder | [10-pdf-decoder.md](10-pdf-decoder.md) | 01, 09 | M |
| 11 | Document Chunker | [11-document-chunker.md](11-document-chunker.md) | 01, 09, 10 | M |
| 12 | Relationship Extractor | [12-relationship-extractor.md](12-relationship-extractor.md) | 01, 05, 07, 11 | L |
| 13 | Document Embeddings | [13-document-embeddings.md](13-document-embeddings.md) | 01, 04, 05, 11 | M |
| 14 | GraphQL API | [14-graphql-api.md](14-graphql-api.md) | 02, 04, 07, 08, 09 | L |

### Phase 3: Query & RAG

| # | Feature | Datei | Abhaengig von | Aufwand |
|---|---------|-------|---------------|---------|
| 15 | Graph RAG | [15-graph-rag.md](15-graph-rag.md) | 05, 07, 12, 13, 14 | XL |
| 16 | Document RAG | [16-document-rag.md](16-document-rag.md) | 04, 05, 09, 13, 14 | L |
| 17 | MCP Tool Interface | [17-mcp-tool-interface.md](17-mcp-tool-interface.md) | 05, 14 | M |
| 18 | NLP Query Service | [18-nlp-query-service.md](18-nlp-query-service.md) | 05, 14, 15 | M |

### Phase 4: Extraction-Tiefe

| # | Feature | Datei | Abhaengig von | Aufwand |
|---|---------|-------|---------------|---------|
| 19 | Definition Extractor | [19-definition-extractor.md](19-definition-extractor.md) | 01, 05, 07, 11 | M |
| 20 | Ontology System | [20-ontology-system.md](20-ontology-system.md) | 02, 06, 07 | XL |
| 21 | Ontology-guided Extractor | [21-ontology-guided-extractor.md](21-ontology-guided-extractor.md) | 05, 11, 20 | L |
| 22 | Structured Data Storage | [22-structured-data-storage.md](22-structured-data-storage.md) | 02, 06 | L |
| 23 | Structured Data Extractor | [23-structured-data-extractor.md](23-structured-data-extractor.md) | 05, 11, 22 | L |
| 24 | Agent-based Extractor | [24-agent-based-extractor.md](24-agent-based-extractor.md) | 05, 11, 17 | L |

### Phase 5: Erweiterungen

| # | Feature | Datei | Abhaengig von | Aufwand |
|---|---------|-------|---------------|---------|
| 25 | Agent System (ReAct Loop) | [25-agent-system.md](25-agent-system.md) | 05, 15, 16, 17 | XL |
| 26 | Tool Groups & Tool Services | [26-tool-groups.md](26-tool-groups.md) | 25 | M |
| 27 | Streaming (LLM, RAG, Agent) | [27-streaming.md](27-streaming.md) | 05, 15, 16, 25 | L |
| 28 | Multi-Tenancy | [28-multi-tenancy.md](28-multi-tenancy.md) | 02, 08, 09 | L |
| 29 | Extraction-Time Provenance | [29-extraction-provenance.md](29-extraction-provenance.md) | 07, 10, 11, 12 | L |
| 30 | Query-Time Explainability | [30-query-explainability.md](30-query-explainability.md) | 07, 15, 16, 25 | L |
| 31 | CLI Tools | [31-cli-tools.md](31-cli-tools.md) | 08, 09, 14 | M |
| 32 | Document UI | [32-document-ui.md](32-document-ui.md) | 09, 14 | L |
| 33 | Query UI | [33-query-ui.md](33-query-ui.md) | 14, 15, 16 | L |
| 34 | Graph Explorer UI | [34-graph-explorer-ui.md](34-graph-explorer-ui.md) | 07, 14 | XL |
| 35 | Admin UI | [35-admin-ui.md](35-admin-ui.md) | 06, 08, 14 | M |

## DAG-Visualisierung

Phase 1 (Foundation):      01  02  03  04  05  (alle parallel)
                             │   │   │   │
                             └─┬─┘   │   │
                               ▼     │   │
                           06(Config) │   │
                            02 ▼      │   │
                           07(RDF)    │   │
                                      │   │
Phase 2 (Pipeline):    06,02,03,04 ──▶ 08(Collections)
                              02,03,08 ──▶ 09(Librarian)
                                 01,09 ──▶ 10(PDF)
                              01,09,10 ──▶ 11(Chunker)
                          01,05,07,11 ──▶ 12(Rel.Extractor)
                          01,04,05,11 ──▶ 13(Embeddings)
                       02,04,07,08,09 ──▶ 14(GraphQL)

Phase 3 (Query):    05,07,12,13,14 ──▶ 15(GraphRAG)
                     04,05,09,13,14 ──▶ 16(DocRAG)
                              05,14 ──▶ 17(MCP)
                           05,14,15 ──▶ 18(NLP)

Phase 4 (Extract):    01,05,07,11 ──▶ 19(Definition)
                          02,06,07 ──▶ 20(Ontology)
                           05,11,20 ──▶ 21(OntoExtract)
                              02,06 ──▶ 22(StructStore)
                           05,11,22 ──▶ 23(StructExtract)
                           05,11,17 ──▶ 24(AgentExtract)

Phase 5 (Extend):   05,15,16,17 ──▶ 25(Agent)
                              25 ──▶ 26(ToolGroups)
                       05,15,16,25 ──▶ 27(Streaming)
                          02,08,09 ──▶ 28(MultiTenant)
                       07,10,11,12 ──▶ 29(ExtrProvenance)
                       07,15,16,25 ──▶ 30(QueryExplain)
                          08,09,14 ──▶ 31(CLI)
                              09,14 ──▶ 32(DocUI)
                           14,15,16 ──▶ 33(QueryUI)
                              07,14 ──▶ 34(GraphUI)
                           06,08,14 ──▶ 35(AdminUI)
```

- [ ] **Step 2: Commit**

```bash
git add docs/features/00-feature-set-overview.md
git commit -m "docs: add complete feature-set overview with 35 features and DAG"
```

---

## Task 2: Phase 1 — Foundation Features (01-07)

**Files:**

- Create: `docs/features/01-kafka-messaging.md`
- Create: `docs/features/02-cassandra-storage.md`
- Create: `docs/features/03-s3-blob-storage.md`
- Create: `docs/features/04-qdrant-vector-store.md`
- Create: `docs/features/05-llm-provider-abstraction.md`
- Create: `docs/features/06-configuration-service.md`
- Create: `docs/features/07-rdf-graph-model.md`

**Vorgehen pro Feature:**

1. Relevante Base-Docs lesen (siehe Referenz-Tabelle oben)
2. Template aus `docs/features/feature-template.md` kopieren
3. Alle Sektionen ausfuellen: Problem, Ziel, Voraussetzungen, Architektur (mit Kotlin-Code), Betroffene Dateien,
   Akzeptanzkriterien
4. Abhaengigkeiten aus dem DAG in die Voraussetzungen-Tabelle eintragen

**Kontext fuer jedes Feature:**

**01 Kafka Messaging Infrastructure:**

- Base-Docs: `pubsub.md`, `import-export-graceful-shutdown.md`
- Kern: Kafka-Client-Abstraktion, Producer/Consumer/Subscriber/Publisher Pattern, JSON-Serialisierung,
  Request/Response-Korrelation, Topic-Naming-Konvention
- Interfaces: `MessageProducer<T>`, `MessageConsumer<T>`, `MessageSubscriber<T>`, `MessagePublisher<T>`
- Spring Boot Auto-Configuration fuer Kafka

**02 Cassandra Storage Layer:**

- Base-Docs: `cassandra-consolidation.md`, `cassandra-performance-refactor.md`, `entity-centric-graph.md`
- Kern: Cassandra-Client-Abstraktion, Keyspace-Management, Entity-Centric 2-Tabellen-Design (`quads_by_entity`,
  `quads_by_collection`), Connection-Pooling
- Interfaces: `CassandraClient`, `QuadStore`, `QuadQuery`

**03 S3/MinIO Blob Storage:**

- Base-Docs: `minio-to-s3-migration.md`
- Kern: S3-kompatible Client-Abstraktion, Bucket-Management, Put/Get/Delete/List, Content-Type-Handling
- Interface: `BlobStore`

**04 Qdrant Vector Store:**

- Base-Docs: `vector-store-lifecycle.md`
- Kern: Qdrant-Client-Abstraktion, Lazy Collection Creation (dimension-aware), Upsert/Search/Delete, Dimension-basierte
  Collection-Namensgebung
- Interface: `VectorStore`

**05 LLM Provider Abstraction:**

- Base-Docs: *(keine — aus architecture-principles.md ableiten)*
- Kern: Provider-agnostisches Interface, Chat-Completion, Embedding-Generation, Konfigurierbare
  Modelle/Temperature/Tokens
- Interfaces: `LlmProvider`, `ChatCompletion`, `EmbeddingProvider`
- Implementierungen: OpenAI, Anthropic, Ollama (als separate Module)

**06 Configuration Service:**

- Base-Docs: `ARCHITEKTUR-ANALYSE.md`, `logging-strategy.md`, `schema-refactoring-proposal.md`
- Kern: Cassandra-basierter Config-Store, Versionierung, Config-Push ueber Kafka, Handler-Registrierung
- Abhaengig von: 01 (Kafka), 02 (Cassandra)

**07 RDF Graph Model:**

- Base-Docs: `architecture-principles.md`, `graph-contexts.md`, `entity-centric-graph.md`
- Kern: RDF-Quad-Datenmodell (Subject, Predicate, Object, Graph), Namespaces, Named Graphs (`""`, `urn:graph:source`,
  `urn:graph:retrieval`), RDF-Star Quoted Triples, Blank Nodes, Literal Datatypes
- Kotlin data classes: `Quad`, `Triple`, `RdfTerm`, `Uri`, `Literal`, `BlankNode`, `QuotedTriple`
- Abhaengig von: 02 (Cassandra fuer Persistenz)

- [ ] **Step 1: Feature 01 — Kafka Messaging Infrastructure erstellen**

Lese `docs/base/pubsub.md` und `docs/base/import-export-graceful-shutdown.md`. Erstelle
`docs/features/01-kafka-messaging.md` nach Template. Fokus auf: Kafka-Client-Abstraktion, Producer/Consumer Pattern,
Topic-Konventionen, Spring Boot Integration.

- [ ] **Step 2: Feature 02 — Cassandra Storage Layer erstellen**

Lese `docs/base/cassandra-consolidation.md`, `docs/base/cassandra-performance-refactor.md`,
`docs/base/entity-centric-graph.md`. Erstelle `docs/features/02-cassandra-storage.md`. Fokus auf: Entity-Centric
2-Tabellen-Design, Quad-Storage, Connection-Pooling, Keyspace-Management.

- [ ] **Step 3: Feature 03 — S3/MinIO Blob Storage erstellen**

Lese `docs/base/minio-to-s3-migration.md`. Erstelle `docs/features/03-s3-blob-storage.md`. Fokus auf: S3-kompatible
Abstraktion, Bucket-Management, Content-Handling.

- [ ] **Step 4: Feature 04 — Qdrant Vector Store erstellen**

Lese `docs/base/vector-store-lifecycle.md`. Erstelle `docs/features/04-qdrant-vector-store.md`. Fokus auf: Lazy
Collection Creation, Dimension-Awareness, Search-API.

- [ ] **Step 5: Feature 05 — LLM Provider Abstraction erstellen**

Lese `docs/base/architecture-principles.md` fuer Kontext. Erstelle `docs/features/05-llm-provider-abstraction.md`. Fokus
auf: Provider-agnostisches Interface, Chat/Embedding-APIs, Konfiguration.

- [ ] **Step 6: Feature 06 — Configuration Service erstellen**

Lese `docs/base/ARCHITEKTUR-ANALYSE.md`, `docs/base/logging-strategy.md`, `docs/base/schema-refactoring-proposal.md`.
Erstelle `docs/features/06-configuration-service.md`. Fokus auf: Cassandra-Config-Store, Kafka-Config-Push,
Handler-Pattern.

- [ ] **Step 7: Feature 07 — RDF Graph Model erstellen**

Lese `docs/base/architecture-principles.md`, `docs/base/graph-contexts.md`, `docs/base/entity-centric-graph.md`.
Erstelle `docs/features/07-rdf-graph-model.md`. Fokus auf: Quad-Datenmodell, Named Graphs, RDF-Star, Kotlin Data
Classes.

- [ ] **Step 8: Commit Phase 1**

```bash
git add docs/features/01-kafka-messaging.md docs/features/02-cassandra-storage.md docs/features/03-s3-blob-storage.md docs/features/04-qdrant-vector-store.md docs/features/05-llm-provider-abstraction.md docs/features/06-configuration-service.md docs/features/07-rdf-graph-model.md
git commit -m "docs: add Phase 1 foundation feature docs (01-07)"
```

---

## Task 3: Phase 2 — Minimal Pipeline Features (08-14)

**Files:**

- Create: `docs/features/08-collection-management.md`
- Create: `docs/features/09-document-management.md`
- Create: `docs/features/10-pdf-decoder.md`
- Create: `docs/features/11-document-chunker.md`
- Create: `docs/features/12-relationship-extractor.md`
- Create: `docs/features/13-document-embeddings.md`
- Create: `docs/features/14-graphql-api.md`

**Kontext fuer jedes Feature:**

**08 Collection Management:**

- Base-Docs: `collection-management.md`
- Kern: Explizite Collection-Erstellung, Synchronisation ueber alle Stores (Cassandra, Qdrant, S3), Cascade-Deletion,
  Tags/Metadata
- Abhaengig von: 02, 03, 04, 06

**09 Document Management (Librarian):**

- Base-Docs: `extraction-flows.md`, `large-document-loading.md`
- Kern: Dokument-Metadata in Cassandra, Content in S3, Parent-Child-Hierarchie (Source → Pages → Chunks),
  CRUD-Operationen
- Abhaengig von: 02, 03, 08

**10 PDF Decoder:**

- Base-Docs: `extraction-flows.md`, `universal-decoder.md`
- Kern: PDF → Text-Extraktion pro Seite, Kafka-Consumer/Producer, Child-Dokumente im Librarian anlegen
- Abhaengig von: 01, 09

**11 Document Chunker:**

- Base-Docs: `extraction-flows.md`
- Kern: Text → Chunks mit konfigurierbarer Groesse/Overlap, Kafka-Consumer/Producer, Chunk-Dokumente im Librarian
  anlegen
- Abhaengig von: 01, 09, 10

**12 Relationship Extractor:**

- Base-Docs: *(aus extraction-flows.md ableiten)*
- Kern: LLM-basierte Triple-Extraktion aus Chunks, Subject-Predicate-Object, Prompt-Template, Ergebnis als RDF-Quads in
  Cassandra speichern
- Abhaengig von: 01, 05, 07, 11

**13 Document Embeddings:**

- Base-Docs: `document-embeddings-chunk-id.md`, `embeddings-batch-processing.md`
- Kern: Chunk-Embeddings generieren (via LLM Provider), Batch-Verarbeitung, Chunk-ID-Tracking, In Qdrant speichern
- Abhaengig von: 01, 04, 05, 11

**14 GraphQL API:**

- Base-Docs: `graphql-query.md`, `openapi-spec.md`
- Kern: GraphQL-Schema fuer Dokumente, Collections, Triples, Embeddings, Spring Boot GraphQL Integration,
  Query/Mutation-Definitionen
- Abhaengig von: 02, 04, 07, 08, 09

- [ ] **Step 1: Feature 08 — Collection Management erstellen**

Lese `docs/base/collection-management.md`. Erstelle `docs/features/08-collection-management.md`.

- [ ] **Step 2: Feature 09 — Document Management erstellen**

Lese `docs/base/extraction-flows.md`, `docs/base/large-document-loading.md`. Erstelle
`docs/features/09-document-management.md`.

- [ ] **Step 3: Feature 10 — PDF Decoder erstellen**

Lese `docs/base/extraction-flows.md`, `docs/base/universal-decoder.md`. Erstelle `docs/features/10-pdf-decoder.md`.

- [ ] **Step 4: Feature 11 — Document Chunker erstellen**

Lese `docs/base/extraction-flows.md`. Erstelle `docs/features/11-document-chunker.md`.

- [ ] **Step 5: Feature 12 — Relationship Extractor erstellen**

Lese `docs/base/extraction-flows.md` fuer Kontext. Erstelle `docs/features/12-relationship-extractor.md`.

- [ ] **Step 6: Feature 13 — Document Embeddings erstellen**

Lese `docs/base/document-embeddings-chunk-id.md`, `docs/base/embeddings-batch-processing.md`. Erstelle
`docs/features/13-document-embeddings.md`.

- [ ] **Step 7: Feature 14 — GraphQL API erstellen**

Lese `docs/base/graphql-query.md`, `docs/base/openapi-spec.md`. Erstelle `docs/features/14-graphql-api.md`.

- [ ] **Step 8: Commit Phase 2**

```bash
git add docs/features/08-collection-management.md docs/features/09-document-management.md docs/features/10-pdf-decoder.md docs/features/11-document-chunker.md docs/features/12-relationship-extractor.md docs/features/13-document-embeddings.md docs/features/14-graphql-api.md
git commit -m "docs: add Phase 2 minimal pipeline feature docs (08-14)"
```

---

## Task 4: Phase 3 — Query & RAG Features (15-18)

**Files:**

- Create: `docs/features/15-graph-rag.md`
- Create: `docs/features/16-document-rag.md`
- Create: `docs/features/17-mcp-tool-interface.md`
- Create: `docs/features/18-nlp-query-service.md`

**Kontext fuer jedes Feature:**

**15 Graph RAG:**

- Base-Docs: `graphrag-performance-optimization.md`, `rag-streaming-support.md`
- Kern: 3-Stufen-Pipeline (Retrieval → Edge Selection → Synthesis), LLM-basierte Edge-Auswahl mit Reasoning,
  Subgraph-Retrieval ueber Embeddings + Keywords
- Abhaengig von: 05, 07, 12, 13, 14

**16 Document RAG:**

- Base-Docs: *(aus graphrag-performance-optimization.md ableiten)*
- Kern: Semantische Suche ueber Chunk-Embeddings, Top-K Retrieval, LLM-Synthese aus Chunks, Source-Attribution
- Abhaengig von: 04, 05, 09, 13, 14

**17 MCP Tool Interface:**

- Base-Docs: `mcp-tool-arguments.md`, `mcp-tool-bearer-token.md`
- Kern: Model Context Protocol Server, Tool-Definitionen fuer Knowledge-Query/Document-Query/Structured-Query,
  Bearer-Token-Auth, Argument-Handling
- Abhaengig von: 05, 14

**18 NLP Query Service:**

- Base-Docs: *(aus graphql-query.md ableiten)*
- Kern: Natural Language → strukturierte Query-Konvertierung, LLM-basiertes Query-Understanding, Intent-Detection,
  Query-Routing (Graph/Document/Structured)
- Abhaengig von: 05, 14, 15

- [ ] **Step 1: Feature 15 — Graph RAG erstellen**

Lese `docs/base/graphrag-performance-optimization.md`, `docs/base/rag-streaming-support.md`. Erstelle
`docs/features/15-graph-rag.md`.

- [ ] **Step 2: Feature 16 — Document RAG erstellen**

Erstelle `docs/features/16-document-rag.md`. Ableitung aus GraphRAG-Architektur, angepasst fuer Chunk-basierte
Retrieval.

- [ ] **Step 3: Feature 17 — MCP Tool Interface erstellen**

Lese `docs/base/mcp-tool-arguments.md`, `docs/base/mcp-tool-bearer-token.md`. Erstelle
`docs/features/17-mcp-tool-interface.md`.

- [ ] **Step 4: Feature 18 — NLP Query Service erstellen**

Erstelle `docs/features/18-nlp-query-service.md`. Ableitung aus GraphQL- und GraphRAG-Architektur.

- [ ] **Step 5: Commit Phase 3**

```bash
git add docs/features/15-graph-rag.md docs/features/16-document-rag.md docs/features/17-mcp-tool-interface.md docs/features/18-nlp-query-service.md
git commit -m "docs: add Phase 3 query and RAG feature docs (15-18)"
```

---

## Task 5: Phase 4 — Extraction-Tiefe Features (19-24)

**Files:**

- Create: `docs/features/19-definition-extractor.md`
- Create: `docs/features/20-ontology-system.md`
- Create: `docs/features/21-ontology-guided-extractor.md`
- Create: `docs/features/22-structured-data-storage.md`
- Create: `docs/features/23-structured-data-extractor.md`
- Create: `docs/features/24-agent-based-extractor.md`

**Kontext fuer jedes Feature:**

**19 Definition Extractor:**

- Base-Docs: *(aus extraction-flows.md ableiten)*
- Kern: LLM-basierte Entity-Definition-Extraktion, "Was ist X?"-Prompts, Ergebnis als Literal-Triples (entity,
  `rdfs:comment`, definition)
- Abhaengig von: 01, 05, 07, 11

**20 Ontology System:**

- Base-Docs: `ontology.md`
- Kern: OWL-inspiriertes Ontologie-Modell, Klassen mit Vererbung, Object/Datatype Properties, Domain/Range,
  Multi-Language Labels, Validierung, Import/Export (Turtle, RDF/XML)
- Abhaengig von: 02, 06, 07

**21 Ontology-guided Extractor:**

- Base-Docs: `ontology-extract-phase-2.md`, `ontorag.md`
- Kern: Ontologie-Schema als Extraction-Prompt-Kontext, Klassen + Properties leiten LLM-Extraktion, Validierung gegen
  Ontologie
- Abhaengig von: 05, 11, 20

**22 Structured Data Storage:**

- Base-Docs: `structured-data.md`, `structured-data-2.md`, `structured-data-schemas.md`,
  `structured-data-descriptor.md`, `structured-diag-service.md`
- Kern: Cassandra-basierter Row-Store, Schema-Management, Dynamische Tabellen, CRUD fuer Rows
- Abhaengig von: 02, 06

**23 Structured Data Extractor:**

- Base-Docs: `structured-data.md`, `structured-data-2.md`, `structured-data-descriptor.md`, `structured-diag-service.md`
- Kern: LLM-basierte Tabellen-Extraktion aus Chunks, Schema-Erkennung, Row-Generierung, Speicherung in Structured Data
  Storage
- Abhaengig von: 05, 11, 22

**24 Agent-based Extractor:**

- Base-Docs: `jsonl-prompt-output.md`
- Kern: ReAct-Agent der iterativ Wissen aus Chunks extrahiert, Tool-Nutzung (z.B. Nachfragen, Kontext-Erweiterung),
  Flexible Extraction-Strategien
- Abhaengig von: 05, 11, 17

- [ ] **Step 1: Feature 19 — Definition Extractor erstellen**

Erstelle `docs/features/19-definition-extractor.md`.

- [ ] **Step 2: Feature 20 — Ontology System erstellen**

Lese `docs/base/ontology.md`. Erstelle `docs/features/20-ontology-system.md`.

- [ ] **Step 3: Feature 21 — Ontology-guided Extractor erstellen**

Lese `docs/base/ontology-extract-phase-2.md`, `docs/base/ontorag.md`. Erstelle
`docs/features/21-ontology-guided-extractor.md`.

- [ ] **Step 4: Feature 22 — Structured Data Storage erstellen**

Lese `docs/base/structured-data.md`, `docs/base/structured-data-2.md`, `docs/base/structured-data-schemas.md`,
`docs/base/structured-data-descriptor.md`, `docs/base/structured-diag-service.md`. Erstelle
`docs/features/22-structured-data-storage.md`.

- [ ] **Step 5: Feature 23 — Structured Data Extractor erstellen**

Lese `docs/base/structured-data.md`, `docs/base/structured-data-2.md`. Erstelle
`docs/features/23-structured-data-extractor.md`.

- [ ] **Step 6: Feature 24 — Agent-based Extractor erstellen**

Lese `docs/base/jsonl-prompt-output.md`. Erstelle `docs/features/24-agent-based-extractor.md`.

- [ ] **Step 7: Commit Phase 4**

```bash
git add docs/features/19-definition-extractor.md docs/features/20-ontology-system.md docs/features/21-ontology-guided-extractor.md docs/features/22-structured-data-storage.md docs/features/23-structured-data-extractor.md docs/features/24-agent-based-extractor.md
git commit -m "docs: add Phase 4 extraction depth feature docs (19-24)"
```

---

## Task 6: Phase 5 — Backend-Erweiterungen (25-31)

**Files:**

- Create: `docs/features/25-agent-system.md`
- Create: `docs/features/26-tool-groups.md`
- Create: `docs/features/27-streaming.md`
- Create: `docs/features/28-multi-tenancy.md`
- Create: `docs/features/29-extraction-provenance.md`
- Create: `docs/features/30-query-explainability.md`
- Create: `docs/features/31-cli-tools.md`

**Kontext fuer jedes Feature:**

**25 Agent System (ReAct Loop):**

- Base-Docs: *(aus architecture-principles.md und tool-services.md ableiten)*
- Kern: Think → Act → Observe Loop, Tool-Auswahl, Kontext-Management, Iteration-Limit, Abbruch-Bedingungen
- Abhaengig von: 05, 15, 16, 17

**26 Tool Groups & Tool Services:**

- Base-Docs: `tool-group.md`, `tool-services.md`, `flow-class-definition.md`, `flow-configurable-parameters.md`
- Kern: Tool-Definitionen, Tool-Gruppen (basic, advanced, read-only, write), Dynamische Tool-Services,
  Flow-Konfiguration
- Abhaengig von: 25

**27 Streaming:**

- Base-Docs: `streaming-llm-responses.md`, `rag-streaming-support.md`, `python-api-refactor.md`,
  `large-document-loading.md`
- Kern: Token-by-Token LLM-Streaming, RAG-Streaming, Agent-Streaming, WebSocket/SSE, Backward-Compatible
- Abhaengig von: 05, 15, 16, 25

**28 Multi-Tenancy:**

- Base-Docs: `multi-tenant-support.md`, `neo4j-user-collection-isolation.md`
- Kern: User-scoped Collections, Keyspace-Isolation, Collection-basierte Zugriffskontrolle
- Abhaengig von: 02, 08, 09

**29 Extraction-Time Provenance:**

- Base-Docs: `extraction-time-provenance.md`, `extraction-provenance-subgraph.md`
- Kern: PROV-O Modell, Subgraph-Provenance (1 Record pro Chunk-Extraktion), RDF-Star Quoted Triples, Named Graph
  `urn:graph:source`
- Abhaengig von: 07, 10, 11, 12

**30 Query-Time Explainability:**

- Base-Docs: `query-time-explainability.md`, `agent-explainability.md`
- Kern: Question → Exploration → Focus → Synthesis Kette, Agent-Iteration-Tracking, Named Graph `urn:graph:retrieval`
- Abhaengig von: 07, 15, 16, 25

**31 CLI Tools:**

- Base-Docs: `explainability-cli.md`, `more-config-cli.md`
- Kern: Kommandozeilen-Tools fuer Collection-Management, Document-Upload, Query-Ausfuehrung, Config-Verwaltung,
  Explainability-Inspektion
- Abhaengig von: 08, 09, 14

- [ ] **Step 1: Feature 25 — Agent System erstellen**

Erstelle `docs/features/25-agent-system.md`.

- [ ] **Step 2: Feature 26 — Tool Groups & Tool Services erstellen**

Lese `docs/base/tool-group.md`, `docs/base/tool-services.md`, `docs/base/flow-class-definition.md`,
`docs/base/flow-configurable-parameters.md`. Erstelle `docs/features/26-tool-groups.md`.

- [ ] **Step 3: Feature 27 — Streaming erstellen**

Lese `docs/base/streaming-llm-responses.md`, `docs/base/rag-streaming-support.md`. Erstelle
`docs/features/27-streaming.md`.

- [ ] **Step 4: Feature 28 — Multi-Tenancy erstellen**

Lese `docs/base/multi-tenant-support.md`, `docs/base/neo4j-user-collection-isolation.md`. Erstelle
`docs/features/28-multi-tenancy.md`.

- [ ] **Step 5: Feature 29 — Extraction-Time Provenance erstellen**

Lese `docs/base/extraction-time-provenance.md`, `docs/base/extraction-provenance-subgraph.md`. Erstelle
`docs/features/29-extraction-provenance.md`.

- [ ] **Step 6: Feature 30 — Query-Time Explainability erstellen**

Lese `docs/base/query-time-explainability.md`, `docs/base/agent-explainability.md`. Erstelle
`docs/features/30-query-explainability.md`.

- [ ] **Step 7: Feature 31 — CLI Tools erstellen**

Lese `docs/base/explainability-cli.md`, `docs/base/more-config-cli.md`. Erstelle `docs/features/31-cli-tools.md`.

- [ ] **Step 8: Commit Phase 5 Backend**

```bash
git add docs/features/25-agent-system.md docs/features/26-tool-groups.md docs/features/27-streaming.md docs/features/28-multi-tenancy.md docs/features/29-extraction-provenance.md docs/features/30-query-explainability.md docs/features/31-cli-tools.md
git commit -m "docs: add Phase 5 backend extension feature docs (25-31)"
```

---

## Task 7: Phase 5 — UI Features (32-35)

**Files:**

- Create: `docs/features/32-document-ui.md`
- Create: `docs/features/33-query-ui.md`
- Create: `docs/features/34-graph-explorer-ui.md`
- Create: `docs/features/35-admin-ui.md`

**Kontext fuer jedes Feature:**

**32 Document UI:**

- Kern: Next.js-basierte Dokumentenverwaltung, Upload (Drag&Drop), Dokument-Liste mit Filter/Suche, Dokument-Detail (
  Metadaten, Chunks, extrahierte Triples), Collection-Auswahl
- Abhaengig von: 09, 14

**33 Query UI:**

- Kern: Chat-Interface fuer Natural Language Queries, GraphRAG/DocRAG-Toggle, Streaming-Anzeige, Source-Attribution (
  klickbare Quellen), Query-History
- Abhaengig von: 14, 15, 16

**34 Graph Explorer UI:**

- Kern: Interaktive Graph-Visualisierung (Force-Directed Layout), Node/Edge-Details, Filter nach Named Graph,
  Subgraph-Exploration, Zoom/Pan, Entity-Suche
- Abhaengig von: 07, 14

**35 Admin UI:**

- Kern: Config-Dashboard, Collection-Management-UI, Flow-Konfiguration, Ontologie-Editor (optional),
  System-Status/Health, User-Management (Multi-Tenancy)
- Abhaengig von: 06, 08, 14

- [ ] **Step 1: Feature 32 — Document UI erstellen**

Erstelle `docs/features/32-document-ui.md`. Fokus auf Next.js-Komponenten, GraphQL-Queries, Upload-Flow.

- [ ] **Step 2: Feature 33 — Query UI erstellen**

Erstelle `docs/features/33-query-ui.md`. Fokus auf Chat-Interface, Streaming-Integration, Source-Attribution.

- [ ] **Step 3: Feature 34 — Graph Explorer UI erstellen**

Erstelle `docs/features/34-graph-explorer-ui.md`. Fokus auf Graph-Visualisierung, Force-Directed Layout, Interaktion.

- [ ] **Step 4: Feature 35 — Admin UI erstellen**

Erstelle `docs/features/35-admin-ui.md`. Fokus auf Config-Dashboard, Collection-Management, System-Health.

- [ ] **Step 5: Commit Phase 5 UI**

```bash
git add docs/features/32-document-ui.md docs/features/33-query-ui.md docs/features/34-graph-explorer-ui.md docs/features/35-admin-ui.md
git commit -m "docs: add Phase 5 UI feature docs (32-35)"
```

---

## Task 8: Abschluss und Validierung

**Files:**

- Verify: alle Dateien in `docs/features/`

- [ ] **Step 1: Vollstaendigkeits-Check**

Pruefe ob alle 36 Dateien existieren (00-overview + 35 features):

```bash
ls docs/features/*.md | wc -l
```

Erwartet: 37 (inkl. feature-template.md)

- [ ] **Step 2: Link-Check**

Pruefe ob alle Links in `00-feature-set-overview.md` auf existierende Dateien zeigen:

```bash
grep -oP '\(\K[^)]+\.md' docs/features/00-feature-set-overview.md | while read f; do test -f "docs/features/$f" || echo "MISSING: $f"; done
```

Erwartet: keine Ausgabe

- [ ] **Step 3: Template-Conformance-Check**

Pruefe ob jedes Feature-Doc alle Pflicht-Sektionen enthaelt:

```bash
for f in docs/features/[0-3][0-9]-*.md; do
  echo "=== $f ==="
  for section in "## Problem" "## Ziel" "## Voraussetzungen" "## Architektur" "## Betroffene Dateien" "## Akzeptanzkriterien"; do
    grep -q "$section" "$f" || echo "  MISSING: $section"
  done
done
```

Erwartet: keine MISSING-Zeilen

- [ ] **Step 4: Final Commit**

```bash
git add docs/features/ docs/superpowers/
git commit -m "docs: complete GraphMesh feature documentation (35 features, 5 phases)"
```
