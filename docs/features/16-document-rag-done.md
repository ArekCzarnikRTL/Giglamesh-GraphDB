# Feature 16: Document RAG — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagModels.kt`** — `DocumentRagQuery` (mit `precomputedEmbedding: FloatArray?` fuer NLP-Integration, `similarityThreshold: Float = 0.3f` als provider-vertraeglicher Default fuer Ollama + OpenAI), `DocumentRagResult` (inkl. `sessionId: UUID`), `SourceAttribution` (inkl. `score: Float`) und `RetrievedChunk`.
- **`src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt`** — `@Service`-Klasse mit synchronem `query(DocumentRagQuery)`. Phase 1: `VectorStore.search(collection, queryVector, limit=topK, scoreThreshold=similarityThreshold)`; Chunk-Text wird via `LibrarianService.getContent(chunkId)` nachgeladen, Chunks mit leerem Text werden verworfen. Phase 2: LLM-Synthese via Koog `PromptExecutor` + `resolveLlmModel(llmModelName)`, System-Prompt referenziert Quellen als `[Source N]`. `SourceAttribution` wird aus `LibrarianService.findById(documentId)` und `extractPageNumber`-Regex (`/p(\\d+)`) aufgebaut. Leere Treffer liefern `"No relevant documents found for this question."`. Jeder Call emittiert ein `ExplainabilityEvent` via `ExplainabilityEventProducer.sendDocRagEvent`.
- **`src/main/kotlin/com/agentwork/graphmesh/api/DocumentRagController.kt`** — Spring GraphQL `@QueryMapping fun documentRag(input: DocumentRagInput)`. Nutzt `DocumentRagQuery`-Defaults via `copy`, damit sich geaenderte Defaults automatisch propagieren.
- **`src/main/resources/graphql/document-rag.graphqls`** — `documentRag(input: DocumentRagInput!)`-Query, `DocumentRagResponse` mit `sessionId: ID!` und `durationMs: Int!`. Schema-Default `similarityThreshold = 0.5`.

### Tests

- **`DocumentRagServiceTest`** — 6 Unit-Tests: `extractPageNumber` (finde Seite, null bei fehlender Page, Edge Cases), Snippet-Truncation auf 200 Zeichen, `RetrievedChunk`-Werteverhalten, Defaults von `DocumentRagQuery`.

## Abweichungen vom Feature-Dokument

- **Package `com.agentwork.graphmesh.query.docrag`** (nicht `com.graphmesh.query.docrag`), Single-Module.
- **Keine separaten Interfaces**: `ChunkRetriever`, `DocumentSynthesizer` und `DefaultDocumentRagService` existieren nicht — alles in einer `DocumentRagService`-Klasse mit privaten Helpern.
- **`similarityThreshold: Float = 0.3f`** statt `Double = 0.5`. Grund dokumentiert im Code: Kompromiss zwischen Ollama (`nomic-embed-text`, Scores 0.5–0.8) und OpenAI (`text-embedding-3-small`, Scores 0.25–0.5). Der Threshold wird in den VectorStore durchgereicht, nicht erst nach dem Retrieval gefiltert.
- **Synchrone API**: `query(...)` ist nicht `suspend`; Koog-Aufruf ueber `runBlocking`. Kein Streaming.
- **Collection-Name im VectorStore**: Schema-Skizze nutzt `"doc-embeddings-$collectionId"`. Tatsaechlich wird die `collectionId` direkt als Qdrant-Collection-Name uebergeben (siehe `VectorStore.search`).
- **Payload-Feld `chunk_id` nicht verwendet**: `RetrievedChunk.chunkId` wird aus `result.id` gelesen, nicht aus `payload["chunk_id"]`. `documentId` kommt aus `payload["document_id"]`. `pageNumber` wird aus der Chunk-ID-Hierarchie (`/p<N>`) geparst, nicht aus dem Payload.
- **`SourceAttribution.score` ist `Float`**, nicht `Double`.
- **Kein Streaming/`queryStreaming`/`Flow<String>`**. GraphQL-Subscription entfaellt.
- **LLM ueber Koog** (`PromptExecutor` + `resolveLlmModel`), nicht ueber `ChatCompletionService`.
- **Explainability**: Service emittiert ein `ExplainabilityEvent` und liefert `sessionId` im Ergebnis zurueck (nicht im Spec).
- **`durationMs`** als GraphQL-`Int!` (nicht `Long!`).
- **Schema-Default abweichend vom Service-Default**: Controller nutzt den Kotlin-Default (0.3f), das GraphQL-Schema listet `similarityThreshold: Float = 0.5` — greift nur, wenn der Client keinen Wert sendet. Das ist eine bewusste Doppeldefinition, aber inkonsistent.

## Akzeptanzkriterien

- [x] GraphQL-Query `documentRag` nimmt eine natuerlichsprachige Frage und Collection-ID entgegen
- [x] Chunk-Retrieval vektorisiert die Frage und sucht Top-K aehnliche Chunks in Qdrant
- [x] Chunks unterhalb des konfigurierbaren Similarity-Thresholds werden herausgefiltert (serverseitig via `scoreThreshold`)
- [x] LLM-Synthese generiert eine Antwort ausschliesslich auf Basis der abgerufenen Chunks
- [x] Jede Quellenangabe enthaelt Dokument-ID, Titel, Seitennummer (falls vorhanden) und Score
- [x] Snippet-Feld in der Quellenangabe zeigt einen getrunkten Textauszug (max. 200 Zeichen)
- [ ] Streaming-Modus liefert Antwort-Tokens als Flow aus der Synthese-Phase — **nicht** implementiert
- [x] Top-K Parameter ist konfigurierbar (Standard: 10)
- [x] Antwort enthaelt `retrievedChunkCount` und `durationMs` als Metriken
- [x] Bei leerer Collection oder keinem Treffer wird eine aussagekraeftige Meldung zurueckgegeben

## Offene Punkte

- Streaming-Variante (`Flow<String>` + GraphQL-Subscription) fuer Token-Streaming, falls UI-Clients das brauchen.
- GraphQL-Schema-Default (`similarityThreshold = 0.5`) mit Kotlin-Default (`0.3f`) angleichen, um Verwirrung zu vermeiden.
