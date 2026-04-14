# Feature 49: Markdown Document Support — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable upload and full-pipeline processing of Markdown (.md) and plain text (.txt) documents alongside PDFs.

**Architecture:** New `TextDecoderConsumer` listens to the same `graphmesh.document.ingested` topic as the PDF decoder, processes `text/markdown` and `text/plain` documents, and emits `page.extracted` events that the existing Chunker consumes. Markdown is split at top-level headings (`#`, `##`) into pages. A pure `MarkdownSplitter` isolates the splitting logic for unit testing.

**Tech Stack:** Kotlin, Spring Boot, Kafka, Next.js 14, react-dropzone

---

### Task 1: MarkdownSplitter (Pure Splitting Logic + Tests)

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/MarkdownSplitter.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/extraction/decoder/MarkdownSplitterTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.agentwork.graphmesh.extraction.decoder

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownSplitterTest {

    private val splitter = MarkdownSplitter()

    @Test
    fun `empty text produces no pages`() {
        assertEquals(emptyList(), splitter.split(""))
    }

    @Test
    fun `text without headings is a single page`() {
        val text = "Just some plain paragraph text.\nNo headings here."
        assertEquals(listOf(text), splitter.split(text))
    }

    @Test
    fun `splits at level-1 headings`() {
        val text = """
            # First
            Content A.
            # Second
            Content B.
        """.trimIndent()
        val pages = splitter.split(text)
        assertEquals(2, pages.size)
        assertEquals("# First\nContent A.", pages[0])
        assertEquals("# Second\nContent B.", pages[1])
    }

    @Test
    fun `splits at level-2 headings`() {
        val text = """
            ## Alpha
            A.
            ## Beta
            B.
        """.trimIndent()
        val pages = splitter.split(text)
        assertEquals(2, pages.size)
        assertEquals("## Alpha\nA.", pages[0])
    }

    @Test
    fun `does not split at level-3 or deeper headings`() {
        val text = """
            # Top
            Intro.
            ### Deep
            Details.
            ### Other
            More.
        """.trimIndent()
        val pages = splitter.split(text)
        assertEquals(1, pages.size)
    }

    @Test
    fun `does not split inside fenced code blocks`() {
        val text = """
            # Real heading
            Intro.
            ```
            # not a heading inside code
            ```
            After code.
        """.trimIndent()
        val pages = splitter.split(text)
        assertEquals(1, pages.size)
    }

    @Test
    fun `appends pages shorter than 50 chars to previous page`() {
        val text = """
            # Long section
            This is a long section with plenty of content to fill at least fifty characters easily.
            # A
            # Another long section with enough content to be its own page without any issue at all.
        """.trimIndent()
        val pages = splitter.split(text)
        assertEquals(2, pages.size)
        // "# A" got appended to the first page because it is too short.
    }

    @Test
    fun `leading content before first heading forms its own page`() {
        val text = """
            Some intro without heading.

            # First
            Body.
        """.trimIndent()
        val pages = splitter.split(text)
        assertEquals(2, pages.size)
        assertEquals("Some intro without heading.", pages[0].trim())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.decoder.MarkdownSplitterTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Implement MarkdownSplitter**

```kotlin
package com.agentwork.graphmesh.extraction.decoder

import org.springframework.stereotype.Component

@Component
class MarkdownSplitter {

    companion object {
        private const val MIN_PAGE_LENGTH = 50
        private val HEADING_REGEX = Regex("^(#{1,2})\\s+.+$")
    }

    fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val lines = text.lines()
        val pages = mutableListOf<StringBuilder>()
        var current = StringBuilder()
        var inCodeBlock = false

        for (line in lines) {
            if (line.trimStart().startsWith("```")) inCodeBlock = !inCodeBlock

            val isHeading = !inCodeBlock && HEADING_REGEX.matches(line)

            if (isHeading && current.isNotEmpty()) {
                pages.add(current)
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        if (current.isNotEmpty()) pages.add(current)

        // Merge pages shorter than MIN_PAGE_LENGTH into the previous page.
        val merged = mutableListOf<StringBuilder>()
        for (page in pages) {
            val text = page.toString().trim()
            if (merged.isNotEmpty() && text.length < MIN_PAGE_LENGTH) {
                merged.last().append('\n').append(text)
            } else {
                merged.add(StringBuilder(text))
            }
        }

        return merged.map { it.toString() }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.decoder.MarkdownSplitterTest"`
Expected: PASS (8 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/MarkdownSplitter.kt \
        src/test/kotlin/com/agentwork/graphmesh/extraction/decoder/MarkdownSplitterTest.kt
git commit -m "feat(decoder): add MarkdownSplitter with heading-based page splitting"
```

---

### Task 2: TextDecoderService

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/TextDecoderService.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/extraction/decoder/TextDecoderServiceTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.agentwork.graphmesh.extraction.decoder

import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentState
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.librarian.LibrarianService
import io.mockk.*
import kotlin.test.Test
import kotlin.test.assertEquals

class TextDecoderServiceTest {

    private val librarian = mockk<LibrarianService>(relaxed = true)
    private val producer = mockk<PageExtractedProducer>(relaxed = true)
    private val splitter = MarkdownSplitter()
    private val service = TextDecoderService(librarian, producer, splitter)

    @Test
    fun `decode splits markdown and emits page events`() {
        val content = "# A\nContent of section A, long enough to be its own page please.\n# B\nContent of section B, also long enough to count as a full page here."
        val doc = Document(id = "doc-1", collectionId = "col-1", title = "x", mimeType = "text/markdown", type = DocumentType.SOURCE)
        val pageDoc1 = doc.copy(id = "page-1", type = DocumentType.PAGE)
        val pageDoc2 = doc.copy(id = "page-2", type = DocumentType.PAGE)

        every { librarian.getContent("doc-1") } returns content.toByteArray()
        every { librarian.findById("doc-1") } returns doc
        every { librarian.createChildDocument(any(), any(), any(), any(), any()) } returnsMany listOf(pageDoc1, pageDoc2)

        service.decode("doc-1")

        verify(exactly = 2) { librarian.createChildDocument(parentId = "doc-1", type = DocumentType.PAGE, any(), any(), mimeType = "text/plain") }
        verify(exactly = 2) { producer.send(any()) }
        verify { librarian.updateState("doc-1", DocumentState.EXTRACTED) }
    }

    @Test
    fun `decode on empty content marks as extracted without pages`() {
        val doc = Document(id = "doc-2", collectionId = "col-1", title = "x", mimeType = "text/plain", type = DocumentType.SOURCE)
        every { librarian.getContent("doc-2") } returns "".toByteArray()
        every { librarian.findById("doc-2") } returns doc

        service.decode("doc-2")

        verify(exactly = 0) { producer.send(any()) }
        verify { librarian.updateState("doc-2", DocumentState.EXTRACTED) }
    }

    @Test
    fun `decode sets FAILED on error`() {
        every { librarian.getContent("doc-3") } throws RuntimeException("boom")

        val ex = runCatching { service.decode("doc-3") }.exceptionOrNull()

        assertEquals(true, ex is TextDecodingException)
        verify { librarian.updateState("doc-3", DocumentState.FAILED) }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.decoder.TextDecoderServiceTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Implement TextDecoderService**

```kotlin
package com.agentwork.graphmesh.extraction.decoder

import com.agentwork.graphmesh.librarian.DocumentState
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.librarian.LibrarianService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class TextDecoderService(
    private val librarianService: LibrarianService,
    private val pageExtractedProducer: PageExtractedProducer,
    private val markdownSplitter: MarkdownSplitter
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun decode(documentId: String) {
        librarianService.updateState(documentId, DocumentState.PROCESSING)

        try {
            val content = librarianService.getContent(documentId)
            val doc = librarianService.findById(documentId)
                ?: throw TextDecodingException(documentId, IllegalStateException("Document not found"))

            val text = String(content, Charsets.UTF_8)
            val pages = markdownSplitter.split(text)

            pages.forEachIndexed { index, pageText ->
                if (pageText.isBlank()) return@forEachIndexed
                val pageDoc = librarianService.createChildDocument(
                    parentId = documentId,
                    type = DocumentType.PAGE,
                    title = "Seite ${index + 1}",
                    content = pageText.toByteArray(Charsets.UTF_8),
                    mimeType = "text/plain"
                )
                pageExtractedProducer.send(
                    PageExtractedEvent(
                        documentId = pageDoc.id,
                        parentDocumentId = documentId,
                        collectionId = doc.collectionId,
                        pageNumber = index + 1,
                        charCount = pageText.length
                    )
                )
            }

            librarianService.updateState(documentId, DocumentState.EXTRACTED)
            logger.info("Text document decoded: documentId={}, pages={}", documentId, pages.size)

        } catch (e: TextDecodingException) {
            throw e
        } catch (e: Exception) {
            librarianService.updateState(documentId, DocumentState.FAILED)
            throw TextDecodingException(documentId, e)
        }
    }
}

class TextDecodingException(
    documentId: String,
    cause: Throwable
) : RuntimeException("Text decoding failed for document '$documentId': ${cause.message}", cause)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.decoder.TextDecoderServiceTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/TextDecoderService.kt \
        src/test/kotlin/com/agentwork/graphmesh/extraction/decoder/TextDecoderServiceTest.kt
git commit -m "feat(decoder): add TextDecoderService for markdown and plain text"
```

---

### Task 3: TextDecoderConsumer (Kafka Listener)

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/TextDecoderConsumer.kt`

- [ ] **Step 1: Implement the consumer**

```kotlin
package com.agentwork.graphmesh.extraction.decoder

import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class TextDecoderConsumer(
    private val textDecoderService: TextDecoderService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.document.ingested"], groupId = "graphmesh-text-decoder")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val documentId = value["documentId"].toString()
        val mimeType = value["mimeType"].toString()

        if (mimeType != "text/markdown" && mimeType != "text/plain") {
            logger.debug("Skipping non-text document: id={}, mimeType={}", documentId, mimeType)
            return
        }

        logger.info("Text document received for decoding: id={}, mimeType={}", documentId, mimeType)

        try {
            textDecoderService.decode(documentId)
        } catch (e: Exception) {
            logger.error("Text decoding failed for document {}", documentId, e)
        }
    }
}
```

- [ ] **Step 2: Verify full build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/TextDecoderConsumer.kt
git commit -m "feat(decoder): add TextDecoderConsumer for text/markdown and text/plain"
```

---

### Task 4: Frontend Upload Accepts Markdown and Text

**Files:**
- Modify: `frontend/src/components/documents/DocumentUpload.tsx`

- [ ] **Step 1: Update the accept list and mimeType fallback**

Find this block around line 88-125:

```tsx
  const onDrop = useCallback(
    async (accepted: File[]) => {
      const file = accepted[0];
      if (!file) return;
      setUploadError(null);
      try {
        const base64 = await fileToBase64(file);
        const { data } = await uploadDocument({
          variables: {
            input: {
              collectionId,
              title: file.name,
              mimeType: file.type || "application/pdf",
              content: base64,
              metadata: null,
            },
          },
        });
```

Replace the `mimeType` line with an endings-based fallback:

```tsx
              mimeType: file.type || (
                file.name.endsWith(".md") || file.name.endsWith(".markdown")
                  ? "text/markdown"
                  : file.name.endsWith(".txt")
                    ? "text/plain"
                    : "application/pdf"
              ),
```

And find:

```tsx
    accept: { "application/pdf": [".pdf"] },
```

Replace with:

```tsx
    accept: {
      "application/pdf": [".pdf"],
      "text/markdown": [".md", ".markdown"],
      "text/plain": [".txt"],
    },
```

- [ ] **Step 2: Type-check the frontend**

Run: `cd frontend && npx tsc --noEmit 2>&1 | grep "DocumentUpload" || echo "No new errors"`
Expected: No new errors

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/documents/DocumentUpload.tsx
git commit -m "feat(frontend): accept .md, .markdown and .txt in document upload"
```

---

### Task 5: Manual Verification

**Files:** None new — verification only.

- [ ] **Step 1: Start backend**

Run: `./gradlew bootRun` (or `./start.sh`)

- [ ] **Step 2: Start frontend**

Run: `cd frontend && pnpm dev` (or use `./start.sh`)

- [ ] **Step 3: Upload a Markdown file**

1. Open http://localhost:3002/documents/upload
2. Select a `.md` file (e.g. this project's `README.md`)
3. Verify the upload succeeds
4. Observe the document status transition: `PROCESSING` → `EXTRACTED`
5. Open the document detail page and verify it has page children
6. Run a query against the collection and verify the markdown content is searchable

- [ ] **Step 4: Upload a plain .txt file**

1. Upload a `.txt` file
2. Verify it completes with one page

- [ ] **Step 5: Upload a PDF (regression)**

1. Upload a `.pdf` file
2. Verify the existing PDF pipeline still works

- [ ] **Step 6: Verify both decoders do not double-process**

Check backend logs: for a `.md` file only `TextDecoderConsumer` should log "Text document received", not the PDF decoder. For a `.pdf` file only `PdfDecoderConsumer` should log.

- [ ] **Step 7: (Optional) Create done file**

Create `docs/features/49-markdown-document-support-done.md` documenting what was implemented and any deviations from the spec.
