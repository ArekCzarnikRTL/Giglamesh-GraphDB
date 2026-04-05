# Feature 23: Structured Data Extractor — Done

## Zusammenfassung

Kafka-basierter Extractor implementiert, der tabellarische Daten in Textchunks erkennt, das Schema via LLM inferiert und die extrahierten Zeilen über StructuredDataService (Feature 22) speichert.

### Implementierte Komponenten

| Datei | Zweck |
|-------|-------|
| `extraction/structured/Models.kt` | DetectionResult, InferredSchema, StructuredExtractionResult |
| `extraction/structured/TableDetector.kt` | @Service — LLM-Call #1: Tabellenerkennung mit Konfidenz |
| `extraction/structured/SchemaInferenceService.kt` | @Service — LLM-Call #2: Schema-Inferenz, existierende Schemata matchen |
| `extraction/structured/StructuredDataExtractorService.kt` | @Service — Orchestrator + LLM-Call #3: Zeilen-Extraktion als JSONL |
| `extraction/structured/StructuredDataExtractorConsumer.kt` | @Component — @KafkaListener auf graphmesh.chunk.created |

### Tests

| Test | Anzahl |
|------|--------|
| TableDetectorTest | 7 Tests (JSON-Parsing, Edge Cases) |
| SchemaInferenceServiceTest | 5 Tests (Schema-Parsing, alle ColumnTypes) |
| StructuredDataExtractorServiceTest | 10 Tests (Row-Parsing + E2E mit Mocks) |
| StructuredDataExtractorConsumerTest | 2 Tests (Delegation, Error Handling) |

## Abweichungen vom Feature-Dokument

1. **Kein MultiChunkTableMerger** — Schema-Matching via `matchesExisting` erledigt Multi-Chunk-Merge implizit. Tabellen über mehrere Chunks werden automatisch zusammengeführt, da die SchemaInferenceService existierende Schemata erkennt.
2. **Koog PromptExecutor** statt custom ChatCompletionService — folgt dem bestehenden Codebase-Pattern
3. **Jackson ObjectMapper** statt kotlinx.serialization — konsistent mit allen anderen Extractors
4. **@KafkaListener + Avro** statt custom MessageConsumer — Projekt-Standard
5. **runBlocking** statt suspend/coroutines — konsistent mit DefinitionExtractorService
6. **DataRow.source** genutzt für Provenance (`"urn:chunk:$chunkId"`)

## Offene Punkte

- Keine bekannten technischen Schulden
- Feature ist vollständig und ready for integration testing mit echtem Kafka + LLM
