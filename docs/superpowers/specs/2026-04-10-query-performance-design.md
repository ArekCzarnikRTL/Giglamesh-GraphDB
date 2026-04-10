# Design Spec: Feature 45 — Query Performance Optimierung

## Kontext

Jede Query durchlaeuft 3-7 sequenzielle LLM-Roundtrips (je 500-2000ms). Kein Caching,
keine Parallelisierung. Ziel: 50-70% Latenzreduktion durch Calls eliminieren,
Parallelisierung und Caching. Ansatz: schrittweise Optimierung (7 Commits).

## Schritt 1: Edge-Selection + Synthesis zusammenlegen

**Problem:** `GraphRagService` macht zwei separate LLM-Calls — erst Edge-Selection
(`selectEdges()`), dann Answer-Synthesis (`synthesizeAnswer()`).

**Loesung:** Neue Methode `selectAndSynthesize()` mit einem kombinierten Prompt.

**Prompt-Format:**

```
You are a knowledge assistant. Answer the user's question based ONLY on the
provided knowledge graph facts. Do not make up information.

After your answer, list the fact numbers you used with reasoning.

Knowledge graph facts:
0|Alice|arbeitetBei|Acme
1|Bob|arbeitetBei|Acme
...

Respond in this exact format:
ANSWER:
<your answer here>

EDGES:
<index>|<reasoning why this fact was relevant>
```

**Response-Parsing:** `parseSelectAndSynthesize(response, edges)` gibt
`Pair<String, List<SelectedEdge>>` zurueck. Text vor `EDGES:` ist die Antwort,
danach die bekannte `INDEX|REASONING`-Struktur. Bestehende `parseEdgeSelection()`
bleibt als Referenz; neuer Parser uebernimmt.

**Fallback:** Wenn `EDGES:`-Marker fehlt, wird der gesamte Text als Antwort behandelt
und `selectedEdges` ist leer (graceful degradation, keine Exception).

**Aenderungen:**
- `GraphRagService.kt`: Neue Methode `selectAndSynthesize()`, ersetzt Aufrufe von
  `selectEdges()` + `synthesizeAnswer()` in `query()`. Alte Methoden bleiben private
  (koennen spaeter entfernt werden).

**Einsparung:** -1 LLM-Call pro GraphRAG-Query.

## Schritt 2: Intent-Detection ueberspringen

**Problem:** `NlpQueryService` ruft immer ein LLM fuer Intent-Detection auf, auch wenn
die Collection nur RDF-Daten oder nur Dokumente enthaelt.

**Loesung:** Heuristik vor dem LLM-Call.

```kotlin
val detectedIntent = when {
    query.forceIntent != null ->
        DetectedIntent(query.forceIntent, 1.0, "Intent forced by caller")
    !contentTypeService.hasDocuments(query.collectionId) ->
        DetectedIntent(QueryIntent.GRAPH_QUERY, 1.0, "Collection contains only graph data")
    !contentTypeService.hasTriples(query.collectionId) ->
        DetectedIntent(QueryIntent.DOCUMENT_QUERY, 1.0, "Collection contains only documents")
    else ->
        detectIntent(query.question)  // LLM nur bei MIXED
}
```

**Neuer Service: `CollectionContentTypeService`**

```kotlin
@Service
class CollectionContentTypeService(
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService,
) {
    private val cache: Cache<String, ContentFlags> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(Duration.ofMinutes(10))
        .build()

    data class ContentFlags(val hasTriples: Boolean, val hasDocuments: Boolean)

    fun hasTriples(collectionId: String): Boolean =
        getFlags(collectionId).hasTriples

    fun hasDocuments(collectionId: String): Boolean =
        getFlags(collectionId).hasDocuments

    fun isMixed(collectionId: String): Boolean =
        getFlags(collectionId).let { it.hasTriples && it.hasDocuments }

    fun invalidate(collectionId: String) = cache.invalidate(collectionId)

    private fun getFlags(collectionId: String): ContentFlags =
        cache.get(collectionId) { cid ->
            ContentFlags(
                hasTriples = quadStore.query(cid, QuadQuery(), limit = 1).isNotEmpty(),
                hasDocuments = librarianService.countByCollection(cid) > 0
            )
        }
}
```

`LibrarianService.findByCollection(collectionId)` existiert bereits und gibt
`List<Document>` zurueck. Fuer den Existenz-Check genuegt:
`librarianService.findByCollection(collectionId).isNotEmpty()`. Fuer groessere
Collections sollte spaeter ein `limit`-Parameter oder Count-Query ergaenzt werden.

**Aenderungen:**
- `CollectionContentTypeService.kt` — NEU
- `NlpQueryService.kt` — Heuristik einfuegen, Service injizieren
- `build.gradle.kts` — `com.github.ben-manes.caffeine:caffeine` Dependency

**Einsparung:** -1 LLM-Call pro NLP-Auto-Query bei RDF-only oder Doc-only Collections.

## Schritt 3: Embedding-Cache

**Problem:** Gleiche Frage erzeugt jedes Mal einen neuen Embedding-API-Call (~200-500ms).

**Loesung:** `CachedEmbeddingService` mit Caffeine-Cache.

```kotlin
@Service
class CachedEmbeddingService(
    private val embeddingProvider: LLMEmbeddingProvider,
) {
    private val cache: Cache<String, FloatArray> = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofMinutes(30))
        .build()

    fun embed(text: String, model: LLModel): FloatArray {
        val key = "${model.id}:${text.hashCode()}"
        return cache.get(key) { _ ->
            val embedding = runBlocking { embeddingProvider.embed(text, model) }
            FloatArray(embedding.size) { embedding[it].toFloat() }
        }
    }
}
```

Cache-Key nutzt `text.hashCode()` statt SHA-256 — ausreichend fuer 1000 Eintraege,
und vermeidet Crypto-Overhead. Bei Kollision wird einfach neu berechnet (Cache-Miss,
kein falsches Ergebnis).

**Aenderungen:**
- `CachedEmbeddingService.kt` — NEU
- `GraphRagService.kt` — `embeddingProvider` durch `CachedEmbeddingService` ersetzen
- `DocumentRagService.kt` — gleiche Aenderung

**Einsparung:** ~200-500ms bei wiederholten identischen Fragen.

## Schritt 4: Precomputed Embedding durchreichen

**Problem:** Wenn `NlpQueryService` zu `GraphRagService` oder `DocumentRagService`
delegiert, berechnet der delegierte Service das Embedding nochmal.

**Loesung:** Optionales Feld `precomputedEmbedding` in den Query-Modellen.

```kotlin
data class GraphRagQuery(
    val question: String,
    val collectionId: String,
    val precomputedEmbedding: FloatArray? = null,
    val maxEdges: Int = 150,
    val maxDepth: Int = 2,
    val maxSelectedEdges: Int = 30
)
```

Gleich fuer `DocumentRagQuery`.

In `retrieveSubgraph()` / `retrieveChunks()`:

```kotlin
val queryVector = query.precomputedEmbedding
    ?: cachedEmbeddingService.embed(query.question, embeddingModel)
```

`NlpQueryService.route()` berechnet das Embedding einmal und reicht es durch:

```kotlin
private fun route(question: String, intent: QueryIntent, collectionId: String): Pair<String, List<String>> {
    val embedding = cachedEmbeddingService.embed(question, embeddingModel)
    return when (intent) {
        QueryIntent.GRAPH_QUERY -> {
            val result = graphRagService.query(GraphRagQuery(question, collectionId, precomputedEmbedding = embedding))
            ...
        }
        QueryIntent.DOCUMENT_QUERY -> {
            val result = documentRagService.query(DocumentRagQuery(question, collectionId, precomputedEmbedding = embedding))
            ...
        }
        ...
    }
}
```

**Aenderungen:**
- `GraphRagQuery.kt` oder inline in `GraphRagService.kt` — neues Feld
- `DocumentRagModels.kt` — neues Feld
- `GraphRagService.kt` — `precomputedEmbedding` nutzen
- `DocumentRagService.kt` — `precomputedEmbedding` nutzen
- `NlpQueryService.kt` — Embedding berechnen und durchreichen

**Einsparung:** -1 doppelter Embedding-Call pro NLP-Auto-Query.

## Schritt 5: Embedding parallel zur Intent-Detection

**Problem:** Bei MIXED Collections laufen Intent-Detection (LLM) und Embedding-Berechnung
sequenziell, obwohl sie unabhaengig sind.

**Loesung:** `coroutineScope` + `async` nur im MIXED-Fall:

```kotlin
// In NlpQueryService.query():
val (detectedIntent, precomputedEmbedding) = when {
    query.forceIntent != null -> {
        val intent = DetectedIntent(query.forceIntent, 1.0, "Forced")
        val embedding = cachedEmbeddingService.embed(query.question, embeddingModel)
        intent to embedding
    }
    !contentTypeService.hasDocuments(query.collectionId) -> {
        val intent = DetectedIntent(QueryIntent.GRAPH_QUERY, 1.0, "RDF only")
        val embedding = cachedEmbeddingService.embed(query.question, embeddingModel)
        intent to embedding
    }
    !contentTypeService.hasTriples(query.collectionId) -> {
        val intent = DetectedIntent(QueryIntent.DOCUMENT_QUERY, 1.0, "Docs only")
        val embedding = cachedEmbeddingService.embed(query.question, embeddingModel)
        intent to embedding
    }
    else -> runBlocking {
        coroutineScope {
            val intentDeferred = async { detectIntent(query.question) }
            val embeddingDeferred = async { cachedEmbeddingService.embed(query.question, embeddingModel) }
            intentDeferred.await() to embeddingDeferred.await()
        }
    }
}
```

**Voraussetzung:** `detectIntent()` und `cachedEmbeddingService.embed()` muessen
thread-safe sein. Beide nutzen eigene HTTP-Clients, kein shared mutable state.

**Aenderungen:** Nur `NlpQueryService.kt`.

**Einsparung:** ~1s Wartezeit bei MIXED Collections (Intent + Embedding parallel statt sequenziell).

## Schritt 6: HYBRID parallel

**Problem:** HYBRID-Modus ruft GraphRAG und DocRAG sequenziell auf.

**Loesung:**

```kotlin
QueryIntent.HYBRID -> {
    val (graphResult, docResult) = runBlocking {
        coroutineScope {
            val graph = async {
                graphRagService.query(GraphRagQuery(question, collectionId, precomputedEmbedding = precomputedEmbedding))
            }
            val doc = async {
                documentRagService.query(DocumentRagQuery(question, collectionId, precomputedEmbedding = precomputedEmbedding))
            }
            graph.await() to doc.await()
        }
    }
    // Combine wie bisher
}
```

**Aenderungen:** Nur `NlpQueryService.kt` — `route()` Methode, HYBRID-Branch.

**Einsparung:** ~50% Latenz bei HYBRID-Queries.

## Schritt 7: Tests

| Testdatei | Was |
|-----------|-----|
| `GraphRagServiceTest.kt` | `parseSelectAndSynthesize()` mit verschiedenen Formaten, Fallback bei fehlendem EDGES-Marker |
| `NlpQueryServiceTest.kt` | Intent-Heuristik: RDF-only → GRAPH_QUERY, Doc-only → DOCUMENT_QUERY, MIXED → LLM-Call |
| `CachedEmbeddingServiceTest.kt` (NEU) | Cache-Hit, Cache-Miss, unterschiedliche Modelle getrennt gecacht |
| `CollectionContentTypeServiceTest.kt` (NEU) | hasTriples/hasDocuments/isMixed, Cache-Invalidierung |

Alle Tests nutzen InMemory-Implementierungen (InMemoryQuadStore, Mock-EmbeddingProvider) —
keine Docker-Abhaengigkeiten.

## Abhaengigkeiten

Neue Dependency in `build.gradle.kts`:

```kotlin
implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
```

Kotlin Coroutines sind bereits vorhanden (via Koog-Framework).

## Ergebnis-Uebersicht

| Modus | Vorher | Nachher |
|-------|--------|---------|
| NLP Auto → GraphRAG (RDF-only) | 4 Calls sequenziell | 2 Calls (Embed + Synthesize) |
| NLP Auto → GraphRAG (MIXED) | 4 Calls sequenziell | 3 Calls (Intent ∥ Embed, dann Synthesize) |
| GraphRAG direkt | 3 Calls sequenziell | 2 Calls (Embed + Synthesize) |
| DocumentRAG direkt | 2 Calls sequenziell | 2 Calls (unveraendert) |
| HYBRID | 7 Calls sequenziell | 3 Calls (Intent ∥ Embed, dann GraphRAG ∥ DocRAG) |
