# Feature 10: PDF Decoder

## Problem

PDF-Dokumente muessen in der Extraktionspipeline seitenweise in Text umgewandelt werden, bevor sie in Chunks zerlegt und
analysiert werden koennen. Ohne einen dedizierten PDF-Decoder muss die gesamte Text-Extraktion manuell oder monolithisch
erfolgen, was Skalierbarkeit und Fehlerbehandlung erschwert. Der Decoder muss als eigenstaendiger Kafka-Consumer
arbeiten, der auf Dokument-Events reagiert und die Ergebnisse als Kind-Dokumente (eine Seite = ein Dokument) im
Librarian ablegt.

## Ziel

Implementierung eines Kafka-basierten PDF-Decoders, der PDF-Dokumente seitenweise in Text extrahiert, pro Seite ein
Kind-Dokument im Librarian erstellt und Page-Extracted-Events publiziert.

1. **Kafka-Consumer** -- Empfaengt `document.uploaded`-Events fuer PDF-Dokumente
2. **Seitenweise Extraktion** -- Extrahiert Text pro Seite mit Apache PDFBox
3. **Kind-Dokumente** -- Erstellt pro Seite ein Kind-Dokument im Librarian (Feature 09)
4. **Event-Publishing** -- Publiziert `page.extracted`-Events fuer den Chunker (Feature 11)
5. **Streaming-Verarbeitung** -- Laedt PDFs ueber den Librarian, nicht vollstaendig in den Speicher

## Voraussetzungen

| Abhaengigkeit                               | Status     | Blocker? |
|---------------------------------------------|------------|----------|
| Feature 01: Kafka Messaging Infrastructure  | Geplant    | Ja       |
| Feature 09: Document Management (Librarian) | Geplant    | Ja       |
| Apache PDFBox 3.x                           | Verfuegbar | Nein     |
| Spring Boot 3.x                             | Verfuegbar | Nein     |

## Architektur

### PdfDecoderConsumer

```kotlin
package com.graphmesh.extraction.decoder

import com.graphmesh.messaging.MessageConsumer
import com.graphmesh.messaging.Message

/**
 * Kafka-Consumer fuer PDF-Dekodierung.
 * Lauscht auf graphmesh.document.uploaded Events mit mimeType=application/pdf.
 */
class PdfDecoderConsumer(
    private val consumer: MessageConsumer<DocumentUploadedEvent>,
    private val decoderService: PdfDecoderService
) {
    fun start() {
        consumer.subscribe { message ->
            val event = message.payload
            if (event.mimeType == "application/pdf") {
                decoderService.decodePdf(event.documentId, event.collectionId)
            }
        }
    }
}
```

### PdfDecoderService

```kotlin
package com.graphmesh.extraction.decoder

import com.graphmesh.librarian.LibrarianService
import com.graphmesh.librarian.DocumentType
import com.graphmesh.librarian.DocumentState
import com.graphmesh.messaging.MessageProducer
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID

/**
 * Extrahiert Text seitenweise aus PDF-Dokumenten.
 *
 * Ablauf:
 * 1. PDF-Inhalt vom Librarian laden (Stream zu Temp-Datei)
 * 2. PDFBox oeffnet die Temp-Datei
 * 3. Fuer jede Seite: Text extrahieren, als Kind-Dokument speichern
 * 4. Page-Extracted-Event publizieren
 * 5. Temp-Datei loeschen
 */
class PdfDecoderService(
    private val librarianService: LibrarianService,
    private val eventProducer: MessageProducer<PageExtractedEvent>
) {

    suspend fun decodePdf(documentId: String, collectionId: UUID) {
        // Zustand auf PROCESSING setzen
        librarianService.updateState(documentId, DocumentState.PROCESSING)

        try {
            // PDF-Inhalt laden
            val pdfContent = librarianService.getContent(documentId)

            // Temp-Datei fuer speichereffiziente Verarbeitung
            val tempFile = File.createTempFile("gm-pdf-", ".pdf")
            try {
                tempFile.writeBytes(pdfContent)

                val results = extractPages(tempFile)

                // Pro Seite ein Kind-Dokument erstellen
                for (result in results) {
                    val pageDoc = librarianService.createChildDocument(
                        parentId = documentId,
                        type = DocumentType.PAGE,
                        title = "Seite ${result.pageNumber}",
                        content = result.text.toByteArray(Charsets.UTF_8),
                        mimeType = "text/plain"
                    )

                    // Event fuer den Chunker publizieren
                    eventProducer.send(
                        PageExtractedEvent(
                            documentId = pageDoc.id,
                            parentDocumentId = documentId,
                            collectionId = collectionId,
                            pageNumber = result.pageNumber,
                            charCount = result.text.length
                        )
                    )
                }

                // Zustand auf EXTRACTED setzen
                librarianService.updateState(documentId, DocumentState.EXTRACTED)

            } finally {
                tempFile.delete()
            }

        } catch (e: Exception) {
            librarianService.updateState(documentId, DocumentState.FAILED)
            throw PdfDecodingException(documentId, e)
        }
    }

    /**
     * Extrahiert Text seitenweise mit Apache PDFBox.
     */
    private fun extractPages(pdfFile: File): List<PageExtractionResult> {
        val document = Loader.loadPDF(pdfFile)
        val results = mutableListOf<PageExtractionResult>()

        try {
            val stripper = PDFTextStripper()
            val totalPages = document.numberOfPages

            for (pageNum in 1..totalPages) {
                stripper.startPage = pageNum
                stripper.endPage = pageNum
                val text = stripper.getText(document).trim()

                if (text.isNotEmpty()) {
                    results.add(
                        PageExtractionResult(
                            pageNumber = pageNum,
                            text = text,
                            totalPages = totalPages
                        )
                    )
                }
            }
        } finally {
            document.close()
        }

        return results
    }
}
```

### Datenmodelle

```kotlin
package com.graphmesh.extraction.decoder

import java.util.UUID

/**
 * Ergebnis der Textextraktion einer einzelnen PDF-Seite.
 */
data class PageExtractionResult(
    val pageNumber: Int,
    val text: String,
    val totalPages: Int
)

/**
 * Eingehendes Event: Neues Dokument hochgeladen.
 * Topic: graphmesh.document.uploaded
 */
data class DocumentUploadedEvent(
    val documentId: String,
    val collectionId: UUID,
    val mimeType: String,
    val title: String
)

/**
 * Ausgehendes Event: Seite aus PDF extrahiert.
 * Topic: graphmesh.page.extracted
 */
data class PageExtractedEvent(
    val documentId: String,
    val parentDocumentId: String,
    val collectionId: UUID,
    val pageNumber: Int,
    val charCount: Int
)

/**
 * Exception fuer PDF-Dekodierungsfehler.
 */
class PdfDecodingException(
    documentId: String,
    cause: Throwable
) : RuntimeException(
    "PDF-Dekodierung fehlgeschlagen fuer Dokument '$documentId': ${cause.message}",
    cause
)
```

### Kafka-Topics

| Topic                         | Richtung  | Schema                  |
|-------------------------------|-----------|-------------------------|
| `graphmesh.document.uploaded` | Eingehend | `DocumentUploadedEvent` |
| `graphmesh.page.extracted`    | Ausgehend | `PageExtractedEvent`    |

## Betroffene Dateien

### Backend

| Datei                                                                                  | Aenderung                                        |
|----------------------------------------------------------------------------------------|--------------------------------------------------|
| `extraction/src/main/kotlin/com/graphmesh/extraction/decoder/PdfDecoderService.kt`     | NEU - Seitenweise PDF-Textextraktion             |
| `extraction/src/main/kotlin/com/graphmesh/extraction/decoder/PdfDecoderConsumer.kt`    | NEU - Kafka-Consumer fuer PDF-Events             |
| `extraction/src/main/kotlin/com/graphmesh/extraction/decoder/PageExtractionResult.kt`  | NEU - Extraktionsergebnis pro Seite              |
| `extraction/src/main/kotlin/com/graphmesh/extraction/decoder/DocumentUploadedEvent.kt` | NEU - Eingehendes Kafka-Event                    |
| `extraction/src/main/kotlin/com/graphmesh/extraction/decoder/PageExtractedEvent.kt`    | NEU - Ausgehendes Kafka-Event                    |
| `extraction/src/main/kotlin/com/graphmesh/extraction/decoder/PdfDecodingException.kt`  | NEU - Exception-Klasse                           |
| `extraction/build.gradle.kts`                                                          | NEU/AENDERUNG - PDFBox-Abhaengigkeit hinzufuegen |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                     | Aenderung                                     |
|-------------------------------------------------------------------------------------------|-----------------------------------------------|
| `extraction/src/test/kotlin/com/graphmesh/extraction/decoder/PdfDecoderServiceTest.kt`    | NEU - Unit-Tests mit Test-PDFs                |
| `extraction/src/test/kotlin/com/graphmesh/extraction/decoder/PdfDecoderConsumerTest.kt`   | NEU - Kafka-Consumer-Tests mit Embedded Kafka |
| `extraction/src/test/kotlin/com/graphmesh/extraction/decoder/PageExtractionResultTest.kt` | NEU - Validierung der Seitenextraktion        |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                             |
|-------------------|-------------|---------------------------------------------------|
| Spring Boot (JVM) | Ja          | Apache PDFBox ist eine JVM-Bibliothek             |
| KMP Library       | Nein        | PDFBox ist JVM-spezifisch                         |
| Ktor/Wasm         | Nein        | Keine PDF-Parsing-Bibliothek fuer Wasm verfuegbar |

## Akzeptanzkriterien

- [ ] PDF-Decoder empfaengt `document.uploaded`-Events und filtert auf `mimeType=application/pdf`
- [ ] Text wird seitenweise mit Apache PDFBox extrahiert
- [ ] Pro Seite wird ein Kind-Dokument im Librarian erstellt mit ID-Schema `{docId}/p{pageNum}`
- [ ] Leere Seiten (nur Whitespace) werden uebersprungen
- [ ] Nach erfolgreicher Extraktion wird der Dokumentzustand auf EXTRACTED gesetzt
- [ ] Bei Fehlern wird der Dokumentzustand auf FAILED gesetzt
- [ ] Pro extrahierte Seite wird ein `page.extracted`-Event auf Kafka publiziert
- [ ] PDF-Verarbeitung verwendet Temp-Dateien statt vollstaendiger In-Memory-Speicherung
- [ ] Temp-Dateien werden auch bei Fehlern aufgeraeumt (finally-Block)
- [ ] Nicht-PDF-Dokumente werden ignoriert (kein Fehler, kein Processing)
