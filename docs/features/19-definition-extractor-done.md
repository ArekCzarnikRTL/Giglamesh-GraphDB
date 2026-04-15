# Feature 19: Definition Extractor — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorModels.kt`** — `DefinitionResult(entity, definition)` und `DefinitionExtractionResult(chunkId, definitionsExtracted, entitiesFound)`.
- **`src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionPromptTemplate.kt`** — `object` mit `systemPrompt()` und `userPrompt(chunkText)` (auf Englisch). JSONL-Format `{"entity": ..., "definition": ...}` pro Zeile, Regeln zur Abgrenzung gegen Beziehungen und zu kanonischen Entitaetsnamen.
- **`src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorService.kt`** — `@Service`, synchrone `extract(chunkId, collectionId: String)`:
  1. Laedt Chunk-Text ueber `LibrarianService.getContent`, entfernt Control-Zeichen via `com.agentwork.graphmesh.llm.sanitizeForLlm` (konsistent zu PDF-/Markdown-Pfad).
  2. LLM-Call ueber Koog `PromptExecutor` + `resolveLlmModel(modelName)` (`graphmesh.extraction.model`, Default `gpt-4o`).
  3. `parseJsonlDefinitions` nutzt Jackson (`jacksonObjectMapper().readValue<Map<String, String>>`), ueberspringt leere/Markdown-Fence-Zeilen und fehlerhafte JSON-Zeilen.
  4. Erzeugt pro Definition **Knowledge-Quads** `(EntityIdGenerator.generate(entity), rdfs:comment, definition)` und **Label-Quads** `(entityId, rdfs:label, entity)` (dedupliziert per `distinctBy { it.subject.toNTriples() }`) im `NamedGraph.DEFAULT`.
  5. **Provenance** kommt ueber `ProvenanceService.buildSubgraphQuads(SubgraphProvenance(...))` mit `agentLabel="DefinitionExtractor"` und `modelName` — **nicht** ueber direkt konstruierte `RdfTerm.QuotedTriple`/`EXTRACTED_FROM`-Tripel.
  6. Alle Quads werden ueber `QuadConverter.toStoredQuad` zu `StoredQuad` konvertiert und per `QuadStore.insertBatch(collectionId, ...)` gespeichert.
- **`src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorConsumer.kt`** — `@Component` mit `@KafkaListener(topics=["graphmesh.chunk.created"], groupId="graphmesh-definition-extractor")`. Liest Avro-`GenericRecord` direkt (`record.value()["chunkId"]`, `["collectionId"]`), kein typisierter `ChunkCreatedEvent`-Wrapper. `DocumentNotFoundException` (wenn Chunk vor der Verarbeitung geloescht wurde) wird auf `info`-Level toleriert, andere Fehler werden geloggt aber der Consumer commitet weiter.

### Tests

- **`DefinitionExtractorServiceTest`** — 11 Unit-Tests:
  - `parseJsonlDefinitions`: valide Definitionen, Blank-Zeilen, invalides JSON, fehlende Felder, leere Werte, Markdown-Code-Fences, leerer Response, truncation-resilientes Verhalten (unvollstaendige letzte Zeile wird uebersprungen).
  - `extract`: erzeugt Knowledge-, Label- und Provenance-Quads (verifiziert per `QuadStore`-Mock); `extract` gibt fuer Blank-Chunk `0` zurueck; Label-Quads werden nach Subject dedupliziert.

## Abweichungen vom Feature-Dokument

- **Package `com.agentwork.graphmesh.extraction.definition`** (nicht `com.graphmesh...`), Single-Module.
- **`collectionId: String`** statt `UUID`. Passt zum restlichen Code (Collection-IDs sind im Projekt durchgaengig Strings).
- **`extract(...)` statt `extractDefinitions(...)`** als Methodenname.
- **LLM ueber Koog**: `PromptExecutor` + `resolveLlmModel(modelName)`, nicht ueber `ChatCompletionService`/`ChatMessage`/`ChatRole`. Prompts werden via Koog-DSL (`prompt { system(...); user(...) }`) gebaut.
- **JSONL-Parsing mit Jackson** statt `kotlinx.serialization.Json`. Selbes Ergebnis, aber konsistent mit anderen Extraktoren.
- **Provenance ueber `ProvenanceService.buildSubgraphQuads`**: Statt einfacher `(extractedTriple, extractedFrom, urn:chunk:<id>)`-Quads im `NamedGraph.SOURCE` wird die Subgraph-Kompressions-Variante aus dem Provenance-Modul verwendet (Feature 20/21-Integration). Enthaelt `agentLabel="DefinitionExtractor"` und den verwendeten Modellnamen.
- **`sanitizeForLlm`** wird **vor** dem LLM-Call auf den Chunk-Text angewandt (Control-Char-Stripping, Commit `b4594a9`). Nicht im Spec erwaehnt.
- **`QuadConverter.toStoredQuad` + `QuadStore.insertBatch`**: Persistenz-Pfad ueber den storage-internen `StoredQuad`-Typ, statt direktem `quadStore.saveAll`.
- **`DefinitionExtractorService` ist `@Service`**, `DefinitionExtractorConsumer` ist `@Component` mit `@KafkaListener`-Annotation — kein eigener `start()`-Bootstrap und kein generisches `MessageConsumer<ChunkCreatedEvent>`-Interface. Event wird als Avro-`GenericRecord` direkt gelesen.
- **Konfigurierbares Modell**: `modelName` ist ein `@Value("\${graphmesh.extraction.model:gpt-4o}")`-Parameter, nicht hartcodiert.
- **Blank-Chunk-Early-Exit**: Service kehrt mit `DefinitionExtractionResult(chunkId, 0, emptyList())` zurueck, bevor das LLM aufgerufen wird.
- **Kein `DefinitionPromptTemplateTest`, kein `JsonlParsingTest`, kein `ProvenanceTest` als eigene Dateien**: Alle Cases sind in `DefinitionExtractorServiceTest` konsolidiert.

## Akzeptanzkriterien

- [x] Extractor empfaengt `graphmesh.chunk.created`-Events und verarbeitet den zugehoerigen Chunk-Text
- [x] LLM-Prompt extrahiert Entity-Definition-Paare im JSONL-Format
- [x] Definitionen werden als `(entity, rdfs:comment, definition_text)` Triples gespeichert
- [x] Subjects erhalten deterministische Entity-IDs via `EntityIdGenerator`
- [x] `rdfs:label`-Triples werden fuer jede extrahierte Entitaet erzeugt (dedupliziert)
- [x] Knowledge-Quads werden im Default-Graph gespeichert
- [x] Provenance-Quads werden im Source-Graph gespeichert (via `ProvenanceService.buildSubgraphQuads`, das RDF-Star / Subgraph-Kompression nutzt)
- [x] JSONL-Parsing ist truncation-resilient: unvollstaendige letzte Zeilen werden uebersprungen
- [x] Ungueltige JSONL-Zeilen (kein JSON, fehlende Felder) werden uebersprungen
- [x] Leere Definitionen oder Entity-Namen werden ignoriert
- [x] `DefinitionExtractionResult` enthaelt korrekte Zaehler und Entity-Liste

## Offene Punkte

- Keine.
