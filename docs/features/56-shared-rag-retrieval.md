# Feature 56: Shared RAG Retrieval (VectorRetriever)

## Problem

`DocumentRagService` und `GraphRagService` duplizieren die gleiche Retrieval-Logik: Frage embedden via `CachedEmbeddingService` → Vektor-Suche in Qdrant → Payload-Keys extrahieren. Beide Services injizieren `CachedEmbeddingService` UND `VectorStore` separat. Das `precomputedEmbedding ?: embed()` Pattern wird 2x identisch implementiert:

```kotlin
// DocumentRagService.kt:87
val queryVector = query.precomputedEmbedding ?: cachedEmbeddingService.embed(query.question)
val searchResults = vectorStore.search(
    collection = query.collectionId,
    queryVector = queryVector,
    limit = query.topK,
    scoreThreshold = query.similarityThreshold
)

// GraphRagService.kt:89
val queryVector = query.precomputedEmbedding ?: cachedEmbeddingService.embed(query.question)
val searchResults = vectorStore.search(
    collection = query.collectionId,
    queryVector = queryVector,
    limit = 50
)
```

Score-Range-Debug-Logging existiert nur in `DocumentRagService` (Zeilen 104–112), nicht in `GraphRagService`. Beim Wechsel des Embedding-Providers (Ollama ↔ OpenAI) ist dieses Logging die primaere Diagnose-Quelle fuer mis-tuned `scoreThreshold` — fehlt es in GraphRAG, sind Retrieval-Probleme dort blind zu debuggen.

## Ziel

Extraktion der gemeinsamen Embed+Search-Logik in einen dedizierten `VectorRetriever`-Service, der als einzige Stelle im System fuer Vektor-basiertes Retrieval zustaendig ist.

1. **`VectorRetriever`** — konkreter `@Service` in `com.agentwork.graphmesh.query`, kapselt Embedding-Aufloesung und Vektor-Suche in einer Klasse.
2. **Zwei Overloads** — `retrieve(question, collectionId, ...)` (embeddet intern) und `retrieve(precomputedEmbedding, collectionId, ...)` (ueberspringt Embedding).
3. **`RetrievalResult`** — Ergebnis-Datenklasse mit `hits: List<SearchResult>` + `embedding: FloatArray` + Helper `distinctPayloadValues(key)` fuer Payload-Key-Extraktion.
4. **RAG-Service-Vereinfachung** — `DocumentRagService` und `GraphRagService` verlieren je 2 Constructor-Dependencies (`CachedEmbeddingService`, `VectorStore`), gewinnen 1 (`VectorRetriever`).
5. **Konsistentes Score-Range-Logging** — Debug-Logging fuer Score-Verteilung zentral im `VectorRetriever`, gilt damit automatisch fuer beide RAG-Modi.
6. **NlpQueryService unberuehrt** — behaelt `CachedEmbeddingService` direkt (parallel async embedding pattern erfordert manuellen Zugriff auf das Embedding).

## Voraussetzungen

| Abhaengigkeit                                           | Status           | Blocker? |
|---------------------------------------------------------|------------------|----------|
| Feature 04 (Qdrant Vector Store)                        | Implementiert    | Nein     |
| Feature 13 (Document Embeddings)                        | Implementiert    | Nein     |
| Feature 15 (Graph RAG)                                  | Implementiert    | Nein     |
| Feature 16 (Document RAG)                               | Implementiert    | Nein     |
| `CachedEmbeddingService` in `query/`                    | Verfuegbar       | Nein     |
| `VectorStore` Interface in `storage/vector/`            | Verfuegbar       | Nein     |

Keine Infra-Aenderung in Qdrant / docker-compose.

## Architektur

### Ist-Zustand

```kotlin
// DocumentRagService.kt — injiziert CachedEmbeddingService + VectorStore
@Service
class DocumentRagService(
    private val cachedEmbeddingService: CachedEmbeddingService,
    private val vectorStore: VectorStore,
    private val librarianService: LibrarianService,
    private val promptExecutor: PromptExecutor,
    ...
) {
    private fun retrieveChunks(query: DocumentRagQuery): List<RetrievedChunk> {
        val queryVector = query.precomputedEmbedding ?: cachedEmbeddingService.embed(query.question)
        val searchResults = vectorStore.search(
            collection = query.collectionId,
            queryVector = queryVector,
            limit = query.topK,
            scoreThreshold = query.similarityThreshold
        )
        // Score-Range-Debug-Logging nur hier vorhanden
        if (logger.isDebugEnabled) {
            val scores = searchResults.map { it.score }
            logger.debug("Vector search returned {} hits, score range=[{}..{}]",
                searchResults.size, scores.minOrNull(), scores.maxOrNull())
        }
        // ... Payload-Verarbeitung
    }
}

// GraphRagService.kt — identisches Pattern, OHNE Score-Range-Logging
@Service
class GraphRagService(
    private val cachedEmbeddingService: CachedEmbeddingService,
    private val vectorStore: VectorStore,
    private val quadStore: QuadStore,
    private val promptExecutor: PromptExecutor,
    ...
) {
    private fun retrieveSubgraph(query: GraphRagQuery): List<StoredQuad> {
        val queryVector = query.precomputedEmbedding ?: cachedEmbeddingService.embed(query.question)
        val searchResults = vectorStore.search(
            collection = query.collectionId,
            queryVector = queryVector,
            limit = 50
        )
        // Kein Score-Range-Debug-Logging
        // ... Payload-Verarbeitung
    }
}
```

### Soll-Zustand

```kotlin
// DocumentRagService.kt — nur noch VectorRetriever
@Service
class DocumentRagService(
    private val vectorRetriever: VectorRetriever,
    private val librarianService: LibrarianService,
    private val promptExecutor: PromptExecutor,
    ...
) {
    private fun retrieveChunks(query: DocumentRagQuery): List<RetrievedChunk> {
        val result = vectorRetriever.retrieve(
            question = query.question,
            collectionId = query.collectionId,
            limit = query.topK,
            scoreThreshold = query.similarityThreshold,
            precomputedEmbedding = query.precomputedEmbedding
        )
        // Score-Range-Logging passiert jetzt zentral im VectorRetriever
        // ... Payload-Verarbeitung mit result.hits
    }
}

// GraphRagService.kt — analog
@Service
class GraphRagService(
    private val vectorRetriever: VectorRetriever,
    private val quadStore: QuadStore,
    private val promptExecutor: PromptExecutor,
    ...
) {
    private fun retrieveSubgraph(query: GraphRagQuery): List<StoredQuad> {
        val result = vectorRetriever.retrieve(
            question = query.question,
            collectionId = query.collectionId,
            limit = 50,
            precomputedEmbedding = query.precomputedEmbedding
        )
        // Score-Range-Logging jetzt automatisch auch hier aktiv
        // ... Payload-Verarbeitung mit result.hits
    }
}
```

### Subsection 1: VectorRetriever-Service

```kotlin
@Service
class VectorRetriever(
    private val cachedEmbeddingService: CachedEmbeddingService,
    private val vectorStore: VectorStore
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun retrieve(
        question: String,
        collectionId: String,
        limit: Int = 10,
        scoreThreshold: Float? = null,
        filter: VectorFilter? = null,
        precomputedEmbedding: FloatArray? = null
    ): RetrievalResult {
        val embedding = precomputedEmbedding ?: cachedEmbeddingService.embed(question)
        return retrieve(embedding, collectionId, limit, scoreThreshold, filter)
    }

    fun retrieve(
        precomputedEmbedding: FloatArray,
        collectionId: String,
        limit: Int = 10,
        scoreThreshold: Float? = null,
        filter: VectorFilter? = null
    ): RetrievalResult {
        val searchResults = vectorStore.search(
            collection = collectionId,
            queryVector = precomputedEmbedding,
            limit = limit,
            scoreThreshold = scoreThreshold,
            filter = filter
        )

        if (logger.isDebugEnabled) {
            val scores = searchResults.map { it.score }
            logger.debug(
                "Vector search: collection={}, limit={}, threshold={} -> {} hits, score range=[{}..{}]",
                collectionId, limit, scoreThreshold,
                searchResults.size, scores.minOrNull(), scores.maxOrNull()
            )
        }

        return RetrievalResult(hits = searchResults, embedding = precomputedEmbedding)
    }
}
```

- Erster Overload: loest Embedding auf (cached oder frisch), delegiert an zweiten Overload.
- Zweiter Overload: fuehrt Vektor-Suche aus, loggt Score-Range, gibt `RetrievalResult` zurueck.
- Debug-Logging ist zentral — gilt fuer jeden Aufrufer (DocRAG, GraphRAG, kuenftige Features).

### Subsection 2: RetrievalResult-Datenklasse

```kotlin
data class RetrievalResult(
    val hits: List<SearchResult>,
    val embedding: FloatArray
) {
    fun distinctPayloadValues(key: String): List<String> =
        hits.mapNotNull { it.payload[key]?.toString() }.distinct()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RetrievalResult) return false
        return hits == other.hits && embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int = 31 * hits.hashCode() + embedding.contentHashCode()
}
```

- `distinctPayloadValues(key)` ersetzt das wiederkehrende `mapNotNull { it.payload[key]?.toString() }.distinct()`-Pattern in beiden RAG-Services.
- Custom `equals`/`hashCode` wegen `FloatArray` (Kotlin-`data class` vergleicht Arrays per Referenz).

### Subsection 3: NlpQueryService bleibt unveraendert

`NlpQueryService` nutzt `CachedEmbeddingService` direkt fuer sein parallel-async-Embedding-Pattern (Embedding wird vor den parallelen RAG-Aufrufen berechnet und als `precomputedEmbedding` an beide weitergereicht). Dieses Muster erfordert manuellen Zugriff auf den Embedding-Vektor, bevor das Retrieval gestartet wird — `VectorRetriever` waere hier ein unnuetzer Umweg.

## Betroffene Dateien

### Backend

| Datei                                                                                                 | Aenderung                                                                                |
|-------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/query/VectorRetriever.kt`                                   | NEU — zentraler Retrieval-Service mit Embed+Search+Logging                                |
| `src/main/kotlin/com/agentwork/graphmesh/query/RetrievalResult.kt`                                   | NEU — Ergebnis-Datenklasse mit `distinctPayloadValues` Helper                             |
| `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt`                         | `CachedEmbeddingService` + `VectorStore` durch `VectorRetriever` ersetzen; Score-Logging entfernen |
| `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`                          | `CachedEmbeddingService` + `VectorStore` durch `VectorRetriever` ersetzen                  |

### Frontend

Keine Aenderungen.

### Tests

| Datei                                                                                          | Aenderung                                                                                       |
|------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/query/VectorRetrieverTest.kt`                        | NEU — Unit-Test: Embedding-Aufloesung (mit/ohne precomputed), Score-Range-Logging, `distinctPayloadValues`, Delegation an `VectorStore.search` |
| `src/test/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagServiceTest.kt`              | Constructor-Mocks anpassen: `CachedEmbeddingService` + `VectorStore` durch `VectorRetriever` ersetzen |
| `src/test/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagServiceTest.kt`               | Analog anpassen                                                                                  |

## Akzeptanzkriterien

- [ ] `VectorRetriever` ist als `@Service` in `com.agentwork.graphmesh.query` registriert und per Constructor-Injection in beiden RAG-Services verfuegbar.
- [ ] `DocumentRagService` hat keine direkte Abhaengigkeit mehr auf `CachedEmbeddingService` oder `VectorStore` — nur noch auf `VectorRetriever`.
- [ ] `GraphRagService` hat keine direkte Abhaengigkeit mehr auf `CachedEmbeddingService` oder `VectorStore` — nur noch auf `VectorRetriever`.
- [ ] Score-Range-Debug-Logging erscheint bei beiden RAG-Modi (verifiziert durch Test mit `DEBUG`-Level).
- [ ] `precomputedEmbedding`-Pfad funktioniert: wenn ein Embedding uebergeben wird, ruft `VectorRetriever` `CachedEmbeddingService.embed()` nicht auf.
- [ ] `RetrievalResult.distinctPayloadValues(key)` liefert deduplizierte Payload-Werte fuer beliebige Keys.
- [ ] `NlpQueryService` behaelt seine direkte `CachedEmbeddingService`-Dependency (kein Umbau).
- [ ] API-Verhalten beider RAG-Services bleibt identisch (gleiche Ergebnisse fuer gleiche Queries).
- [ ] Bestehende Tests von Feature 15 / 16 / 18 / 45 bleiben gruen.
- [ ] Bestehende Funktionalitaet bleibt unberuehrt.
