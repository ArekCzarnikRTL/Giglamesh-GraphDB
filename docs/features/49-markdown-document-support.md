# Feature 49: Markdown-Dokumenten-Unterstuetzung

## Problem

Die Dokumenten-Pipeline in GraphMesh akzeptiert und verarbeitet aktuell ausschliesslich
PDF-Dateien. Das Frontend-Formular filtert per `accept={ "application/pdf": [".pdf"] }`,
und der `PdfDecoderConsumer` ueberspringt stillschweigend alle Dokumente mit anderem MimeType.

In der Praxis fehlt damit die Moeglichkeit, bereits als Text vorliegende Inhalte
(README-Dateien, technische Dokumentation, Notizen, exportierte Wiki-Seiten, Spec-Dokumente)
einzuspeisen, ohne sie vorher in PDF zu konvertieren. Gerade fuer entwicklernahe
Wissensgraphen ist Markdown das natuerliche Quellformat.

## Ziel

Markdown-Dateien (`.md`, `text/markdown`) koennen hochgeladen und durch die komplette
Extraktions-Pipeline (Chunking, Embedding, LLM-Extraktion) verarbeitet werden — genauso
wie PDFs heute.

1. **Upload** — Das Frontend-Formular akzeptiert `.md` zusaetzlich zu `.pdf`.
2. **Decoding** — Ein neuer `TextDecoderConsumer` verarbeitet `text/markdown`-Dokumente
   und produziert `page.extracted`-Events mit dem bereits dekodierten Text.
3. **Chunking** — Der bestehende `ChunkerService` laeuft unveraendert auf den extrahierten
   Seiten.
4. **Strukturerhalt** — Markdown-Headings (`#`, `##`, `###`) werden als Chunk-Grenzen
   genutzt, sodass semantisch zusammenhaengende Abschnitte nicht zerrissen werden.

## Voraussetzungen

| Abhaengigkeit                                          | Status        | Blocker? |
|--------------------------------------------------------|---------------|----------|
| Feature 09: Document Management (Upload, Storage)      | Implementiert | Ja       |
| Feature 10: PDF Decoder (Parallelpattern)              | Implementiert | Referenz |
| Feature 11: Document Chunker                           | Implementiert | Ja       |

## Architektur

### Upload-Flow

Der bestehende `uploadDocument`-Mutation-Endpunkt braucht keine Aenderung — er akzeptiert
bereits beliebige MimeTypes. Die `document.ingested`-Events tragen den MimeType, sodass
Decoder-Consumer selbst entscheiden koennen ob sie zustaendig sind.

### Neuer TextDecoderConsumer

Analog zu `PdfDecoderConsumer`:

```kotlin
@Component
class TextDecoderConsumer(
    private val textDecoderService: TextDecoderService
) {
    @KafkaListener(topics = ["graphmesh.document.ingested"], groupId = "graphmesh-text-decoder")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val mimeType = record.value()["mimeType"].toString()
        if (mimeType != "text/markdown" && mimeType != "text/plain") return
        textDecoderService.decode(record.value()["documentId"].toString())
    }
}
```

### TextDecoderService

Der Service:
1. Laedt den Byte-Content aus `LibrarianService`.
2. Dekodiert als UTF-8-String.
3. Bei Markdown: Splittet an Heading-Grenzen in "Seiten" (pro Top-Level-Heading eine Seite).
4. Speichert jede Seite als Child-Document (`DocumentType.PAGE`).
5. Produziert `page.extracted`-Events — diese triggern den bestehenden Chunker.

```kotlin
@Service
class TextDecoderService(
    private val librarianService: LibrarianService,
    private val pageExtractedProducer: PageExtractedProducer
) {
    fun decode(documentId: String) {
        val content = librarianService.getContent(documentId)
        val text = String(content, Charsets.UTF_8)
        val pages = splitIntoPages(text)

        pages.forEachIndexed { index, pageText ->
            val pageDoc = librarianService.createChildDocument(
                parentId = documentId,
                type = DocumentType.PAGE,
                title = "Seite ${index + 1}",
                content = pageText.toByteArray(Charsets.UTF_8),
                mimeType = "text/plain"
            )
            pageExtractedProducer.send(
                PageExtractedEvent(
                    documentId = documentId,
                    pageId = pageDoc.id,
                    pageNumber = index + 1,
                    collectionId = /* aus Document-Metadaten */
                )
            )
        }
    }

    private fun splitIntoPages(text: String): List<String> {
        // Split an Top-Level-Headings (#, ##), nicht an ###+.
        // Wenn keine Headings existieren: ganzes Dokument als eine Seite.
    }
}
```

### Markdown-Splitting-Regel

- Ein neuer Split-Punkt **vor** jeder Zeile, die mit `# ` oder `## ` beginnt.
- Listen, Code-Bloecke, Zitate bleiben unveraendert — sie landen in dem Chunk, in dem sie
  stehen.
- Wenn das Dokument keine Headings enthaelt: eine einzige Seite mit dem Gesamttext.
- Mindestlaenge pro Seite: 50 Zeichen (sonst wird sie an die vorherige angehaengt, um
  Mini-Seiten durch Heading-Kaskaden zu vermeiden).

### Frontend-Aenderung

In `frontend/src/components/documents/DocumentUpload.tsx`:

```tsx
accept: {
  "application/pdf": [".pdf"],
  "text/markdown": [".md", ".markdown"],
  "text/plain": [".txt"]
}
```

Der automatische MimeType-Hinweis funktioniert ueber `file.type` — bei `.md`-Dateien
liefert der Browser manchmal leeren String, dann faellt das Frontend auf die
Endung-basierte Erkennung zurueck:

```tsx
const mimeType = file.type
  || (file.name.endsWith(".md") ? "text/markdown" : "text/plain");
```

## Betroffene Dateien

### Backend

| Datei                                                                                        | Aenderung                                                    |
|----------------------------------------------------------------------------------------------|--------------------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/TextDecoderConsumer.kt`         | NEU — Kafka-Consumer fuer `text/markdown` und `text/plain`   |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/TextDecoderService.kt`          | NEU — Dekodierung, Splitting, Page-Event-Emission            |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/MarkdownSplitter.kt`            | NEU — Splitting-Logik (pure, testbar)                        |

### Frontend

| Datei                                                           | Aenderung                                                 |
|-----------------------------------------------------------------|-----------------------------------------------------------|
| `frontend/src/components/documents/DocumentUpload.tsx`          | UPDATE — `accept`-Liste erweitern, MimeType-Fallback     |

### Tests

| Datei                                                                                           | Aenderung                                              |
|-------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/extraction/decoder/MarkdownSplitterTest.kt`           | NEU — Unit-Tests fuer Splitting-Regeln                 |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/decoder/TextDecoderServiceTest.kt`         | NEU — Pipeline-Tests mit Fakes fuer LibrarianService   |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                                          |
|-------------------|-------------|----------------------------------------------------------------|
| Spring Boot (JVM) | Ja          | Kafka, Spring Boot                                             |

## Akzeptanzkriterien

- [ ] Upload einer `.md`-Datei ueber das Frontend startet die komplette Extraction-Pipeline.
- [ ] `TextDecoderConsumer` ignoriert Nicht-Text-Dokumente (keine Doppelverarbeitung mit PDF).
- [ ] `TextDecoderService` splittet an `#` und `##`-Headings, aber nicht an tieferen.
- [ ] Markdown ohne Headings wird als einzelne Seite verarbeitet.
- [ ] Seiten mit weniger als 50 Zeichen werden an die vorherige angehaengt.
- [ ] Der Chunker produziert aus jeder Page korrekte `chunk.created`-Events.
- [ ] Embeddings und Extraktion laufen unveraendert auf den Chunks.
- [ ] Auch `.txt`-Dateien (`text/plain`) werden verarbeitet (als eine einzige Seite).
- [ ] Frontend zeigt im Dateiauswahldialog `.pdf`, `.md`, `.markdown` und `.txt`.
- [ ] MarkdownSplitterTest deckt ab: keine Headings, nur `#`, gemischt `#`/`##`/`###`, Code-Block mit `#` (wird nicht gesplittet).
- [ ] Bestehender PDF-Upload funktioniert unveraendert (Regression).
