# Feature Set: GraphMesh

Jedes Feature ist eine eigenstaendige, implementierbare Einheit.
Die Reihenfolge ist so gewaehlt, dass jedes Feature auf dem vorherigen aufbaut.
Abhaengigkeiten folgen einem DAG (Directed Acyclic Graph) — manche Features koennen parallel implementiert werden.

## Tech Stack

| Bereich       | Technologie                                           |
|---------------|-------------------------------------------------------|
| Backend       | Spring Boot + Kotlin + Gradle Kotlin DSL              |
| Messaging     | Apache Kafka                                          |
| Graph Storage | Apache Cassandra (Entity-Centric RDF Quads)           |
| Vector Store  | Qdrant                                                |
| Blob Storage  | S3 / MinIO                                            |
| LLM           | Provider-agnostisch (OpenAI, Anthropic, Ollama, etc.) |
| API           | GraphQL + MCP (Model Context Protocol)                |
| Frontend      | Next.js (React + TypeScript)                          |

## Reihenfolge

### Phase 1: Foundation

| #  | Feature                        | Datei                                                            | Abhaengig von | Aufwand |
|----|--------------------------------|------------------------------------------------------------------|---------------|---------|
| 01 | Kafka Messaging Infrastructure | [01-kafka-messaging.md](01-kafka-messaging.md)                   | —             | L       |
| 02 | Cassandra Storage Layer        | [02-cassandra-storage.md](02-cassandra-storage.md)               | —             | L       |
| 03 | S3/MinIO Blob Storage          | [03-s3-blob-storage.md](03-s3-blob-storage.md)                   | —             | M       |
| 04 | Qdrant Vector Store            | [04-qdrant-vector-store.md](04-qdrant-vector-store.md)           | —             | M       |
| 05 | LLM Provider Abstraction       | [05-llm-provider-abstraction.md](05-llm-provider-abstraction.md) | —             | L       |
| 06 | Configuration Service          | [06-configuration-service.md](06-configuration-service.md)       | 01, 02        | M       |
| 07 | RDF Graph Model                | [07-rdf-graph-model.md](07-rdf-graph-model.md)                   | 02            | L       |

### Phase 2: Minimal Pipeline

| #  | Feature                         | Datei                                                        | Abhaengig von      | Aufwand |
|----|---------------------------------|--------------------------------------------------------------|--------------------|---------|
| 08 | Collection Management           | [08-collection-management.md](08-collection-management.md)   | 02, 03, 04, 06     | M       |
| 09 | Document Management (Librarian) | [09-document-management.md](09-document-management.md)       | 02, 03, 08         | L       |
| 10 | PDF Decoder                     | [10-pdf-decoder.md](10-pdf-decoder.md)                       | 01, 09             | M       |
| 11 | Document Chunker                | [11-document-chunker.md](11-document-chunker.md)             | 01, 09, 10         | M       |
| 12 | Relationship Extractor          | [12-relationship-extractor.md](12-relationship-extractor.md) | 01, 05, 07, 11     | L       |
| 13 | Document Embeddings             | [13-document-embeddings.md](13-document-embeddings.md)       | 01, 04, 05, 11     | M       |
| 14 | GraphQL API                     | [14-graphql-api.md](14-graphql-api.md)                       | 02, 04, 07, 08, 09 | L       |

### Phase 3: Query & RAG

| #  | Feature            | Datei                                                | Abhaengig von      | Aufwand |
|----|--------------------|------------------------------------------------------|--------------------|---------|
| 15 | Graph RAG          | [15-graph-rag.md](15-graph-rag.md)                   | 05, 07, 12, 13, 14 | XL      |
| 16 | Document RAG       | [16-document-rag.md](16-document-rag.md)             | 04, 05, 09, 13, 14 | L       |
| 17 | MCP Tool Interface | [17-mcp-tool-interface.md](17-mcp-tool-interface.md) | 05, 14             | M       |
| 18 | NLP Query Service  | [18-nlp-query-service.md](18-nlp-query-service.md)   | 05, 14, 15         | M       |

### Phase 4: Extraction-Tiefe

| #  | Feature                   | Datei                                                              | Abhaengig von  | Aufwand |
|----|---------------------------|--------------------------------------------------------------------|----------------|---------|
| 19 | Definition Extractor      | [19-definition-extractor.md](19-definition-extractor.md)           | 01, 05, 07, 11 | M       |
| 20 | Ontology System           | [20-ontology-system.md](20-ontology-system.md)                     | 02, 06, 07     | XL      |
| 21 | Ontology-guided Extractor | [21-ontology-guided-extractor.md](21-ontology-guided-extractor.md) | 05, 11, 20     | L       |
| 22 | Structured Data Storage   | [22-structured-data-storage.md](22-structured-data-storage.md)     | 02, 06         | L       |
| 23 | Structured Data Extractor | [23-structured-data-extractor.md](23-structured-data-extractor.md) | 05, 11, 22     | L       |
| 24 | Agent-based Extractor     | [24-agent-based-extractor.md](24-agent-based-extractor.md)         | 05, 11, 17     | L       |

### Phase 5: Erweiterungen

| #  | Feature                     | Datei                                                      | Abhaengig von  | Aufwand |
|----|-----------------------------|------------------------------------------------------------|----------------|---------|
| 25 | Agent System (ReAct Loop)   | [25-agent-system.md](25-agent-system.md)                   | 05, 15, 16, 17 | XL      |
| 26 | Tool Groups & Tool Services | [26-tool-groups.md](26-tool-groups.md)                     | 25             | M       |
| 27 | Streaming (LLM, RAG, Agent) | [27-streaming.md](27-streaming.md)                         | 05, 15, 16, 25 | L       |
| 28 | Multi-Tenancy               | [28-multi-tenancy.md](28-multi-tenancy.md)                 | 02, 08, 09     | L       |
| 29 | Extraction-Time Provenance  | [29-extraction-provenance.md](29-extraction-provenance.md) | 07, 10, 11, 12 | L       |
| 30 | Query-Time Explainability   | [30-query-explainability.md](30-query-explainability.md)   | 07, 15, 16, 25 | L       |
| 31 | CLI Tools                   | [31-cli-tools.md](31-cli-tools.md)                         | 08, 09, 14     | M       |
| 32 | Document UI                 | [32-document-ui.md](32-document-ui.md)                     | 09, 14         | L       |
| 33 | Query UI                    | [33-query-ui.md](33-query-ui.md)                           | 14, 15, 16     | L       |
| 34 | Graph Explorer UI           | [34-graph-explorer-ui.md](34-graph-explorer-ui.md)         | 07, 14         | XL      |
| 35 | Admin UI                    | [35-admin-ui.md](35-admin-ui.md)                           | 06, 08, 14     | M       |

## DAG-Visualisierung

```
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
