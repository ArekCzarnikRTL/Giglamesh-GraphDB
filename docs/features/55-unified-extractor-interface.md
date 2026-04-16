# Feature 55: Unified Extractor Interface

## Problem

Die 5 Extraction-Services (`AgentExtractorService`, `RelationshipExtractorService`, `DefinitionExtractorService`, `TopicExtractorService`, `StructuredDataExtractorService`) und der `OntologyGuidedExtractorService` folgen alle dem gleichen Muster: Kafka `chunk.created` empfangen, Chunk-Content laden, LLM aufrufen, Output parsen, Quads/Rows persistieren, Provenance generieren. Aber jeder definiert eigene Result-Typen (`AgentExtractionResult`, `ExtractionResult`, `StructuredExtractionResult`), eigenes Consumer-Boilerplate (~35 LOC pro Consumer) und eigene Output-Formate (JSONL, Pipe-Delimited, Custom). Das erzeugt ~220 LOC identisches Kafka-Consumer-Boilerplate und ~120 LOC duplizierte chunk-loading/provenance/persistence-Logik.

## Ziel

Vereinheitlichung der Extractor-Schnittstelle durch ein gemeinsames Interface und eine Basisklasse, die wiederkehrende Querschnittslogik absorbiert. Anwendungsseitige Semantik (LLM-Prompts, Output-Formate, Persistenzziele) bleibt pro Extractor individuell.

1. **`ChunkExtractor`-Interface** — einheitliche Schnittstelle mit `extract(chunkId, collectionId): ExtractionSummary` fuer alle 6 Extractors.
2. **`QuadProducingExtractor`-Basisklasse** — absorbiert chunk-loading, LLM-Sanitization, Provenance-Generierung und QuadStore-Persistenz fuer die drei Quad-produzierenden Extractors (Relationship, Definition, Topic).
3. **Direkte `ChunkExtractor`-Implementierung** — Agent, OntologyGuided und Structured implementieren das Interface direkt, da sie unterschiedliche LLM-Patterns und Persistenz-Ziele haben.
4. **`AbstractExtractionConsumer`-Basisklasse** — Thin Consumers mit separaten `groupId`s (5 LOC pro Consumer statt 35).
5. **EmbeddingConsumer bleibt aussen** — andere Signatur (braucht `documentId` statt `chunkId`).

## Voraussetzungen

| Abhaengigkeit                                           | Status           | Blocker? |
|---------------------------------------------------------|------------------|----------|
| Feature 01 (Kafka)                                      | Implementiert    | Nein     |
| Feature 05 (LLM)                                       | Implementiert    | Nein     |
| Feature 07 (RDF Graph Model)                            | Implementiert    | Nein     |
| Feature 11 (Chunker)                                    | Implementiert    | Nein     |
| Feature 12 (Relationship Extractor)                     | Implementiert    | Nein     |
| Feature 19 (Definition Extractor)                       | Implementiert    | Nein     |
| Feature 21 (Topic Extractor)                            | Implementiert    | Nein     |
| Feature 23 (Agent Extractor)                            | Implementiert    | Nein     |
| Feature 24 (Structured Data Extractor)                  | Implementiert    | Nein     |
| Feature 29 (Provenance)                                 | Implementiert    | Nein     |

Keine Infra-Aenderung in Kafka / docker-compose.

## Architektur

### Ist-Zustand

```kotlin
// RelationshipExtractorConsumer.kt — typisches Beispiel, ~35 LOC pro Consumer
@Component
class RelationshipExtractorConsumer(
    private val extractorService: RelationshipExtractorService,
    private val librarianService: LibrarianService,
    private val quadStore: QuadStoreService,
    private val provenanceService: ProvenanceService,
) {
    @KafkaListener(topics = ["chunk.created"], groupId = "relationship-extractor")
    fun onChunkCreated(record: ConsumerRecord<String, GenericRecord>) {
        val chunkId = record.value()["chunkId"].toString()
        val collectionId = record.value()["collectionId"].toString()
        val chunk = librarianService.getChunk(chunkId)
        val content = chunk.content
        val sanitized = sanitizeLlmOutput(content)
        val result = extractorService.extract(sanitized, chunkId, collectionId)
        result.quads.forEach { quad ->
            quadStore.insert(collectionId, quad)
        }
        provenanceService.record(chunkId, "RelationshipExtractor", result.quads.size)
    }
}
```

Dieses Muster wiederholt sich nahezu identisch in `DefinitionExtractorConsumer`, `TopicExtractorConsumer`, `AgentExtractorConsumer`, `OntologyGuidedExtractorConsumer` und `StructuredDataExtractorConsumer` — mit jeweils eigenen Result-Typen und minimalen Variationen.

### Soll-Zustand

```kotlin
// RelationshipExtractorConsumer.kt — Thin Consumer, ~5 LOC
@Component
class RelationshipExtractorConsumer(
    extractor: RelationshipExtractorService,
) : AbstractExtractionConsumer(extractor, groupId = "relationship-extractor")
```

Die gesamte Kafka-Consumer-Logik (Record-Parsing, Chunk-Loading, Fehlerbehandlung, Logging) lebt in `AbstractExtractionConsumer`. Die fachliche Extraktion delegiert an das `ChunkExtractor`-Interface.

### Subsection 1: ChunkExtractor-Interface und ExtractionSummary

```kotlin
// extraction/ChunkExtractor.kt
interface ChunkExtractor {
    val extractorName: String
    fun extract(chunkId: String, collectionId: String): ExtractionSummary
}

data class ExtractionSummary(
    val chunkId: String,
    val extractorName: String,
    val itemsExtracted: Int,
    val details: Map<String, String> = emptyMap()
)
```

`ExtractionSummary` ersetzt die heterogenen Result-Typen als einheitliche Rueckgabe fuer Consumer-Logging und Monitoring. Die internen Datenstrukturen (Quads, Rows) werden innerhalb der `extract()`-Methode persistiert und tauchen nicht im Return-Typ auf.

### Subsection 2: QuadProducingExtractor-Basisklasse

```kotlin
// extraction/QuadProducingExtractor.kt
abstract class QuadProducingExtractor(
    private val librarianService: LibrarianService,
    private val quadStore: QuadStoreService,
    private val provenanceService: ProvenanceService,
    protected val modelName: String,
) : ChunkExtractor {

    override fun extract(chunkId: String, collectionId: String): ExtractionSummary {
        val chunk = librarianService.getChunk(chunkId)
        val sanitized = sanitizeLlmOutput(chunk.content)
        val quads = extractQuads(sanitized, chunkId, collectionId)
        quadStore.insertBatch(collectionId, quads)
        provenanceService.record(chunkId, extractorName, quads.size)
        return ExtractionSummary(
            chunkId = chunkId,
            extractorName = extractorName,
            itemsExtracted = quads.size,
        )
    }

    protected abstract fun extractQuads(
        chunkText: String,
        chunkId: String,
        collectionId: String,
    ): List<StoredQuad>
}
```

`RelationshipExtractorService`, `DefinitionExtractorService` und `TopicExtractorService` erben von `QuadProducingExtractor` und implementieren nur `extractQuads()` — die LLM-spezifische Prompt-Logik und Parsing bleibt in der jeweiligen Service-Klasse.

### Subsection 3: AbstractExtractionConsumer

```kotlin
// extraction/AbstractExtractionConsumer.kt
abstract class AbstractExtractionConsumer(
    private val extractor: ChunkExtractor,
    private val groupId: String,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @KafkaListener(topics = ["chunk.created"], groupId = "#{__listener.groupId}")
    fun onChunkCreated(record: ConsumerRecord<String, GenericRecord>) {
        val chunkId = record.value()["chunkId"].toString()
        val collectionId = record.value()["collectionId"].toString()
        log.info("[{}] Processing chunk {}", extractor.extractorName, chunkId)
        val summary = extractor.extract(chunkId, collectionId)
        log.info("[{}] Extracted {} items from chunk {}",
            summary.extractorName, summary.itemsExtracted, summary.chunkId)
    }
}
```

Jeder Thin Consumer erbt von `AbstractExtractionConsumer`, uebergibt seinen `ChunkExtractor` und seine `groupId`. Fehlerbehandlung (try/catch, Dead-Letter-Logging) wird einmalig in der Basisklasse implementiert.

### Subsection 4: Direkte ChunkExtractor-Implementierungen

Agent, OntologyGuided und Structured implementieren `ChunkExtractor` direkt:

```kotlin
// Agent: nutzt Koog-Agent mit Tool-Binding, persistiert Quads ueber eigene Logik
class AgentExtractorService(...) : ChunkExtractor {
    override val extractorName = "AgentExtractor"
    override fun extract(chunkId: String, collectionId: String): ExtractionSummary { ... }
}

// OntologyGuided: nutzt Ontology-Prompt-Builder, validiert gegen SKOS-Taxonomie
class OntologyGuidedExtractorService(...) : ChunkExtractor {
    override val extractorName = "OntologyGuidedExtractor"
    override fun extract(chunkId: String, collectionId: String): ExtractionSummary { ... }
}

// Structured: persistiert in CassandraRowStore statt QuadStore
class StructuredDataExtractorService(...) : ChunkExtractor {
    override val extractorName = "StructuredDataExtractor"
    override fun extract(chunkId: String, collectionId: String): ExtractionSummary { ... }
}
```

Diese drei benoetigen keinen `QuadProducingExtractor`, da ihre Persistenz-Ziele (Agent: eigene Quad-Logik mit Tool-Calls, OntologyGuided: Validierung + selektive Persistenz, Structured: Row-Store) sich vom Standard-Quad-Pattern unterscheiden.

## Betroffene Dateien

### Backend

| Datei                                                                                                          | Aenderung                                                                                         |
|----------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/extraction/ChunkExtractor.kt`                                        | NEU — Interface `ChunkExtractor` + `ExtractionSummary` Data Class                                 |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/QuadProducingExtractor.kt`                                 | NEU — Abstrakte Basisklasse mit chunk-loading, Sanitization, Persistenz, Provenance               |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/AbstractExtractionConsumer.kt`                             | NEU — Kafka-Consumer-Basisklasse mit Record-Parsing, Logging, Fehlerbehandlung                    |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/relationship/RelationshipExtractorService.kt`              | Von `QuadProducingExtractor` erben, `extractQuads()` implementieren                               |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorService.kt`                  | Von `QuadProducingExtractor` erben, `extractQuads()` implementieren                               |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorService.kt`                            | Von `QuadProducingExtractor` erben, `extractQuads()` implementieren                               |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/agent/AgentExtractorService.kt`                            | `ChunkExtractor` direkt implementieren                                                            |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/ontology/OntologyGuidedExtractorService.kt`                | `ChunkExtractor` direkt implementieren                                                            |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorService.kt`              | `ChunkExtractor` direkt implementieren                                                            |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/relationship/RelationshipExtractorConsumer.kt`             | Auf Thin Consumer reduzieren (erbt `AbstractExtractionConsumer`)                                  |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorConsumer.kt`                 | Auf Thin Consumer reduzieren (erbt `AbstractExtractionConsumer`)                                  |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorConsumer.kt`                           | Auf Thin Consumer reduzieren (erbt `AbstractExtractionConsumer`)                                  |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/agent/AgentExtractorConsumer.kt`                           | Auf Thin Consumer reduzieren (erbt `AbstractExtractionConsumer`)                                  |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/ontology/OntologyGuidedExtractorConsumer.kt`               | Auf Thin Consumer reduzieren (erbt `AbstractExtractionConsumer`)                                  |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorConsumer.kt`             | Auf Thin Consumer reduzieren (erbt `AbstractExtractionConsumer`)                                  |

### Frontend

Keine Aenderungen.

### Tests

| Datei                                                                                                          | Aenderung                                                                                         |
|----------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/extraction/ChunkExtractorContractTest.kt`                             | NEU — Contract-Tests fuer `ChunkExtractor`-Interface (positive + negative Faelle)                  |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/QuadProducingExtractorTest.kt`                             | NEU — Unit-Test mit gemocktem LibrarianService/QuadStore: chunk-loading, Persistenz, Provenance    |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/AbstractExtractionConsumerTest.kt`                         | NEU — Unit-Test: Record-Parsing, Delegation an ChunkExtractor, Fehlerbehandlung                   |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/relationship/RelationshipExtractorServiceTest.kt`          | An neue Vererbung anpassen, `extractQuads()`-Vertrag testen                                       |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorServiceTest.kt`              | Analog anpassen                                                                                   |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorServiceTest.kt`                        | Analog anpassen                                                                                   |

## Akzeptanzkriterien

- [ ] `ChunkExtractor`-Interface existiert mit `extractorName` und `extract(chunkId, collectionId): ExtractionSummary`.
- [ ] Alle 6 Extractor-Services implementieren `ChunkExtractor` (3 ueber `QuadProducingExtractor`, 3 direkt).
- [ ] `QuadProducingExtractor` absorbiert chunk-loading, LLM-Sanitization, QuadStore-Persistenz und Provenance-Generierung — keine Duplikation mehr in Relationship/Definition/Topic.
- [ ] Alle 6 Consumer-Klassen erben von `AbstractExtractionConsumer` und enthalten maximal 5 LOC (Konstruktor + `groupId`).
- [ ] `EmbeddingConsumer` bleibt unveraendert (andere Signatur, nicht Teil des Refactorings).
- [ ] Kafka `groupId`s bleiben identisch zu vorher — kein Consumer-Offset-Reset noetig.
- [ ] Bestehende Extraktions-Pipeline (End-to-End: Dokument hochladen, Chunks erzeugen, alle Extractors laufen) funktioniert identisch.
- [ ] Neue Unit-Tests fuer `ChunkExtractor`-Contract, `QuadProducingExtractor` und `AbstractExtractionConsumer` sind gruen.
- [ ] Bestehende Tests von Feature 12 / 19 / 21 / 23 / 24 / 29 bleiben gruen.
- [ ] Gesamte LOC-Reduktion im Consumer/Boilerplate-Bereich betraegt mindestens 200 LOC.
