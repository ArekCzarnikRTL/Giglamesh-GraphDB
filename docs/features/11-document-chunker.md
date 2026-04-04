# Feature 11: Document Chunker

## Problem

Extrahierter Text (aus PDF-Seiten oder direkt aus Textdokumenten) muss in kleinere, ueberlappende Abschnitte (Chunks)
zerlegt werden, bevor er fuer Embedding-Generierung oder Wissensextraktion verwendet werden kann. Ohne konfigurierbare
Chunk-Groesse und Overlap-Parameter fehlt die Kontrolle ueber die Granularitaet der nachfolgenden Verarbeitungsschritte.
Zu grosse Chunks ueberfordern LLM-Kontextfenster, zu kleine verlieren semantischen Zusammenhang.

## Ziel

Implementierung eines Kafka-basierten Document Chunkers, der Text in ueberlappende Chunks konfigurierbarer Groesse
zerlegt, als Kind-Dokumente im Librarian speichert und Chunk-Created-Events publiziert.

1. **Kafka-Consumer** -- Empfaengt `page.extracted`-Events vom PDF-Decoder (Feature 10)
2. **Konfigurierbares Chunking** -- `ChunkConfig` mit `chunkSize` und `overlapSize` Parametern
3. **Ueberlappende Chunks** -- Overlap-Bereich sichert Kontextkontinuitaet an Chunk-Grenzen
4. **Kind-Dokumente** -- Pro Chunk ein Kind-Dokument im Librarian (Feature 09)
5. **Event-Publishing** -- Publiziert `chunk.created`-Events fuer Relationship Extractor (Feature 12) und Embeddings (
   Feature 13)

## Voraussetzungen

| Abhaengigkeit                                           | Status     | Blocker? |
|---------------------------------------------------------|------------|----------|
| Feature 01: Kafka Messaging Infrastructure              | Geplant    | Ja       |
| Feature 09: Document Management (Librarian)             | Geplant    | Ja       |
| Feature 10: PDF Decoder (liefert page.extracted Events) | Geplant    | Ja       |
| Spring Boot 3.x                                         | Verfuegbar | Nein     |

## Architektur

### ChunkConfig

```kotlin
package com.graphmesh.extraction.chunker

/**
 * Konfiguration fuer den Document Chunker.
 *
 * Standard-Werte orientieren sich an gaengigen RAG-Pipelines:
 * - chunkSize: 2000 Zeichen (passend fuer die meisten LLM-Kontextfenster)
 * - overlapSize: 200 Zeichen (10% Overlap fuer Kontextkontinuitaet)
 */
data class ChunkConfig(
    val chunkSize: Int = 2000,
    val overlapSize: Int = 200
) {
    init {
        require(chunkSize > 0) { "chunkSize muss positiv sein: $chunkSize" }
        require(overlapSize >= 0) { "overlapSize darf nicht negativ sein: $overlapSize" }
        require(overlapSize < chunkSize) {
            "overlapSize ($overlapSize) muss kleiner als chunkSize ($chunkSize) sein"
        }
    }
}
```

### ChunkerService

```kotlin
package com.graphmesh.extraction.chunker

import com.graphmesh.librarian.LibrarianService
import com.graphmesh.librarian.DocumentType
import com.graphmesh.messaging.MessageProducer
import java.util.UUID

/**
 * Zerlegt Text in ueberlappende Chunks und speichert sie als Kind-Dokumente.
 */
class ChunkerService(
    private val librarianService: LibrarianService,
    private val eventProducer: MessageProducer<ChunkCreatedEvent>,
    private val config: ChunkConfig = ChunkConfig()
) {

    /**
     * Verarbeitet ein extrahiertes Dokument (Page oder Text).
     *
     * 1. Text vom Librarian laden
     * 2. In ueberlappende Chunks zerlegen
     * 3. Pro Chunk ein Kind-Dokument erstellen
     * 4. chunk.created Events publizieren
     */
    suspend fun chunkDocument(documentId: String, collectionId: UUID) {
        val content = librarianService.getContent(documentId)
        val text = content.toString(Charsets.UTF_8)

        if (text.isBlank()) return

        val chunks = splitIntoChunks(text)

        for ((index, chunkResult) in chunks.withIndex()) {
            val chunkDoc = librarianService.createChildDocument(
                parentId = documentId,
                type = DocumentType.CHUNK,
                title = "Chunk ${index + 1}",
                content = chunkResult.text.toByteArray(Charsets.UTF_8),
                mimeType = "text/plain"
            )

            eventProducer.send(
                ChunkCreatedEvent(
                    chunkId = chunkDoc.id,
                    documentId = documentId,
                    collectionId = collectionId,
                    chunkIndex = index,
                    charOffset = chunkResult.charOffset,
                    charLength = chunkResult.text.length
                )
            )
        }
    }

    /**
     * Zerlegt Text in ueberlappende Chunks.
     *
     * Der Algorithmus verwendet ein Sliding-Window mit konfiguriertem
     * Overlap, sodass Kontextinformationen an Chunk-Grenzen erhalten bleiben.
     */
    internal fun splitIntoChunks(text: String): List<ChunkResult> {
        val chunks = mutableListOf<ChunkResult>()
        val step = config.chunkSize - config.overlapSize
        var offset = 0

        while (offset < text.length) {
            val end = minOf(offset + config.chunkSize, text.length)
            val chunkText = text.substring(offset, end)

            chunks.add(
                ChunkResult(
                    text = chunkText,
                    charOffset = offset,
                    chunkIndex = chunks.size
                )
            )

            offset += step

            // Vermeide winzige Rest-Chunks
            if (offset < text.length && text.length - offset < config.overlapSize) {
                break
            }
        }

        // Restlichen Text als letzten Chunk hinzufuegen
        if (offset < text.length && chunks.lastOrNull()?.let {
                it.charOffset + it.text.length < text.length
            } == true) {
            chunks.add(
                ChunkResult(
                    text = text.substring(offset),
                    charOffset = offset,
                    chunkIndex = chunks.size
                )
            )
        }

        return chunks
    }
}
```

### ChunkerConsumer

```kotlin
package com.graphmesh.extraction.chunker

import com.graphmesh.extraction.decoder.PageExtractedEvent
import com.graphmesh.messaging.MessageConsumer

/**
 * Kafka-Consumer fuer den Chunker.
 * Lauscht auf page.extracted und text.loaded Events.
 */
class ChunkerConsumer(
    private val pageConsumer: MessageConsumer<PageExtractedEvent>,
    private val chunkerService: ChunkerService
) {
    fun start() {
        pageConsumer.subscribe { message ->
            val event = message.payload
            chunkerService.chunkDocument(
                documentId = event.documentId,
                collectionId = event.collectionId
            )
        }
    }
}
```

### Datenmodelle

```kotlin
package com.graphmesh.extraction.chunker

import java.util.UUID

/**
 * Ergebnis eines einzelnen Chunks.
 */
data class ChunkResult(
    val text: String,
    val charOffset: Int,
    val chunkIndex: Int
)

/**
 * Ausgehendes Event: Chunk erstellt.
 * Topic: graphmesh.chunk.created
 *
 * Wird von Feature 12 (Relationship Extractor) und
 * Feature 13 (Document Embeddings) konsumiert.
 */
data class ChunkCreatedEvent(
    val chunkId: String,
    val documentId: String,
    val collectionId: UUID,
    val chunkIndex: Int,
    val charOffset: Int,
    val charLength: Int
)
```

### Kafka-Topics

| Topic                      | Richtung  | Schema               |
|----------------------------|-----------|----------------------|
| `graphmesh.page.extracted` | Eingehend | `PageExtractedEvent` |
| `graphmesh.chunk.created`  | Ausgehend | `ChunkCreatedEvent`  |

## Betroffene Dateien

### Backend

| Datei                                                                              | Aenderung                                               |
|------------------------------------------------------------------------------------|---------------------------------------------------------|
| `extraction/src/main/kotlin/com/graphmesh/extraction/chunker/ChunkerService.kt`    | NEU - Chunk-Logik mit Overlap                           |
| `extraction/src/main/kotlin/com/graphmesh/extraction/chunker/ChunkerConsumer.kt`   | NEU - Kafka-Consumer fuer Page-Events                   |
| `extraction/src/main/kotlin/com/graphmesh/extraction/chunker/ChunkConfig.kt`       | NEU - Konfigurationsparameter                           |
| `extraction/src/main/kotlin/com/graphmesh/extraction/chunker/ChunkResult.kt`       | NEU - Chunk-Ergebnis-Datenklasse                        |
| `extraction/src/main/kotlin/com/graphmesh/extraction/chunker/ChunkCreatedEvent.kt` | NEU - Ausgehendes Kafka-Event                           |
| `extraction/build.gradle.kts`                                                      | AENDERUNG - Keine neuen Abhaengigkeiten (reines Kotlin) |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                | Aenderung                                     |
|--------------------------------------------------------------------------------------|-----------------------------------------------|
| `extraction/src/test/kotlin/com/graphmesh/extraction/chunker/ChunkerServiceTest.kt`  | NEU - Unit-Tests fuer Chunk-Algorithmus       |
| `extraction/src/test/kotlin/com/graphmesh/extraction/chunker/ChunkConfigTest.kt`     | NEU - Validierung der Konfigurationsparameter |
| `extraction/src/test/kotlin/com/graphmesh/extraction/chunker/ChunkerConsumerTest.kt` | NEU - Kafka-Consumer-Integration              |
| `extraction/src/test/kotlin/com/graphmesh/extraction/chunker/OverlapTest.kt`         | NEU - Overlap-Verhalten und Randfaelle        |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                               |
|-------------------|-------------|-------------------------------------|
| Spring Boot (JVM) | Ja          | Reines Kotlin mit Kafka-Consumer    |
| KMP Library       | Nein        | Abhaengigkeit zu Kafka-Client (JVM) |
| Ktor/Wasm         | Nein        | Kafka-Client ist JVM-spezifisch     |

## Akzeptanzkriterien

- [ ] Chunker empfaengt `page.extracted`-Events und zerlegt den zugehoerigen Text
- [ ] Chunk-Groesse und Overlap sind ueber `ChunkConfig` konfigurierbar (Defaults: 2000/200)
- [ ] Chunks ueberlappen korrekt: Ende von Chunk N == Anfang von Chunk N+1 (minus Overlap)
- [ ] Pro Chunk wird ein Kind-Dokument im Librarian erstellt mit ID-Schema `{parentId}/c{index}`
- [ ] Pro Chunk wird ein `chunk.created`-Event auf Kafka publiziert
- [ ] `charOffset` und `charLength` im Event sind korrekt relativ zum Quelltext
- [ ] Leere Texte (nur Whitespace) erzeugen keine Chunks
- [ ] Sehr kurze Texte (kuerzer als chunkSize) erzeugen genau einen Chunk
- [ ] `ChunkConfig`-Validierung: chunkSize > 0, overlapSize >= 0, overlapSize < chunkSize
- [ ] Kein Rest-Chunk kleiner als overlapSize (wird an den vorherigen Chunk angehaengt)
