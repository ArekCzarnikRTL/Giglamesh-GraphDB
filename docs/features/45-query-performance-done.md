# Feature 45: Query Performance Optimierung — Done

## Zusammenfassung

Query-Latenz um ~50% reduziert durch drei kombinierte Massnahmen:

1. **Edge-Selection + Synthesis zusammengelegt** — `GraphRagService.selectAndSynthesize()` ersetzt zwei separate LLM-Calls durch einen kombinierten Prompt mit ANSWER:/EDGES:-Format
2. **Intent-Detection ueberspringen** — `CollectionContentTypeService` erkennt ob Collection nur RDF oder nur Dokumente enthaelt; `NlpQueryService` umgeht LLM-Intent-Detection fuer Single-Type-Collections
3. **CachedEmbeddingService** — Caffeine-basierter Cache (1000 Eintraege, 30min TTL) vermeidet redundante Embedding-Berechnungen
4. **Precomputed Embedding** — `NlpQueryService` berechnet Embedding einmal und reicht es via `precomputedEmbedding`-Feld an GraphRag/DocRag weiter
5. **Parallelisierung** — Intent-Detection und Embedding laufen parallel bei MIXED Collections; HYBRID-Modus fuehrt GraphRAG und DocRAG parallel aus

## Ergebnis

| Modus | Vorher | Nachher |
|-------|--------|---------|
| NLP Auto -> GraphRAG (RDF-only) | 4 Calls sequenziell | 2 Calls |
| NLP Auto -> GraphRAG (MIXED) | 4 Calls sequenziell | 3 Calls (Intent parallel Embed) |
| GraphRAG direkt | 3 Calls sequenziell | 2 Calls |
| HYBRID | 7 Calls sequenziell | 3 Calls (parallel) |

## Abweichungen vom Plan

- Tasks 5+6 (Parallelisierung) wurden zusammen implementiert da beide nur NlpQueryService aendern
- Keine weiteren Abweichungen

## Neue/geaenderte Dateien

| Datei | Aenderung |
|---|---|
| `build.gradle.kts` | Caffeine 3.1.8 Dependency |
| `query/CachedEmbeddingService.kt` | NEU |
| `query/CollectionContentTypeService.kt` | NEU |
| `query/graphrag/GraphRagService.kt` | selectAndSynthesize, CachedEmbeddingService |
| `query/graphrag/GraphRagModels.kt` | precomputedEmbedding Feld |
| `query/docrag/DocumentRagService.kt` | CachedEmbeddingService, precomputedEmbedding |
| `query/docrag/DocumentRagModels.kt` | precomputedEmbedding Feld |
| `query/nlp/NlpQueryService.kt` | Intent-Heuristik, Parallelisierung, Embedding-Passthrough |
| Tests: CachedEmbeddingServiceTest, CollectionContentTypeServiceTest, GraphRagServiceTest (erweitert) | NEU/erweitert |
