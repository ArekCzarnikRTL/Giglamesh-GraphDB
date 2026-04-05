# Feature 23: Structured Data Extractor — Design Spec

## Zusammenfassung

Kafka-basierter Extractor, der tabellarische Daten in Textchunks erkennt, das Schema inferiert und die Zeilen über StructuredDataService (Feature 22) speichert. Drei separate LLM-Calls: Detection → Schema-Inferenz → Row-Extraktion.

## Entscheidungen

| Entscheidung | Gewählt | Grund |
|---|---|---|
| MultiChunkTableMerger | Weggelassen | Schema-Matching via `matchesExisting` erledigt Multi-Chunk-Merge implizit |
| LLM-Aufrufe | 3 separate Calls | Jeder Schritt isoliert testbar, Detection-Call günstig |
| LLM-Framework | Koog PromptExecutor + runBlocking | Bestehender Codebase-Standard |
| JSON-Parsing | Jackson ObjectMapper | Bestehender Codebase-Standard |
| Package | `com.agentwork.graphmesh.extraction.structured` | Folgt Projekt-Konvention |

## Architektur

```
graphmesh.chunk.created (Kafka, Avro GenericRecord)
       │
       ▼
StructuredDataExtractorConsumer (@KafkaListener, groupId: graphmesh-structured-extractor)
       │
       ▼
StructuredDataExtractorService (Orchestrator)
       │
       ├─► TableDetector.detect(text)
       │       → DetectionResult(hasTable, confidence, tableDescription)
       │       hasTable=false oder confidence<0.5? → return (skip)
       │
       ├─► SchemaInferenceService.inferSchema(text, existingSchemas, tableDescription)
       │       → InferredSchema(schema, matchesExisting)
       │       matchesExisting != null → reuse existierendes Schema
       │       matchesExisting == null → saveSchema(neues Schema)
       │
       ├─► extractRows(text, schema, collectionId) [interner LLM-Call]
       │       → List<DataRow> via JSONL-Parsing
       │
       └─► StructuredDataService.storeBatch(rows)
              → List<StoreResult>
```

## Komponenten

### TableDetector (@Service)

LLM-Call #1: Erkennt ob ein Chunk tabellarische Daten enthält.

**Input:** Chunk-Text
**Output:** `DetectionResult(hasTable: Boolean, confidence: Double, tableDescription: String?)`

**Prompt-Strategie:** System-Prompt weist LLM an, JSON-Objekt zu liefern:
```json
{"has_table": true, "confidence": 0.85, "description": "Preisliste mit Produktnamen und Preisen"}
```

**Parsing:** Jackson ObjectMapper, `readValue<Map<String, Any>>`. Fehlerhaftes JSON → `DetectionResult(hasTable=false, confidence=0.0)`.

### SchemaInferenceService (@Service)

LLM-Call #2: Inferiert Tabellen-Schema aus dem Text.

**Input:** Chunk-Text, existierende Schemata (via `StructuredDataService.listSchemas()` + `getSchema()`), optionale tableDescription
**Output:** `InferredSchema(schema: TableSchema, matchesExisting: String?)`

**Prompt-Strategie:** System-Prompt enthält existierende Schemata als Kontext. LLM liefert JSON:
```json
{
  "schema_name": "product-prices",
  "description": "Produktpreisliste",
  "columns": [
    {"name": "product-name", "type": "STRING", "primary_key": true, "indexed": false},
    {"name": "price", "type": "FLOAT", "primary_key": false, "indexed": true}
  ],
  "matches_existing": null
}
```

**Schema-Logik:**
- `matchesExisting` gesetzt → existierendes Schema aus StructuredDataService laden
- `matchesExisting` null → neues Schema speichern via `StructuredDataService.saveSchema()`
- Spaltennamen in kebab-case, Typen mapppen auf `ColumnType` enum

### StructuredDataExtractorService (@Service)

Orchestrator + LLM-Call #3 (Row-Extraktion).

**Dependencies:**
- `PromptExecutor` (Koog)
- `TableDetector`
- `SchemaInferenceService`
- `StructuredDataService` (Feature 22)
- `LibrarianService` (Chunk-Inhalte laden)
- `@Value("${graphmesh.extraction.model:gpt-4o}")` modelName

**Methode:** `fun extract(chunkId: String, collectionId: String): StructuredExtractionResult`

**Pipeline:**
1. `librarianService.getContent(chunkId)` → Text laden
2. `tableDetector.detect(text)` → Detection
3. Wenn `!hasTable || confidence < 0.5` → return skip-Result
4. `schemaInference.inferSchema(text, existingSchemas, tableDescription)` → Schema
5. Schema bestimmen (existierendes oder neues)
6. `extractRows(text, schema, collectionId)` → LLM-Call #3, JSONL → `List<DataRow>`
7. `structuredDataService.storeBatch(rows)`
8. Return `StructuredExtractionResult`

**Row-Extraktion (LLM-Call #3):**
- Prompt gibt Schema-Spalten vor, LLM extrahiert Zeilen als JSONL
- Jede Zeile: `{"spalte1": "wert1", "spalte2": "wert2"}`
- Fehlende Werte als leere Strings
- Parsing: Zeile für Zeile mit Jackson, ungültige Zeilen überspringen
- `DataRow(collection=collectionId, schemaName=schema.name, values=map, source="urn:chunk:$chunkId")`

### StructuredDataExtractorConsumer (@Component)

Kafka-Consumer.

**Topic:** `graphmesh.chunk.created`
**GroupId:** `graphmesh-structured-extractor`
**Pattern:** Identisch zu DefinitionExtractorConsumer — `@KafkaListener`, Avro GenericRecord, Felder `chunkId` + `collectionId`.

### Models (Models.kt)

```kotlin
data class DetectionResult(
    val hasTable: Boolean,
    val confidence: Double,
    val tableDescription: String? = null
)

data class InferredSchema(
    val schema: TableSchema,
    val matchesExisting: String?
)

data class StructuredExtractionResult(
    val chunkId: String,
    val tableDetected: Boolean,
    val schemaName: String? = null,
    val rowsExtracted: Int
)
```

## Tests

| Test | Fokus |
|------|-------|
| `TableDetectorTest` | Parsing: hasTable true/false, Confidence-Werte, malformed JSON, fehlende Felder, ```json-Wrapper |
| `SchemaInferenceServiceTest` | Parsing: Spalten+Typen, matchesExisting gesetzt/null, verschiedene ColumnTypes, ungültiger JSON |
| `StructuredDataExtractorServiceTest` | End-to-End mit gemocktem PromptExecutor, TableDetector, SchemaInferenceService, StructuredDataService. Fälle: kein Table → skip, Table gefunden → extract+store, existierendes Schema reuse |
| `StructuredDataExtractorConsumerTest` | Consumer delegiert korrekt an Service, Fehlerfall wird geloggt |

**Test-Pattern:** Mockito für Dependencies, Tests der `internal` Parsing-Methoden direkt (wie bei DefinitionExtractorService).

## Abweichungen vom Feature-Doc

1. Kein `MultiChunkTableMerger` — Schema-Matching reicht
2. Package `com.agentwork.graphmesh` statt `com.graphmesh`
3. Koog PromptExecutor statt custom ChatCompletionService
4. `@KafkaListener` + Avro statt custom MessageConsumer
5. Jackson statt kotlinx.serialization
6. `runBlocking` statt suspend/coroutines
7. `StructuredDataService` direkte Methoden statt `.schemaService` Property
8. `DataRow.source` Feld genutzt für Provenance (`"urn:chunk:$chunkId"`)
