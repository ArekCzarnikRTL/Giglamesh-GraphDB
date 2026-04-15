# Feature 11: Document Chunker — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/extraction/chunker/ChunkerService.kt`** — `@Service`-Klasse, `chunkDocument(documentId, collectionId: String)` laedt Blob via `LibrarianService.getContent`, splittet ueber `splitIntoChunks`, legt pro Chunk ein Child-Document (`DocumentType.CHUNK`, `text/plain`) an und publiziert pro Chunk ein `ChunkCreatedEvent`. Sliding-Window mit `step = chunkSize - overlapSize`, vermeidet Rest-Chunks kleiner als overlap.
- **`src/main/kotlin/com/agentwork/graphmesh/extraction/chunker/ChunkerConsumer.kt`** — `@KafkaListener` auf `graphmesh.page.extracted` (groupId `graphmesh-chunker`), liest Avro-`GenericRecord`, ruft `ChunkerService.chunkDocument(documentId, collectionId)`. Fehler werden geloggt, Record gilt danach als verarbeitet.
- **`src/main/kotlin/com/agentwork/graphmesh/extraction/chunker/ChunkCreatedProducer.kt`** — Avro-basierter Kafka-Producer fuer Topic `graphmesh.chunk.created`, Schema aus `/avro/chunk-created.avsc`, setzt CloudEvents-Header (`CloudEventHeaders.build`) mit Source `graphmesh/chunker`, Typ `graphmesh.chunk.created.v1`, Subject = `chunkId`.
- **`src/main/kotlin/com/agentwork/graphmesh/extraction/chunker/ChunkerModels.kt`** — `ChunkConfig` (`@ConfigurationProperties("graphmesh.chunker")`, Defaults 2000/200, init-Validierung), `ChunkResult`, `ChunkCreatedEvent` (mit `collectionId: String`, nicht `UUID`).

### Tests

- **`ChunkConfigTest`** — Defaults, Validierung fuer `chunkSize <= 0`, `overlapSize < 0`, `overlapSize >= chunkSize`.
- **`ChunkAlgorithmTest`** — Short Text (1 Chunk), Text = chunkSize, Overlap-Korrektheit (Ende = Anfang naechstes), zero overlap, empty text, whitespace, sequential `chunkIndex`, vollstaendige Textabdeckung.

## Abweichungen vom Feature-Dokument

- **Paket-Prefix**: Spec nennt `com.graphmesh.*`, tatsaechlich liegt der Code unter `com.agentwork.graphmesh.*`. Zielverzeichnis ist das Monolith-Projekt, nicht das im Spec genannte Submodul `extraction/`.
- **`collectionId` als `String`**: Der Spec verwendet `UUID`, die Implementierung reicht `collectionId` durchgaengig als `String` durch (entspricht dem Typ, der aus dem Avro-Event kommt und vom `LibrarianService` erwartet wird).
- **Kafka-Integration direkt ueber Spring Kafka**: Spec abstrahiert `MessageConsumer`/`MessageProducer`. Die Implementierung nutzt `@KafkaListener` und `KafkaTemplate<String, GenericRecord>` mit Avro-Schema direkt (keine Messaging-Abstraktion, in Linie mit YAGNI-Prinzip).
- **Keine separaten Dateien fuer `ChunkResult` / `ChunkConfig` / `ChunkCreatedEvent`**: Alle drei Datenklassen liegen zusammen in `ChunkerModels.kt`.
- **Child-Document-ID nicht `{parentId}/c{index}`**: Die ID wird vom `LibrarianService.createChildDocument` vergeben, das Schema steuert der Librarian, nicht der Chunker. Kriterium zum ID-Schema wird deshalb als nicht-implementiert (bzw. nicht zutreffend) markiert.
- **Kein `ChunkerConsumerTest` / `OverlapTest` als eigenstaendige Dateien**: Overlap-Verhalten wird innerhalb `ChunkAlgorithmTest` abgedeckt; Consumer-Test fehlt.
- **Whitespace-only Text erzeugt Chunk**: Der Algorithmus-Test dokumentiert, dass `"   "` einen Chunk ergibt. Spec fordert fuer "leere Texte (nur Whitespace) keine Chunks" — der Service-Layer filtert `text.isBlank()` weg, der reine Algorithmus nicht.

## Akzeptanzkriterien

- [x] Chunker empfaengt `page.extracted`-Events und zerlegt den zugehoerigen Text — `ChunkerConsumer`.
- [x] Chunk-Groesse und Overlap sind konfigurierbar (Defaults 2000/200) — `ChunkConfig` + `graphmesh.chunker`-Properties.
- [x] Chunks ueberlappen korrekt — `ChunkAlgorithmTest.overlap preserves context between chunks`.
- [ ] ID-Schema `{parentId}/c{index}` — wird vom `LibrarianService` bestimmt, nicht vom Chunker; nicht geprueft.
- [x] Pro Chunk wird ein `chunk.created`-Event publiziert — `ChunkCreatedProducer.send` pro Chunk.
- [x] `charOffset` und `charLength` im Event sind korrekt relativ zum Quelltext — `ChunkerService` setzt beide aus `ChunkResult`.
- [x] Leere Texte erzeugen keine Chunks — Service filtert `text.isBlank()`.
- [x] Sehr kurze Texte erzeugen genau einen Chunk — `ChunkAlgorithmTest.short text produces single chunk`.
- [x] `ChunkConfig`-Validierung — `ChunkConfigTest`.
- [x] Kein Rest-Chunk kleiner als overlapSize — explizite Break-Bedingung in `splitIntoChunks`.

## Offene Punkte

- Keine integrativen Tests fuer `ChunkerConsumer` (Avro-Event -> Service-Call); ggf. als Integrationstest nachziehen.
