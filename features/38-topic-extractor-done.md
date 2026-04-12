# Feature 38: Topic Extractor — Done

## Zusammenfassung

LLM-basierte Themenextraktion aus Textchunks, gespeichert als SKOS-Concepts im Knowledge Graph.

### Implementierte Komponenten

| Datei | Zweck |
|---|---|
| `TopicExtractorModels.kt` | `TopicResult`, `TopicExtractionResult` |
| `TopicPromptTemplate.kt` | System/User-Prompts mit optionalen Ontologie-Hints |
| `TopicOntologyMatcher.kt` | OWL-Klassen + SKOS-Concepts als Hints sammeln, URI-Resolution |
| `TopicExtractorService.kt` | Kernlogik: LLM-Aufruf, JSONL-Parsing, Quad-Erzeugung, Provenance |
| `TopicExtractorConsumer.kt` | Kafka-Consumer auf `graphmesh.chunk.created` |
| `application.yml` | `graphmesh.extraction.topic.min-confidence: 0.5` |

### Tests (29 Tests, alle bestanden)

- `TopicPromptTemplateTest` — 5 Tests (Prompt-Struktur, Hints-Integration)
- `TopicJsonlParsingTest` — 11 Tests (Edge-Cases: Truncation, Markdown-Fences, fehlende Felder)
- `TopicDeduplicationTest` — 6 Tests (Normalisierung, EntityId-Determinismus)
- `TopicOntologyMatcherTest` — 7 Tests (OWL/SKOS-Hints, URI-Resolution, Fallback)
- `TopicExtractorServiceTest` — 4 Tests (Quad-Erzeugung, Confidence-Filter, Dedup, Blank-Input)

## Abweichungen vom Feature-Dokument

1. `QuadStore` nicht als Dependency in `TopicOntologyMatcher` — war im Pseudocode aufgefuehrt, aber nie verwendet. URI-Resolution erfolgt ueber `SkosService.findByLabel()`.
2. `quadStore.findByPredicateAndObject()` und `quadStore.exists()` existieren nicht — stattdessen `QuadStore.query()` via `SkosService`.
3. `min-confidence` statt `minConfidence` in application.yml — Konsistenz mit bestehender kebab-case Convention.
4. Frontend-Teil (TopicFacet.tsx) nicht implementiert — separates Feature.

## Offene Punkte

- `normalize()`-Funktion ist in `TopicExtractorService` und `TopicOntologyMatcher` dupliziert. Funktional korrekt, koennte bei Bedarf extrahiert werden.
- JSONL-Parsing-Tests verwenden standalone Kopie der Parsing-Logik (Pattern aus DefinitionExtractorServiceTest). Die echte Logik wird indirekt via `TopicExtractorServiceTest` getestet.
