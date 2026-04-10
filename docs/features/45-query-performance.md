# Feature 45: Query Performance Optimierung

## Problem

Jede Query im GraphMesh-System durchlaeuft mehrere **sequenzielle LLM-Aufrufe**, die jeweils
500-2000ms dauern. Im NLP-Auto-Modus (der Standard-Pfad aus dem Frontend) sind es bis zu
**4 blockierende Roundtrips**: Intent-Detection → Embedding → Edge-Selection → Answer-Synthesis.
Im HYBRID-Modus sogar 7. Keine der Zwischenergebnisse wird gecacht, und unabhaengige Calls
laufen nie parallel.

### Ist-Zustand: LLM-Calls pro Query-Modus

| Modus                 | Embedding | Text-Gen (LLM) | Total | Parallelisiert |
|-----------------------|-----------|-----------------|-------|----------------|
| NLP Auto → GraphRAG   | 1         | 3 (Intent + EdgeSelect + Synthesize) | **4** | Nein |
| NLP Auto → DocRAG     | 1         | 2 (Intent + Synthesize) | **3** | Nein |
| GraphRAG direkt        | 1         | 2 (EdgeSelect + Synthesize) | **3** | Nein |
| DocumentRAG direkt     | 1         | 1 (Synthesize) | **2** | Nein |
| HYBRID                | 2         | 5 | **7** | Nein |

### Betroffene Code-Stellen

| Datei | Call | Typ | Modell |
|-------|------|-----|--------|
| `NlpQueryService.kt:96` | Intent-Detection | Text-Gen | `gpt-4o-mini` |
| `NlpQueryService.kt:122` | Reformulation (conditional) | Text-Gen | `gpt-4o-mini` |
| `GraphRagService.kt:99` | Query-Embedding | Embedding | `text-embedding-3-small` |
| `GraphRagService.kt:215` | Edge-Selection | Text-Gen | `gpt-4o-mini` |
| `GraphRagService.kt:263` | Answer-Synthesis | Text-Gen | `gpt-4o-mini` |
| `DocumentRagService.kt:93` | Query-Embedding | Embedding | `text-embedding-3-small` |
| `DocumentRagService.kt:161` | Answer-Synthesis | Text-Gen | `gpt-4o-mini` |

Alle Calls nutzen `runBlocking` und laufen strikt sequenziell.

## Ziel

Die wahrgenommene Query-Latenz um **50-70%** reduzieren durch drei kombinierte Massnahmen:

1. **LLM-Calls eliminieren** — Unnoetige Roundtrips entfernen
2. **Parallelisierung** — Unabhaengige Calls gleichzeitig ausfuehren
3. **Caching** — Wiederholte Berechnungen vermeiden

### Soll-Zustand nach Optimierung

| Modus                 | Calls vorher | Calls nachher | Einsparung |
|-----------------------|-------------|---------------|------------|
| NLP Auto → GraphRAG   | 4 sequenziell | 2 (parallel: Embed ∥ Intent, dann Synthesize) | ~50% |
| GraphRAG direkt        | 3 sequenziell | 2 (Embed, dann Synthesize) | ~33% |
| DocumentRAG direkt     | 2 sequenziell | 2 (unveraendert, schon minimal) | — |
| HYBRID                | 7 sequenziell | 3 (Intent ∥ Embed, dann GraphRAG ∥ DocRAG) | ~57% |

## Voraussetzungen

| Abhaengigkeit | Status | Blocker? |
|---|---|---|
| Feature 15: Graph RAG | Implementiert | Ja |
| Feature 16: Document RAG | Implementiert | Ja |
| Feature 18: NLP Query Service | Implementiert | Ja |
| Feature 04: Qdrant Vector Store | Implementiert | Ja |
| Feature 05: LLM Provider Abstraction | Implementiert | Ja |
| Kotlin Coroutines (`kotlinx-coroutines-core`) | Verfuegbar | Nein |

## Architektur

### Teil 1: LLM-Calls eliminieren

#### 1a. Intent-Detection ueberspringen wenn moeglich

Aktuell ruft `NlpQueryService` immer erst ein LLM auf, um den Intent zu klassifizieren.
In vielen Faellen ist der Intent vorhersagbar:

- **Frontend-Modus `graph-rag`/`document-rag`**: User hat explizit gewaehlt → `forceIntent` nutzen
- **Collection ohne Dokumente**: Nur RDF-Daten → immer `GRAPH_QUERY`
- **Collection ohne RDF-Triples**: Nur Dokumente → immer `DOCUMENT_QUERY`

**Aenderung in `NlpQueryService.query()`:**

```kotlin
val detectedIntent = when {
    query.forceIntent != null ->
        DetectedIntent(query.forceIntent, 1.0, "Intent forced by caller")
    collectionHasOnlyRdf(query.collectionId) ->
        DetectedIntent(QueryIntent.GRAPH_QUERY, 1.0, "Collection contains only RDF data")
    collectionHasOnlyDocuments(query.collectionId) ->
        DetectedIntent(QueryIntent.DOCUMENT_QUERY, 1.0, "Collection contains only documents")
    else ->
        detectIntent(query.question)  // LLM-Call nur wenn wirklich noetig
}
```

Die `collectionHasOnlyRdf`/`collectionHasOnlyDocuments`-Pruefung nutzt `QuadStore` und
`LibrarianService` (einfache Existenz-Checks, kein LLM noetig). Das Ergebnis kann pro
Collection gecacht werden (aendert sich nur bei Import).

#### 1b. Edge-Selection und Answer-Synthesis zusammenlegen

Aktuell macht `GraphRagService` zwei separate LLM-Calls:

1. **Edge-Selection** (Prompt: "Waehle relevante Kanten aus") → LLM antwortet mit Index-Liste
2. **Answer-Synthesis** (Prompt: "Beantworte basierend auf diesen Kanten") → LLM antwortet mit Text

Diese koennen in **einen einzigen Prompt** zusammengefasst werden:

```
Hier sind Fakten aus einem Knowledge Graph:
0|Alice|arbeitetBei|Acme
1|Bob|arbeitetBei|Acme
...

Beantworte die Frage basierend auf diesen Fakten.
Nenne am Ende die genutzten Fakten-Nummern mit Begruendung.

Frage: Wer arbeitet bei Acme?
```

**Aenderung:** Neue Methode `selectAndSynthesize()` ersetzt `selectEdges()` + `synthesizeAnswer()`.
Das Response-Format wird so gestaltet, dass Antwort und genutzte Kanten in einem Call zurueckkommen.

### Teil 2: Parallelisierung

#### 2a. Embedding parallel zur Intent-Detection

Im NLP-Auto-Modus braucht man das Embedding unabhaengig vom Intent-Ergebnis (sowohl
GraphRAG als auch DocRAG brauchen es). Beide koennen parallel gestartet werden:

```kotlin
val (detectedIntent, queryVector) = coroutineScope {
    val intentDeferred = async { detectIntentOrInfer(query) }
    val embeddingDeferred = async { computeEmbedding(query.question) }
    intentDeferred.await() to embeddingDeferred.await()
}
```

#### 2b. HYBRID: GraphRAG und DocRAG parallel

Aktuell in `NlpQueryService.route()` bei HYBRID:

```kotlin
// VORHER: sequenziell
val graphResult = graphRagService.query(...)
val docResult = documentRagService.query(...)

// NACHHER: parallel
val (graphResult, docResult) = coroutineScope {
    val graph = async { graphRagService.query(...) }
    val doc = async { documentRagService.query(...) }
    graph.await() to doc.await()
}
```

#### 2c. GraphRagService: Embedding vorberechnet durchreichen

Wenn NLP-Auto den Embedding-Vektor bereits parallel berechnet hat, sollte GraphRagService
ihn nicht nochmal berechnen. Neuer optionaler Parameter:

```kotlin
data class GraphRagQuery(
    val question: String,
    val collectionId: String,
    val precomputedEmbedding: FloatArray? = null,  // NEU
    val maxEdges: Int = 150,
    val maxDepth: Int = 2,
    val maxSelectedEdges: Int = 30
)
```

`retrieveSubgraph()` nutzt `precomputedEmbedding` wenn vorhanden, sonst berechnet es selbst.

### Teil 3: Caching

#### 3a. Embedding-Cache

Gleiche Frage → gleicher Vektor. Ein einfacher In-Memory-Cache (Caffeine) mit TTL:

```kotlin
@Bean
fun embeddingCache(): Cache<String, FloatArray> =
    Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofMinutes(30))
        .build()
```

Cache-Key: `SHA-256(question + embeddingModel)`.

#### 3b. Collection-Typ-Cache

Ob eine Collection nur RDF, nur Dokumente oder beides enthaelt, aendert sich selten.
Cache mit Invalidierung bei Import-Events:

```kotlin
@Bean
fun collectionTypeCache(): Cache<String, CollectionContentType> =
    Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(Duration.ofMinutes(10))
        .build()

enum class CollectionContentType { RDF_ONLY, DOCUMENTS_ONLY, MIXED }
```

Invalidierung ueber bestehende Kafka-Events (`document.ingested`, `rdf.imported`).

## Betroffene Dateien

### Backend

| Datei | Aenderung |
|---|---|
| `NlpQueryService.kt` | Intent-Inferenz statt LLM-Call, parallele Embedding-Berechnung, HYBRID-Parallelisierung |
| `GraphRagService.kt` | `selectAndSynthesize()` statt zwei separate Calls, `precomputedEmbedding` Parameter |
| `DocumentRagService.kt` | `precomputedEmbedding` Parameter |
| `GraphRagQuery.kt` / `GraphRagModels.kt` | Neues Feld `precomputedEmbedding` |
| `DocumentRagQuery.kt` / `DocumentRagModels.kt` | Neues Feld `precomputedEmbedding` |
| `EmbeddingCacheConfig.kt` | NEU — Caffeine-Cache-Bean fuer Embeddings |
| `CollectionContentTypeService.kt` | NEU — Bestimmt RDF_ONLY/DOCUMENTS_ONLY/MIXED mit Cache |
| `build.gradle.kts` | Caffeine-Dependency hinzufuegen |

### Tests

| Datei | Aenderung |
|---|---|
| `NlpQueryServiceTest.kt` | Tests fuer Intent-Inferenz, Parallelisierung |
| `GraphRagServiceTest.kt` | Tests fuer kombiniertes Select+Synthesize, precomputedEmbedding |
| `EmbeddingCacheTest.kt` | NEU — Cache-Hit/Miss/Invalidierung |
| `CollectionContentTypeServiceTest.kt` | NEU — RDF_ONLY/DOCUMENTS_ONLY/MIXED-Erkennung |

## Implementierungsreihenfolge

Die drei Teile sind unabhaengig und koennen in beliebiger Reihenfolge implementiert werden.
Empfohlene Reihenfolge nach Impact/Aufwand:

1. **Teil 1b: Edge-Selection + Synthesis zusammenlegen** — Groesster Impact (1 LLM-Call weniger bei jedem GraphRAG), geringster Aufwand
2. **Teil 1a: Intent-Detection ueberspringen** — Grosser Impact fuer NLP-Auto, mittlerer Aufwand
3. **Teil 2c: Embedding durchreichen** — Vermeidet doppelte Embedding-Berechnung
4. **Teil 2a: Embedding ∥ Intent parallel** — Mittlerer Impact, braucht Coroutine-Umbau
5. **Teil 2b: HYBRID parallel** — Grosser Impact fuer HYBRID-Modus
6. **Teil 3a: Embedding-Cache** — Hilft bei wiederholten Fragen
7. **Teil 3b: Collection-Typ-Cache** — Unterstuetzt Teil 1a

## Akzeptanzkriterien

- [ ] GraphRAG-Query braucht maximal 2 LLM-Calls (Embedding + Synthesize) statt 3
- [ ] NLP-Auto-Query bei reinen RDF-Collections braucht keinen Intent-Detection-Call
- [ ] NLP-Auto-Query bei reinen Dokument-Collections braucht keinen Intent-Detection-Call
- [ ] HYBRID-Modus fuehrt GraphRAG und DocRAG parallel aus
- [ ] Embedding-Berechnung wird im NLP-Auto-Modus parallel zur Intent-Detection gestartet
- [ ] Wiederholte identische Fragen nutzen gecachte Embeddings
- [ ] Bestehende Query-Ergebnisse bleiben inhaltlich identisch (keine Regression)
- [ ] Latenz-Reduktion messbar: NLP Auto → GraphRAG unter 3s (vorher ~5-8s)
- [ ] Unit-Tests fuer alle neuen/geaenderten Komponenten
