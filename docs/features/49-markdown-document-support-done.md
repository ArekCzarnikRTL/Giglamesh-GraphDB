# Feature 49: Markdown-Dokumenten-Unterstuetzung — Done

## Implementierung

### Backend

- **`src/main/kotlin/.../extraction/decoder/TextDecoderConsumer.kt`** — Kafka-Listener auf `graphmesh.document.ingested`, filtert auf `text/markdown` und `text/plain`, andere MimeTypes werden im `debug`-Log uebersprungen (keine Doppelverarbeitung mit PDF).
- **`src/main/kotlin/.../extraction/decoder/TextDecoderService.kt`** — laedt den Blob via `LibrarianService.getContent`, dekodiert als UTF-8, ruft `sanitizeForLlm` (Control-Char-Stripping, siehe Commit `b4594a9`), splittet via `MarkdownSplitter`, legt je Seite ein Child-Document (`DocumentType.PAGE`, MimeType `text/plain`) an und emittiert `PageExtractedEvent`. Zustandsuebergaenge `PROCESSING → EXTRACTED` bzw. `FAILED` werden gesetzt. Eigene `TextDecodingException` mit Wrapping der Ursache.
- **`src/main/kotlin/.../extraction/decoder/MarkdownSplitter.kt`** — Pure Splitting-Komponente. Heading-Regex `^(#{1,2})\s+.+$`, Split vor `#`/`##`, nicht vor `###`+. Code-Block-Tracking via dreifachem Backtick (`` ``` ``) unterdrueckt False-Positives auf `#`-Kommentaren in Code. Pages unter 50 Zeichen **ohne Body-Inhalt** werden an die Vorgaengerseite angehaengt (Schutz gegen leere Heading-Kaskaden). Leere/Nur-Whitespace-Eingaben liefern leere Page-Liste.

### Frontend

- **`frontend/src/components/documents/DocumentUpload.tsx`** — `accept`-Whitelist um `text/markdown: [".md", ".markdown"]` und `text/plain: [".txt"]` erweitert. MimeType-Fallback: wenn `file.type` leer ist (Browser-abhaengig bei `.md`), wird anhand der Endung `text/markdown` bzw. `text/plain` bestimmt.

### Tests

- **`MarkdownSplitterTest`** — Unit-Tests fuer alle Splitting-Regeln (keine Headings, nur `#`, gemischt `#`/`##`/`###`, Code-Block mit `#`, leerer Input, Mini-Seiten-Merge).
- **`TextDecoderServiceTest`** — Pipeline-Test mit Fakes fuer `LibrarianService` und `PageExtractedProducer`: verifiziert Seitenanzahl, Event-Emission, State-Transitions.
- Bestehender `PdfDecoderServiceTest` unveraendert → Regression des PDF-Pfads abgedeckt.

## Abweichungen vom Feature-Dokument

- **`text/plain` gleichberechtigt zu Markdown**: Das Spec-Dokument nennt `.txt` nur am Rand. In der Implementierung behandelt der `TextDecoderConsumer` beide MimeTypes explizit und identisch; der Splitter liefert bei fehlenden Headings automatisch eine einzelne Seite → Anforderung erfuellt ohne Sonderweg.
- **`sanitizeForLlm` vor dem Splitting**: Im Spec nicht erwaehnt, aber im Service integriert (Control-Char-Stripping analog zum PDF-Pfad, Commit `b4594a9`). Verhindert Parser-Fehler in nachgelagerten LLM-Calls.
- **Body-Content-Heuristik beim Merge**: Das Spec sagt „Seiten unter 50 Zeichen an die vorherige anhaengen". Implementiert ist: Merge **nur**, wenn die Seite ausschliesslich aus einer Heading-Zeile besteht (`hasBodyContent == false`). So werden kurze, aber inhaltlich vollstaendige Abschnitte nicht verschluckt.
- **Kein `splitIntoPages`-Helper im Service**: Das Beispiel im Spec (`private fun splitIntoPages(...)` inline) wurde zugunsten einer eigenstaendigen Spring-Komponente `MarkdownSplitter` aufgeloest — besser isoliert testbar.
- **State-Handling**: Service setzt explizit `PROCESSING`/`EXTRACTED`/`FAILED` auf dem Parent-Document, nicht im Spec beschrieben, aber konsistent mit `PdfDecoderService`.

## Akzeptanzkriterien

- [x] Upload einer `.md`-Datei ueber das Frontend startet die komplette Extraction-Pipeline.
- [x] `TextDecoderConsumer` ignoriert Nicht-Text-Dokumente (keine Doppelverarbeitung mit PDF).
- [x] `MarkdownSplitter` splittet an `#` und `##`-Headings, aber nicht an tieferen.
- [x] Markdown ohne Headings wird als einzelne Seite verarbeitet.
- [x] Seiten mit weniger als 50 Zeichen (ohne Body) werden an die vorherige angehaengt.
- [x] Der Chunker produziert aus jeder Page korrekte `chunk.created`-Events (via bestehender `PageExtractedEvent`-Pfad).
- [x] Embeddings und Extraktion laufen unveraendert auf den Chunks.
- [x] Auch `.txt`-Dateien (`text/plain`) werden verarbeitet.
- [x] Frontend zeigt im Dateiauswahldialog `.pdf`, `.md`, `.markdown` und `.txt`.
- [x] `MarkdownSplitterTest` deckt ab: keine Headings, nur `#`, gemischt `#`/`##`/`###`, Code-Block mit `#`.
- [x] Bestehender PDF-Upload funktioniert unveraendert.

## Offene Punkte

- Keine. Feature ist in Produktion (Commits `f43a81b`, `0e7cefb`, `a63a041`, `a53fbb1`).
