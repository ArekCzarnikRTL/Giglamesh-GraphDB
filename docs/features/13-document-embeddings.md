# Feature 13: Document Embeddings

## Problem

Textchunks muessen als Vektoren in einer Vektordatenbank gespeichert werden, damit spaetere semantische Suchen (
Nearest-Neighbor-Queries) moeglich sind. Ohne eine Embedding-Pipeline fehlt die Grundlage fuer Document-RAG, bei dem
relevante Textabschnitte anhand ihrer semantischen Aehnlichkeit zur Benutzeranfrage gefunden werden. Die Verarbeitung
muss effizient in Batches erfolgen und die Qdrant-Collection lazily (dimensionsabhaengig) erstellen.

## Ziel

Implementierung eines Kafka-basierten Embedding-Consumers, der Textchunks ueber den EmbeddingService vektorisiert, in
Batches verarbeitet und mit Chunk-ID-Tracking in Qdrant speichert.

1. **Kafka-Consumer** -- Empfaengt `chunk.created`-Events vom Chunker (Feature 11)
2. **Batch-Verarbeitung** -- Sammelt Chunks und verarbeitet sie in konfigurierbaren Batches fuer optimale Throughput
3. **Embedding-Generierung** -- Verwendet `EmbeddingService` (Feature 05) zur Vektorisierung
4. **Qdrant-Speicherung** -- Speichert Vektoren mit Chunk-ID als Payload in Qdrant (Feature 04)
5. **Lazy Collection Creation** -- Erstellt Qdrant-Collection beim ersten Embedding mit korrekter Dimension

## Voraussetzungen

| Abhaengigkeit                                               | Status     | Blocker? |
|-------------------------------------------------------------|------------|----------|
| Feature 01: Kafka Messaging Infrastructure                  | Geplant    | Ja       |
| Feature 04: Qdrant Vector Store (VectorStore)               | Geplant    | Ja       |
| Feature 05: LLM Provider Abstraction (EmbeddingService)     | Geplant    | Ja       |
| Feature 11: Document Chunker (liefert chunk.created Events) | Geplant    | Ja       |
| Spring Boot 3.x                                             | Verfuegbar | Nein     |

## Architektur

### EmbeddingConfig

```kotlin
package com.graphmesh.extraction.embedding

/**
 * Konfiguration fuer die Embedding-Verarbeitung.
 */
data class EmbeddingConfig(
    /**
     * Anzahl der Chunks pro Batch. Groessere Batches verbessern
     * den Throughput, erhoehen aber den Speicherverbrauch.
     */
    val batchSize: Int = 32,

    /**
     * Maximale Wartezeit in Millisekunden, bevor ein unvollstaendiger
     * Batch verarbeitet wird.
     */
    val batchTimeoutMs: Long = 5000,

    /**
     * Timeout fuer einzelne Embedding-Requests in Sekunden.
     */
    val requestTimeoutSeconds: Long = 300
) {
    init {
        require(batchSize in 1..128) { "batchSize muss zwischen 1 und 128 liegen: $batchSize" }
        require(batchTimeoutMs > 0) { "batchTimeoutMs muss positiv sein: $batchTimeoutMs" }
    }
}
```

### EmbeddingBatchProcessor

```kotlin
package com.graphmesh.extraction.embedding

import com.graphmesh.llm.EmbeddingService
import com.graphmesh.storage.qdrant.VectorStore
import com.graphmesh.librarian.LibrarianService
import java.util.UUID

/**
 * Verarbeitet Embedding-Requests in Batches fuer optimalen Throughput.
 *
 * Statt jeden Chunk einzeln zu vektorisieren, sammelt der Processor
 * mehrere Chunks und sendet sie als Batch an den EmbeddingService.
 * Das reduziert den Overhead pro Text um den Faktor 5-10x.
 */
class EmbeddingBatchProcessor(
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore,
    private val librarianService: LibrarianService,
    private val config: EmbeddingConfig = EmbeddingConfig()
) {

    private var collectionInitialized = false
    private var vectorDimension: Int? = null

    /**
     * Verarbeitet eine Liste von Chunks als Batch.
     *
     * 1. Texte aus dem Librarian laden
     * 2. Batch-Embedding generieren
     * 3. Qdrant-Collection bei Bedarf erstellen (Lazy Init)
     * 4. Vektoren mit Chunk-ID in Qdrant speichern
     */
    suspend fun processBatch(
        chunks: List<ChunkEmbeddingRequest>,
        collectionId: UUID
    ) {
        if (chunks.isEmpty()) return

        // Texte laden
        val texts = chunks.map { chunk ->
            val content = librarianService.getContent(chunk.chunkId)
            content.toString(Charsets.UTF_8)
        }

        // Batch-Embedding generieren
        val vectors = embeddingService.embedBatch(texts)

        // Lazy Collection Creation beim ersten Embedding
        ensureCollectionExists(collectionId, vectors.first().size)

        // Vektoren in Qdrant speichern mit Chunk-ID Payload
        val points = chunks.zip(vectors).map { (chunk, vector) ->
            VectorPoint(
                id = chunk.chunkId,
                vector = vector,
                payload = mapOf(
                    "chunk_id" to chunk.chunkId,
                    "document_id" to chunk.documentId,
                    "collection_id" to collectionId.toString()
                )
            )
        }

        vectorStore.upsertBatch(
            collection = "doc-embeddings-${collectionId}",
            points = points
        )
    }

    /**
     * Erstellt die Qdrant-Collection beim ersten Embedding.
     * Die Dimension wird aus dem ersten Vektor abgeleitet.
     */
    private suspend fun ensureCollectionExists(collectionId: UUID, dimension: Int) {
        if (collectionInitialized && vectorDimension == dimension) return

        val collectionName = "doc-embeddings-${collectionId}"

        if (!vectorStore.collectionExists(collectionName)) {
            vectorStore.createCollection(
                name = collectionName,
                dimension = dimension
            )
        }

        vectorDimension = dimension
        collectionInitialized = true
    }
}
```

### EmbeddingConsumer

```kotlin
package com.graphmesh.extraction.embedding

import com.graphmesh.extraction.chunker.ChunkCreatedEvent
import com.graphmesh.messaging.MessageConsumer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Kafka-Consumer fuer Document Embeddings.
 * Sammelt chunk.created Events und verarbeitet sie in Batches.
 */
class EmbeddingConsumer(
    private val consumer: MessageConsumer<ChunkCreatedEvent>,
    private val batchProcessor: EmbeddingBatchProcessor,
    private val config: EmbeddingConfig = EmbeddingConfig()
) {
    private val buffer = mutableListOf<ChunkEmbeddingRequest>()
    private val mutex = Mutex()
    private var currentCollectionId: UUID? = null

    fun start() {
        consumer.subscribe { message ->
            val event = message.payload
            val request = ChunkEmbeddingRequest(
                chunkId = event.chunkId,
                documentId = event.documentId,
                collectionId = event.collectionId
            )

            mutex.withLock {
                buffer.add(request)
                currentCollectionId = event.collectionId

                // Batch voll -> verarbeiten
                if (buffer.size >= config.batchSize) {
                    flushBuffer()
                }
            }
        }
    }

    /**
     * Verarbeitet den aktuellen Buffer als Batch.
     * Wird aufgerufen wenn der Buffer voll ist oder das Timeout ablaeuft.
     */
    private suspend fun flushBuffer() {
        if (buffer.isEmpty()) return

        val batch = buffer.toList()
        val collectionId = currentCollectionId ?: return
        buffer.clear()

        batchProcessor.processBatch(batch, collectionId)
    }
}
```

### Datenmodelle

```kotlin
package com.graphmesh.extraction.embedding

import java.util.UUID

/**
 * Interner Request fuer Chunk-Embedding.
 */
data class ChunkEmbeddingRequest(
    val chunkId: String,
    val documentId: String,
    val collectionId: UUID
)

/**
 * Ein Punkt im Vektorraum mit Payload-Metadaten.
 */
data class VectorPoint(
    val id: String,
    val vector: List<Float>,
    val payload: Map<String, String>
)
```

### Kafka-Topics

| Topic                     | Richtung  | Schema              |
|---------------------------|-----------|---------------------|
| `graphmesh.chunk.created` | Eingehend | `ChunkCreatedEvent` |

### Sequenzdiagramm

```
chunk.created Event
       |
       v
EmbeddingConsumer (sammelt in Buffer)
       |
       | (Buffer voll oder Timeout)
       v
EmbeddingBatchProcessor
       |
       |--> EmbeddingService.embedBatch(texts)  --> LLM/FastEmbed
       |
       |--> VectorStore.upsertBatch(points)     --> Qdrant
       |
       |--> (Lazy) VectorStore.createCollection --> Qdrant
```

## Betroffene Dateien

### Backend

| Datei                                                                                      | Aenderung                                       |
|--------------------------------------------------------------------------------------------|-------------------------------------------------|
| `extraction/src/main/kotlin/com/graphmesh/extraction/embedding/EmbeddingConsumer.kt`       | NEU - Kafka-Consumer mit Batch-Buffering        |
| `extraction/src/main/kotlin/com/graphmesh/extraction/embedding/EmbeddingBatchProcessor.kt` | NEU - Batch-Verarbeitung und Qdrant-Speicherung |
| `extraction/src/main/kotlin/com/graphmesh/extraction/embedding/EmbeddingConfig.kt`         | NEU - Konfigurationsparameter                   |
| `extraction/src/main/kotlin/com/graphmesh/extraction/embedding/ChunkEmbeddingRequest.kt`   | NEU - Interner Request                          |
| `extraction/src/main/kotlin/com/graphmesh/extraction/embedding/VectorPoint.kt`             | NEU - Vektor-Datenklasse                        |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                          | Aenderung                                                       |
|------------------------------------------------------------------------------------------------|-----------------------------------------------------------------|
| `extraction/src/test/kotlin/com/graphmesh/extraction/embedding/EmbeddingBatchProcessorTest.kt` | NEU - Batch-Verarbeitungstests                                  |
| `extraction/src/test/kotlin/com/graphmesh/extraction/embedding/EmbeddingConsumerTest.kt`       | NEU - Buffer- und Flush-Logik                                   |
| `extraction/src/test/kotlin/com/graphmesh/extraction/embedding/EmbeddingConfigTest.kt`         | NEU - Konfigurations-Validierung                                |
| `extraction/src/test/kotlin/com/graphmesh/extraction/embedding/LazyCollectionCreationTest.kt`  | NEU - Qdrant-Collection wird erst bei erstem Embedding erstellt |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                         |
|-------------------|-------------|-----------------------------------------------|
| Spring Boot (JVM) | Ja          | Qdrant-Client, Kafka-Consumer, LLM-Client     |
| KMP Library       | Nein        | Abhaengigkeit zu JVM-spezifischen Clients     |
| Ktor/Wasm         | Nein        | Qdrant- und Kafka-Clients sind JVM-spezifisch |

## Akzeptanzkriterien

- [ ] Consumer empfaengt `chunk.created`-Events und sammelt sie in einem Buffer
- [ ] Batch-Verarbeitung wird ausgeloest bei vollem Buffer (konfigurierbare `batchSize`, Default: 32)
- [ ] Batch-Verarbeitung wird ausgeloest bei Timeout (konfigurierbare `batchTimeoutMs`, Default: 5000ms)
- [ ] `EmbeddingService.embedBatch()` wird mit allen Texten des Batches aufgerufen (nicht einzeln)
- [ ] Vektoren werden in Qdrant mit `chunk_id` als Payload gespeichert (nicht der Chunk-Text)
- [ ] Qdrant-Collection wird lazy beim ersten Embedding erstellt mit korrekter Vektordimension
- [ ] Collection-Name folgt dem Schema `doc-embeddings-{collectionId}`
- [ ] `EmbeddingConfig`-Validierung: batchSize zwischen 1 und 128
- [ ] Leere Batches werden ignoriert (kein Qdrant-Aufruf)
- [ ] Bei Embedding-Fehlern wird der Batch nicht teilweise gespeichert (Atomaritaet)
