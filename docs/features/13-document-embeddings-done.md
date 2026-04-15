# Feature 13: Document Embeddings — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/extraction/embedding/EmbeddingService.kt`** — `@Service`, `embed(chunkId, documentId, collectionName)` laedt Chunk-Text ueber `LibrarianService.getContent`, ueberspringt leere Chunks, loest das Embedding-Modell ueber `resolveLlmModel(config.model)` (Koog) auf und ruft `LLMEmbeddingProvider.embed(text, model)` via `runBlocking`. Das Ergebnis (`List<Double>`) wird in ein `FloatArray` konvertiert, als `VectorPoint(id = chunkId, vector, payload = {chunk_id, document_id, collection})` gebaut und ueber `VectorStore.upsert(collectionName, listOf(point))` in Qdrant geschrieben.
- **`src/main/kotlin/com/agentwork/graphmesh/extraction/embedding/EmbeddingConsumer.kt`** — `@KafkaListener` auf `graphmesh.chunk.created` (groupId `graphmesh-embedding`). Liest `chunkId`, `documentId`, `collectionId` aus Avro-Record, sucht das Parent-Document ueber `librarianService.findById(documentId)` und uebergibt dessen `collectionId` (Fallback: Event-`collectionId`) an `EmbeddingService.embed`. `DocumentNotFoundException` wird als Info-Log verschluckt, andere Fehler landen im Error-Log.
- **`src/main/kotlin/com/agentwork/graphmesh/extraction/embedding/EmbeddingConfig.kt`** — `@ConfigurationProperties("graphmesh.embedding")`, nur ein Feld `model` (Default `text-embedding-3-small`). Die Feature-Dok-Parameter (`batchSize`, `batchTimeoutMs`, `requestTimeoutSeconds`) existieren nicht.

### Tests

- **`EmbeddingConfigTest`** — Default-Modell, Akzeptanz eines benutzerdefinierten Modells.

## Abweichungen vom Feature-Dokument

- **Kein Batching**: Spec fordert Buffer/Flush-Logik mit `batchSize` (Default 32), `batchTimeoutMs` (Default 5000), `EmbeddingBatchProcessor`, `EmbeddingConsumer` mit `Mutex`/Buffer-Flush. Die Implementierung verarbeitet **jeden Chunk einzeln** synchron im Kafka-Listener. `EmbeddingBatchProcessor` existiert nicht. Grund: YAGNI / einfache direkte Spring-Boot-Integration (siehe Memory `feedback_simplicity.md`).
- **Kein Lazy Collection Create via `collectionExists`/`createCollection`**: Die Collection-Initialisierung passiert im zentralen `VectorStore`-Layer (siehe Memory `project_rag_pipeline_tuning.md`: "Qdrant dim-in-name, similarityThreshold per provider"), nicht hier im Embedding-Service.
- **Collection-Name nicht `doc-embeddings-{collectionId}`**: Der Service leitet `collectionName` aus dem Parent-Document (`doc.collectionId`) ab und reicht ihn unveraendert an `VectorStore.upsert` weiter. Der tatsaechliche Qdrant-Collection-Name wird im VectorStore mit Dimension im Namen kodiert.
- **Payload-Key `collection`** statt `collection_id` (Spec) — konsistent mit der restlichen Codebasis.
- **LLM-Integration ueber Koog**: `LLMEmbeddingProvider` + `resolveLlmModel` statt des Spec-seitigen `EmbeddingService.embedBatch(texts)` (Batch-API gar nicht verwendet).
- **Paket-Prefix**: `com.agentwork.graphmesh.*` statt `com.graphmesh.*`.
- **Fehlende Test-Dateien**: Spec fordert `EmbeddingBatchProcessorTest`, `EmbeddingConsumerTest`, `LazyCollectionCreationTest`. Keiner existiert. Nur `EmbeddingConfigTest` ist vorhanden, bildet aber nur das reduzierte `EmbeddingConfig` (ein Feld) ab.

## Akzeptanzkriterien

- [x] Consumer empfaengt `chunk.created`-Events — `EmbeddingConsumer`.
- [ ] Batch-Verarbeitung bei vollem Buffer (`batchSize`) — **nicht implementiert**, single-chunk processing.
- [ ] Batch-Verarbeitung bei Timeout (`batchTimeoutMs`) — **nicht implementiert**.
- [ ] `EmbeddingService.embedBatch()` mit allen Texten — **nicht implementiert**, `embed(text, model)` wird pro Chunk aufgerufen.
- [x] Vektoren in Qdrant mit `chunk_id` als Payload — `VectorPoint.payload["chunk_id"]`.
- [ ] Qdrant-Collection lazy beim ersten Embedding mit korrekter Dimension — im `VectorStore`-Layer statt hier; Kriterium in diesem Feature nicht geprueft.
- [ ] Collection-Name `doc-embeddings-{collectionId}` — Naming-Schema liegt im VectorStore (dim-in-name), nicht `doc-embeddings-*`.
- [ ] `EmbeddingConfig`-Validierung `batchSize 1..128` — Feld existiert nicht im Config-Typ.
- [x] Leere Batches ignoriert — leere Chunks (`text.isBlank`) werden uebersprungen.
- [ ] Atomaritaet bei Embedding-Fehler — entfaellt ohne Batching.

## Offene Punkte

- Batching-Layer (`EmbeddingBatchProcessor`) mit Buffer und Timeout ist nicht implementiert. Fuer hohen Durchsatz (viele kleine Chunks) sollte das nachgezogen werden, sobald der Throughput-Bedarf real wird.
- `LLMEmbeddingProvider.embed(text, model)` wird pro Chunk synchron via `runBlocking` aufgerufen — potenzieller Hotspot bei hoher Last.
- Keine Consumer-/Integrationstests fuer den Kafka-Pfad.
