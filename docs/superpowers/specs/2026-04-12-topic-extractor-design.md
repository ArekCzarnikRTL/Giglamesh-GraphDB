# Feature 38: Topic Extractor — Design Spec

## Ziel

Ein `TopicExtractorService`, der Textchunks auf abstrakte Themen (Topics) untersucht und diese als `skos:Concept`-Entities im Knowledge Graph verankert. Topics werden per LLM aus Chunks extrahiert, optional gegen importierte Ontologien/SKOS-Taxonomien abgeglichen, und mit Provenance-Quads gespeichert.

## Dateien & Package-Struktur

Alles unter `com.agentwork.graphmesh.extraction.topic`:

| Datei | Zweck |
|---|---|
| `TopicExtractorModels.kt` | `TopicResult`, `TopicExtractionResult` |
| `TopicPromptTemplate.kt` | System/User-Prompts, optional mit Ontologie-Hints |
| `TopicOntologyMatcher.kt` | Pre-Extraction Hints + Post-Extraction URI-Matching |
| `TopicExtractorService.kt` | Kernlogik: LLM-Aufruf, JSONL-Parsing, Quad-Erzeugung, Provenance |
| `TopicExtractorConsumer.kt` | Kafka-Consumer auf `graphmesh.chunk.created` |

Plus `application.yml`-Erweiterung und Tests.

## Datenfluss

```
chunk.created Event
  -> TopicExtractorConsumer.handle()
    -> TopicExtractorService.extract(chunkId, collectionId)
      -> 1. TopicOntologyMatcher.getHints(collectionId)
      -> 2. TopicPromptTemplate.systemPrompt(hints)
      -> 3. PromptExecutor.execute(prompt, model)
      -> 4. parseJsonlTopics(response)
      -> 5. filter(confidence >= minConfidence)
      -> 6. distinctBy(normalize(label))
      -> 7. TopicOntologyMatcher.resolveOrCreate(label, collectionId)
      -> 8. Quads erzeugen (rdf:type, rdfs:label, dct:subject, confidence)
      -> 9. ProvenanceService.buildSubgraphQuads()
      -> 10. QuadStore.insertBatch()
```

## Komponenten

### TopicExtractorModels.kt

```kotlin
data class TopicResult(
    val topic: String,
    val confidence: Double,
    val rationale: String? = null
)

data class TopicExtractionResult(
    val chunkId: String,
    val topicsExtracted: Int,
    val topics: List<String>
)
```

### TopicPromptTemplate.kt

- `systemPrompt(hints: List<String> = emptyList())` — Basis-Prompt mit Topic-vs-Entity-Regeln. Wenn `hints` nicht leer, wird ein Block angefuegt: "Bevorzuge folgende bekannte Konzepte, falls sie zum Text passen: ..." mit den Hint-Labels.
- `userPrompt(chunkText: String)` — Fordert JSONL-Ausgabe an.

Prompt-Inhalte wie im Feature-Dokument spezifiziert: max 5 Topics, confidence 0.0..1.0, rationale max 10 Woerter, keine Entitaeten/Personen/Orte/Firmen.

### TopicOntologyMatcher.kt

```kotlin
@Service
class TopicOntologyMatcher(
    private val ontologyService: OntologyService,
    private val skosService: SkosService,
    private val quadStore: QuadStore
)
```

**Pre-Extraction — `getHints(collectionId: String): List<String>`**

Sammelt bekannte Konzeptlabels aus zwei Quellen:
1. OWL-Klassen aus `ontologyService.get(collectionId)?.classes` — Labels extrahieren
2. SKOS-Concepts aus `skosService.getConceptSchemes(collectionId)` — fuer jedes Schema die Concepts holen und deren prefLabels sammeln

Gibt leere Liste zurueck wenn keine Ontologie/SKOS-Daten vorhanden. Ergebnis wird dedupliziert und auf max 50 Labels begrenzt (damit der Prompt nicht zu lang wird).

**Post-Extraction — `resolveOrCreate(label: String, collectionId: String): RdfTerm.Uri`**

1. Normalisiert das Label (trim, lowercase, whitespace-collapse)
2. Sucht via `skosService.findByLabel(collectionId, normalizedLabel)` nach bestehendem SKOS-Concept
3. Bei exaktem Match (normalisiertes prefLabel == normalizedLabel) -> dessen URI zurueckgeben
4. Kein Match -> `EntityIdGenerator.generate(normalizedLabel)` fuer neue URI

### TopicExtractorService.kt

Folgt 1:1 dem DefinitionExtractorService-Pattern:

- Dependencies: `PromptExecutor`, `QuadStore`, `LibrarianService`, `ProvenanceService`, `TopicOntologyMatcher`
- Config: `@Value("${graphmesh.extraction.model:gpt-4o}")` fuer Model, `@Value("${graphmesh.extraction.topic.minConfidence:0.5}")` fuer Schwellenwert
- `extract(chunkId, collectionId)` -> `TopicExtractionResult`
- `parseJsonlTopics(llmResponse)` -> `List<TopicResult>` (internal, testbar)

**Quad-Erzeugung pro Topic:**

Verwendet `SkosTypes.CONCEPT` statt lokaler Konstante.

- `(topicUri, rdf:type, skos:Concept)` im DEFAULT-Graph
- `(topicUri, rdfs:label, "<Original-Label>")` im DEFAULT-Graph
- `(chunkUri, dct:subject, topicUri)` im DEFAULT-Graph
- `<<chunkUri dct:subject topicUri>> graphmesh:topicConfidence "0.87"` als QuotedTriple im DEFAULT-Graph

Alle Quads werden per `distinctBy(subject|predicate|object|graph)` dedupliziert vor `insertBatch`.

### TopicExtractorConsumer.kt

```kotlin
@Component
class TopicExtractorConsumer(private val extractorService: TopicExtractorService) {
    @KafkaListener(topics = ["graphmesh.chunk.created"], groupId = "graphmesh-topic-extractor")
    fun handle(record: ConsumerRecord<String, GenericRecord>) { ... }
}
```

Identisches Pattern wie `DefinitionExtractorConsumer`: chunkId/collectionId aus GenericRecord extrahieren, `extractorService.extract()` aufrufen, `DocumentNotFoundException` abfangen.

## Konfiguration

Erweiterung in `application.yml`:

```yaml
graphmesh:
  extraction:
    topic:
      minConfidence: 0.5
```

Das LLM-Model wird aus `graphmesh.extraction.model` geteilt (wie bei allen Extractors).

## RDF-Konstanten

| Konstante | Quelle |
|---|---|
| `skos:Concept` | `SkosTypes.CONCEPT` (bereits in `RdfTerm.kt`) |
| `rdfs:label` | Lokale Companion-Konstante `RDFS_LABEL` |
| `dct:subject` | Lokale Companion-Konstante `DCT_SUBJECT` |
| `graphmesh:topicConfidence` | Lokale Companion-Konstante `TOPIC_CONFIDENCE` |
| `rdf:type` | Lokale Companion-Konstante `RDF_TYPE` |

## Abweichungen vom Feature-Dokument

1. Feature-Doc zeigt `quadStore.findByPredicateAndObject()` — existiert nicht. Stattdessen `quadStore.query(collection, QuadQuery(...))`.
2. Feature-Doc zeigt `quadStore.exists()` — existiert nicht. Wird via `query()` + `.isNotEmpty()` umgesetzt.
3. Feature-Doc nutzt `SKOS_CONCEPT` als lokale Konstante — stattdessen `SkosTypes.CONCEPT` aus `RdfTerm.kt`.
4. Frontend-Teil (TopicFacet.tsx) wird nicht implementiert — separates Feature.

## Tests

| Testdatei | Inhalt |
|---|---|
| `TopicExtractorServiceTest.kt` | Unit-Tests mit Fake PromptExecutor und InMemoryQuadStore: Extraktion, Quad-Erzeugung, Provenance, leerer Input |
| `TopicPromptTemplateTest.kt` | Template-Konsistenz: JSONL-Anweisung vorhanden, Hints korrekt eingebaut |
| `TopicJsonlParsingTest.kt` | JSONL-Edge-Cases: truncation, ungueltige Zeilen, Markdown-Fences, fehlende Felder |
| `TopicDeduplicationTest.kt` | Normalisierung + Deduplikation: Case, Whitespace, identische Labels |
| `TopicOntologyMatcherTest.kt` | Matching gegen importierte SKOS/OWL-Konzepte, Fallback auf EntityIdGenerator |
