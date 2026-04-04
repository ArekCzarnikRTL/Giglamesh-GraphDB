# Feature 16: Document RAG

## Problem

Benutzer muessen in grossen Dokumentensammlungen nach Informationen suchen, erhalten aber nur Vektor-Suchergebnisse (
Chunk-IDs und Scores) ohne natuerlichsprachige Zusammenfassung. Ohne eine RAG-Pipeline, die relevante Chunks abruft, dem
LLM als Kontext uebergibt und eine quellenbasierte Antwort generiert, muessen Benutzer die gefundenen Textabschnitte
selbst durchlesen und interpretieren. Ausserdem fehlt die Rueckverfolgbarkeit zum Originaldokument und zur Seitennummer.

## Ziel

Implementierung einer Document-RAG-Pipeline, die semantische Suche ueber Chunk-Embeddings in Qdrant durchfuehrt,
relevante Chunks per Top-K-Retrieval abruft und eine LLM-basierte Synthese mit Quellenangaben generiert.

1. **Chunk Retrieval** -- Semantische Suche ueber Qdrant mit konfigurierbarem Top-K
2. **LLM-Synthese** -- Generierung einer natuerlichsprachigen Antwort aus den abgerufenen Chunks
3. **Source Attribution** -- Rueckverfolgung jedes Chunks zum Originaldokument und zur Seite
4. **GraphQL-Integration** -- Exponierung als GraphQL-Query mit Streaming-Unterstuetzung
5. **Konfigurierbarkeit** -- Einstellbare Parameter fuer K, Similarity-Threshold und Chunk-Kontext

## Voraussetzungen

| Abhaengigkeit                                                                | Status     | Blocker? |
|------------------------------------------------------------------------------|------------|----------|
| Feature 04: Qdrant Vector Store (VectorStore)                                | Geplant    | Ja       |
| Feature 05: LLM Provider Abstraction (ChatCompletionService)                 | Geplant    | Ja       |
| Feature 09: Document Management (LibrarianService, Document)                 | Geplant    | Ja       |
| Feature 13: Document Embeddings (EmbeddingService, Chunk-Vektoren in Qdrant) | Geplant    | Ja       |
| Feature 14: GraphQL API (Schema-first, Controller-Infrastruktur)             | Geplant    | Ja       |
| Spring Boot Starter GraphQL                                                  | Verfuegbar | Nein     |

## Architektur

### Datenmodell

```kotlin
package com.graphmesh.query.docrag

import java.util.UUID

/**
 * Eingabe-Query fuer die Document-RAG-Pipeline.
 */
data class DocumentRagQuery(
    /** Natuerlichsprachige Frage des Benutzers. */
    val question: String,
    /** Collection, in der gesucht werden soll. */
    val collectionId: UUID,
    /** Anzahl der abzurufenden Top-K Chunks. */
    val topK: Int = 10,
    /** Minimaler Similarity-Score (0.0 - 1.0). Chunks unterhalb werden gefiltert. */
    val similarityThreshold: Double = 0.5,
    /** Ob Streaming aktiviert werden soll. */
    val streaming: Boolean = false
)

/**
 * Ergebnis der Document-RAG-Pipeline mit Quellenangaben.
 */
data class DocumentRagResult(
    /** Die generierte natuerlichsprachige Antwort. */
    val answer: String,
    /** Quellenangaben fuer die verwendeten Chunks. */
    val sources: List<SourceAttribution>,
    /** Gesamtzahl der abgerufenen Chunks. */
    val retrievedChunkCount: Int,
    /** Dauer der Pipeline-Ausfuehrung in Millisekunden. */
    val durationMs: Long
)

/**
 * Quellenangabe: verknuepft einen verwendeten Chunk mit dem Originaldokument.
 */
data class SourceAttribution(
    /** ID des Chunks in Qdrant. */
    val chunkId: String,
    /** ID des Originaldokuments. */
    val documentId: String,
    /** Titel des Originaldokuments. */
    val documentTitle: String,
    /** Seitennummer im Originaldokument (falls verfuegbar). */
    val pageNumber: Int?,
    /** Similarity-Score des Chunks zur Frage. */
    val score: Double,
    /** Textauszug des Chunks (gekuerzt). */
    val snippet: String
)
```

### Service-Interfaces

```kotlin
package com.graphmesh.query.docrag

import kotlinx.coroutines.flow.Flow

/**
 * Hauptservice fuer die Document-RAG-Pipeline.
 * Orchestriert Chunk-Retrieval und LLM-Synthese.
 */
interface DocumentRagService {

    /**
     * Fuehrt die vollstaendige Document-RAG-Pipeline aus.
     *
     * @param query Die Benutzeranfrage mit Konfigurationsparametern.
     * @return Das Ergebnis mit Antwort und Quellenangaben.
     */
    suspend fun query(query: DocumentRagQuery): DocumentRagResult

    /**
     * Fuehrt die Pipeline mit Streaming-Antwort aus.
     * Retrieval laeuft blockierend, die Synthese wird gestreamt.
     *
     * @param query Die Benutzeranfrage.
     * @return Flow von Antwort-Tokens.
     */
    fun queryStreaming(query: DocumentRagQuery): Flow<String>
}

/**
 * Abruf relevanter Chunks via semantischer Suche in Qdrant.
 */
interface ChunkRetriever {

    /**
     * Sucht die relevantesten Chunks fuer eine Frage.
     *
     * @param question Die natuerlichsprachige Frage.
     * @param collectionId Die Ziel-Collection.
     * @param topK Maximale Anzahl abzurufender Chunks.
     * @param similarityThreshold Minimaler Similarity-Score.
     * @return Liste der abgerufenen Chunks mit Metadaten.
     */
    suspend fun retrieve(
        question: String,
        collectionId: java.util.UUID,
        topK: Int,
        similarityThreshold: Double
    ): List<RetrievedChunk>
}

/**
 * Ein aus Qdrant abgerufener Chunk mit Metadaten.
 */
data class RetrievedChunk(
    val chunkId: String,
    val documentId: String,
    val pageNumber: Int?,
    val text: String,
    val score: Double
)

/**
 * LLM-basierte Synthese einer Antwort aus abgerufenen Chunks.
 */
interface DocumentSynthesizer {

    /**
     * Synthetisiert eine Antwort aus den abgerufenen Chunks.
     *
     * @param question Die natuerlichsprachige Frage.
     * @param chunks Die abgerufenen Chunks als Kontext.
     * @return Die generierte Antwort.
     */
    suspend fun synthesize(
        question: String,
        chunks: List<RetrievedChunk>
    ): String

    /**
     * Streaming-Variante der Synthese.
     *
     * @param question Die natuerlichsprachige Frage.
     * @param chunks Die abgerufenen Chunks als Kontext.
     * @return Flow von Antwort-Tokens.
     */
    fun synthesizeStreaming(
        question: String,
        chunks: List<RetrievedChunk>
    ): Flow<String>
}
```

### DefaultDocumentRagService

```kotlin
package com.graphmesh.query.docrag

import com.graphmesh.librarian.LibrarianService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Standard-Implementierung der Document-RAG-Pipeline.
 *
 * Ablauf:
 * 1. ChunkRetriever sucht relevante Chunks via Embedding-Similarity in Qdrant
 * 2. DocumentSynthesizer generiert eine Antwort mit LLM-basierter Synthese
 * 3. Quellenangaben werden aus Chunk-Metadaten und LibrarianService aufgebaut
 */
@Service
class DefaultDocumentRagService(
    private val chunkRetriever: ChunkRetriever,
    private val documentSynthesizer: DocumentSynthesizer,
    private val librarianService: LibrarianService
) : DocumentRagService {

    private val logger = LoggerFactory.getLogger(DefaultDocumentRagService::class.java)

    override suspend fun query(query: DocumentRagQuery): DocumentRagResult {
        val startTime = System.currentTimeMillis()

        // Phase 1: Chunk Retrieval
        logger.info("Chunk-Retrieval fuer Collection {}, topK={}", query.collectionId, query.topK)
        val chunks = chunkRetriever.retrieve(
            question = query.question,
            collectionId = query.collectionId,
            topK = query.topK,
            similarityThreshold = query.similarityThreshold
        )
        logger.info("{} Chunks abgerufen", chunks.size)

        // Phase 2: LLM-Synthese
        logger.info("Starte LLM-Synthese aus {} Chunks", chunks.size)
        val answer = documentSynthesizer.synthesize(
            question = query.question,
            chunks = chunks
        )

        // Quellenangaben aufbauen
        val sources = chunks.map { chunk ->
            val doc = try {
                librarianService.getDocument(chunk.documentId)
            } catch (e: Exception) {
                null
            }

            SourceAttribution(
                chunkId = chunk.chunkId,
                documentId = chunk.documentId,
                documentTitle = doc?.title ?: "Unbekannt",
                pageNumber = chunk.pageNumber,
                score = chunk.score,
                snippet = chunk.text.take(200)
            )
        }

        val durationMs = System.currentTimeMillis() - startTime
        logger.info("Document-RAG-Pipeline abgeschlossen in {} ms", durationMs)

        return DocumentRagResult(
            answer = answer,
            sources = sources,
            retrievedChunkCount = chunks.size,
            durationMs = durationMs
        )
    }

    override fun queryStreaming(query: DocumentRagQuery): Flow<String> = flow {
        val chunks = chunkRetriever.retrieve(
            question = query.question,
            collectionId = query.collectionId,
            topK = query.topK,
            similarityThreshold = query.similarityThreshold
        )

        documentSynthesizer.synthesizeStreaming(
            question = query.question,
            chunks = chunks
        ).collect { token -> emit(token) }
    }
}
```

### ChunkRetriever-Implementierung

```kotlin
package com.graphmesh.query.docrag

import com.graphmesh.llm.EmbeddingService
import com.graphmesh.storage.qdrant.VectorStore
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Standard-Implementierung des ChunkRetrievers.
 * Vektorisiert die Frage und sucht aehnliche Chunks in Qdrant.
 */
@Component
class DefaultChunkRetriever(
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore
) : ChunkRetriever {

    override suspend fun retrieve(
        question: String,
        collectionId: UUID,
        topK: Int,
        similarityThreshold: Double
    ): List<RetrievedChunk> {
        val queryVector = embeddingService.embed(question)

        val results = vectorStore.search(
            collection = "doc-embeddings-$collectionId",
            vector = queryVector,
            limit = topK
        )

        return results
            .filter { it.score >= similarityThreshold }
            .map { result ->
                RetrievedChunk(
                    chunkId = result.payload["chunk_id"] ?: "",
                    documentId = result.payload["document_id"] ?: "",
                    pageNumber = result.payload["page_number"]?.toIntOrNull(),
                    text = result.payload["text"] ?: "",
                    score = result.score.toDouble()
                )
            }
    }
}
```

### GraphQL-Integration

```graphql
# api/src/main/resources/graphql/document-rag.graphqls

extend type Query {
    documentRag(input: DocumentRagInput!): DocumentRagResponse!
}

input DocumentRagInput {
    question: String!
    collectionId: ID!
    topK: Int = 10
    similarityThreshold: Float = 0.5
}

type DocumentRagResponse {
    answer: String!
    sources: [SourceAttributionType!]!
    retrievedChunkCount: Int!
    durationMs: Long!
}

type SourceAttributionType {
    chunkId: String!
    documentId: String!
    documentTitle: String!
    pageNumber: Int
    score: Float!
    snippet: String!
}
```

### DocumentRagController

```kotlin
package com.graphmesh.api.graphql

import com.graphmesh.query.docrag.DocumentRagQuery
import com.graphmesh.query.docrag.DocumentRagResult
import com.graphmesh.query.docrag.DocumentRagService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import java.util.UUID

@Controller
class DocumentRagController(
    private val documentRagService: DocumentRagService
) {

    @QueryMapping
    suspend fun documentRag(@Argument input: DocumentRagInput): DocumentRagResult {
        val query = DocumentRagQuery(
            question = input.question,
            collectionId = UUID.fromString(input.collectionId),
            topK = input.topK ?: 10,
            similarityThreshold = input.similarityThreshold?.toDouble() ?: 0.5
        )
        return documentRagService.query(query)
    }
}

data class DocumentRagInput(
    val question: String,
    val collectionId: String,
    val topK: Int?,
    val similarityThreshold: Float?
)
```

## Betroffene Dateien

### Backend

| Datei                                                                           | Aenderung                                                      |
|---------------------------------------------------------------------------------|----------------------------------------------------------------|
| `query/src/main/kotlin/com/graphmesh/query/docrag/DocumentRagQuery.kt`          | NEU - Query-Datenmodell                                        |
| `query/src/main/kotlin/com/graphmesh/query/docrag/DocumentRagResult.kt`         | NEU - Ergebnis-Datenmodell                                     |
| `query/src/main/kotlin/com/graphmesh/query/docrag/SourceAttribution.kt`         | NEU - Quellenangabe-Datenmodell                                |
| `query/src/main/kotlin/com/graphmesh/query/docrag/DocumentRagService.kt`        | NEU - Service-Interface                                        |
| `query/src/main/kotlin/com/graphmesh/query/docrag/ChunkRetriever.kt`            | NEU - Retrieval-Interface                                      |
| `query/src/main/kotlin/com/graphmesh/query/docrag/DocumentSynthesizer.kt`       | NEU - Synthese-Interface                                       |
| `query/src/main/kotlin/com/graphmesh/query/docrag/RetrievedChunk.kt`            | NEU - Chunk-Datenmodell                                        |
| `query/src/main/kotlin/com/graphmesh/query/docrag/DefaultDocumentRagService.kt` | NEU - Pipeline-Orchestrierung                                  |
| `query/src/main/kotlin/com/graphmesh/query/docrag/DefaultChunkRetriever.kt`     | NEU - Qdrant-basiertes Retrieval                               |
| `query/src/main/kotlin/com/graphmesh/query/docrag/LlmDocumentSynthesizer.kt`    | NEU - LLM-basierte Synthese                                    |
| `api/src/main/resources/graphql/document-rag.graphqls`                          | NEU - GraphQL-Schema-Erweiterung                               |
| `api/src/main/kotlin/com/graphmesh/api/graphql/DocumentRagController.kt`        | NEU - GraphQL-Controller                                       |
| `query/build.gradle.kts`                                                        | AENDERUNG - Abhaengigkeiten auf storage, llm, librarian Module |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                               | Aenderung                                                 |
|-------------------------------------------------------------------------------------|-----------------------------------------------------------|
| `query/src/test/kotlin/com/graphmesh/query/docrag/DefaultDocumentRagServiceTest.kt` | NEU - Unit-Tests fuer Pipeline-Orchestrierung             |
| `query/src/test/kotlin/com/graphmesh/query/docrag/DefaultChunkRetrieverTest.kt`     | NEU - Tests fuer Qdrant-Retrieval und Threshold-Filterung |
| `query/src/test/kotlin/com/graphmesh/query/docrag/LlmDocumentSynthesizerTest.kt`    | NEU - Tests fuer LLM-Synthese mit Mock                    |
| `api/src/test/kotlin/com/graphmesh/api/graphql/DocumentRagControllerTest.kt`        | NEU - GraphQL-Integration-Tests                           |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                                               |
|-------------------|-------------|---------------------------------------------------------------------|
| Spring Boot (JVM) | Ja          | Spring Boot GraphQL + Qdrant-Client Integration                     |
| KMP Library       | Nein        | Abhaengigkeit von Spring-spezifischen Annotations und Qdrant-Client |
| Ktor/Wasm         | Nein        | Spring Boot GraphQL ist JVM-spezifisch                              |

## Akzeptanzkriterien

- [ ] GraphQL-Query `documentRag` nimmt eine natuerlichsprachige Frage und Collection-ID entgegen
- [ ] Chunk-Retrieval vektorisiert die Frage und sucht Top-K aehnliche Chunks in Qdrant
- [ ] Chunks unterhalb des konfigurierbaren Similarity-Thresholds werden herausgefiltert
- [ ] LLM-Synthese generiert eine Antwort ausschliesslich auf Basis der abgerufenen Chunks
- [ ] Jede Quellenangabe enthaelt Dokument-ID, Titel, Seitennummer (falls vorhanden) und Score
- [ ] Snippet-Feld in der Quellenangabe zeigt einen getrunkten Textauszug (max. 200 Zeichen)
- [ ] Streaming-Modus liefert Antwort-Tokens als Flow aus der Synthese-Phase
- [ ] Top-K Parameter ist konfigurierbar (Standard: 10)
- [ ] Antwort enthaelt `retrievedChunkCount` und `durationMs` als Metriken
- [ ] Bei leerer Collection oder keinem Treffer wird eine aussagekraeftige Meldung zurueckgegeben
