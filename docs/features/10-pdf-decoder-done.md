# Feature 10: PDF Decoder — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/PdfDecoderService.kt`** — `@Service`. `decode(documentId)` setzt Zustand auf `PROCESSING`, laedt den Blob via `LibrarianService.getContent`, parst mit PDFBox 3 (`Loader.loadPDF(RandomAccessReadBuffer(pdfContent))` — kein Temp-File!), extrahiert seitenweise mit `PDFTextStripper` (`sortByPosition = true`), ueberspringt leere Seiten, legt je Seite ein Kind-Dokument (`DocumentType.PAGE`, `text/plain`) an und publiziert `PageExtractedEvent`. Bei Fehler Zustand `FAILED` + `PdfDecodingException`.
- **`src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/PdfDecoderConsumer.kt`** — `@KafkaListener(topics = ["graphmesh.document.ingested"], groupId = "graphmesh-pdf-decoder")`. Filtert auf `mimeType == "application/pdf"` und delegiert an `PdfDecoderService.decode`. Nicht-PDF wird auf `debug`-Level geloggt.
- **`src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/PdfDecoderModels.kt`** — `PageExtractionResult(pageNumber, text, totalPages)`, `PageExtractedEvent(documentId, parentDocumentId, collectionId: String, pageNumber, charCount)`, `PdfDecodingException`.
- **`src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/PageExtractedProducer.kt`** — Kafka-Producer auf `graphmesh.page.extracted` mit Avro-Schema `/avro/page-extracted.avsc` und CloudEvent-Headern.
- **Koexistenz**: Im selben Package liegen `TextDecoderConsumer`/`TextDecoderService`/`MarkdownSplitter` fuer Markdown-/Text-Dokumente (siehe Feature 49).
- **PDFBox-Dependency**: In `build.gradle.kts` als `org.apache.pdfbox:pdfbox:3.x`.

### Tests

- **`PdfDecoderServiceTest`** — Pipeline-Test mit Fake-`LibrarianService` und Fake-`PageExtractedProducer`: verifiziert Seitenanzahl, Event-Emission, Ueberspringen leerer Seiten, State-Transitions (PROCESSING -> EXTRACTED bzw. FAILED).

## Abweichungen vom Feature-Dokument

- **Package**: `com.agentwork.graphmesh.extraction.decoder` statt `com.graphmesh.extraction.decoder`. Kein separates `extraction/`-Modul.
- **Eingangstopic**: `graphmesh.document.ingested` statt `graphmesh.document.uploaded`. Event-Payload ist Avro (`GenericRecord`), nicht `DocumentUploadedEvent`-Datenklasse — der Consumer liest direkt `value["documentId"]` / `value["mimeType"]`. Keine eigene `DocumentUploadedEvent.kt`-Klasse.
- **Kein Temp-File**: PDF wird ueber `RandomAccessReadBuffer(pdfContent)` In-Memory an PDFBox uebergeben. Spec forderte Temp-File fuer Speichereffizienz.
- **`decode`-Signatur**: Nur `documentId` (String). `collectionId` wird intern aus dem Document geholt — nicht als Argument uebergeben.
- **Keine Coroutines**: `decode` ist synchron (nicht `suspend`).
- **`PageExtractedEvent.collectionId` ist String**: nicht `UUID` (konsistent mit Collection-Model).
- **`sortByPosition = true`**: Zusaetzliche PDFBox-Einstellung (besseres Mehrspalten-Layout), nicht im Spec.
- **Fehlerbehandlung im Consumer**: Exception wird geloggt, aber nicht geworfen (kein Topic-Dead-Letter); `PdfDecodingException` aus dem Service wird im Listener gefangen.
- **Kein eigener `PdfDecoderConsumerTest` / `PageExtractionResultTest`**: Beide Spec-Tests sind in `PdfDecoderServiceTest` konsolidiert.
- **Avro + CloudEvents**: Serialisierungsformat, nicht im Spec.

## Akzeptanzkriterien

- [x] Consumer filtert auf `application/pdf` — `PdfDecoderConsumer.handle`.
- [x] Seitenweise Extraktion mit Apache PDFBox — `extractPages` + `PDFTextStripper`.
- [x] Kind-Dokument pro Seite mit ID `{docId}/p{n}` — via `LibrarianService.createChildDocument(DocumentType.PAGE)`.
- [x] Leere Seiten werden uebersprungen — `if (text.isNotEmpty())` nach `trim()`.
- [x] Zustand `EXTRACTED` nach Erfolg — `librarianService.updateState(documentId, EXTRACTED)` am Ende.
- [x] Zustand `FAILED` bei Fehler — im `catch`-Block vor `throw PdfDecodingException`.
- [x] `page.extracted`-Event pro Seite — `PageExtractedProducer.send(...)`.
- [ ] Temp-Datei statt voll In-Memory — **nicht implementiert**, PDF wird als `ByteArray` + `RandomAccessReadBuffer` In-Memory geparst.
- [ ] Temp-File-Cleanup im finally — entfaellt (keine Temp-Datei). PDFBox-`document.close()` laeuft im `finally`.
- [x] Nicht-PDF wird ignoriert — Consumer-Filter, `return` ohne Exception.

## Offene Punkte

- Bei sehr grossen PDFs kann In-Memory-Parsing zu OOM fuehren; Umstieg auf Temp-File oder Streaming-Buffer waere eine Optimierung, falls Dokumentgroessen kritisch werden.
