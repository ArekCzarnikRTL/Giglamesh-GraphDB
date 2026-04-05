# Definition Extractor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract entity definitions from text chunks via LLM and store them as `rdfs:comment` triples in the knowledge graph.

**Architecture:** Kafka consumer receives `chunk.created` events, delegates to `DefinitionExtractorService` which calls LLM via Koog `PromptExecutor`, parses JSONL response, generates RDF quads (knowledge + labels + provenance), and persists via `QuadStore`. Mirrors `RelationshipExtractorService` pattern exactly.

**Tech Stack:** Kotlin, Spring Boot, Koog PromptExecutor, Jackson, Apache Kafka, Cassandra (via QuadStore)

---

### Task 1: Models and Prompt Template

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorModels.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionPromptTemplate.kt`

- [ ] **Step 1: Create models file**

```kotlin
package com.agentwork.graphmesh.extraction.definition

data class DefinitionResult(
    val entity: String,
    val definition: String
)

data class DefinitionExtractionResult(
    val chunkId: String,
    val definitionsExtracted: Int,
    val entitiesFound: List<String>
)
```

- [ ] **Step 2: Create prompt template**

```kotlin
package com.agentwork.graphmesh.extraction.definition

object DefinitionPromptTemplate {

    fun systemPrompt(): String = """
        You are a knowledge extraction assistant. Your task is to extract
        definitions and descriptions of entities from the given text.

        For each entity that is defined or described in the text, output
        a JSON object per line in the following format:
        {"entity": "<entity name>", "definition": "<definition or description>"}

        Rules:
        - Only extract definitions explicitly stated in the text
        - A definition describes WHAT an entity IS or WHAT it DOES
        - Ignore pure relationships between entities (e.g., "A works at B")
        - Use clear, canonical names for entities
        - The definition should be a complete, understandable sentence
        - One JSON object per line (JSONL format)

        Example:
        Text: "Photosynthesis is the process by which plants convert sunlight
        into chemical energy. Chlorophyll is the green pigment that enables
        this process."

        {"entity": "Photosynthesis", "definition": "Process by which plants convert sunlight into chemical energy"}
        {"entity": "Chlorophyll", "definition": "Green pigment that enables the process of photosynthesis"}
    """.trimIndent()

    fun userPrompt(chunkText: String): String = """
        Extract all entity definitions from the following text:

        ---
        $chunkText
        ---

        Respond ONLY with JSON objects in JSONL format, one per line.
    """.trimIndent()
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorModels.kt src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionPromptTemplate.kt
git commit -m "feat(definition): add models and prompt template for definition extraction"
```

---

### Task 2: JSONL Parsing Tests

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorServiceTest.kt`

**Reference files (read before implementing):**
- `src/test/kotlin/com/agentwork/graphmesh/extraction/relationship/RelationshipExtractorServiceTest.kt` — test pattern with standalone parsing copy
- `src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorModels.kt` — data classes

- [ ] **Step 1: Write test file with standalone parsing logic and all JSONL tests**

```kotlin
package com.agentwork.graphmesh.extraction.definition

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefinitionExtractorServiceTest {

    private val objectMapper = jacksonObjectMapper()

    // --- JSONL Parsing (standalone copy) ---

    @Test
    fun `parseJsonlDefinitions extracts valid definitions`() {
        val response = """
            {"entity": "Photosynthesis", "definition": "Process by which plants convert sunlight into chemical energy"}
            {"entity": "Chlorophyll", "definition": "Green pigment that enables photosynthesis"}
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(2, results.size)
        assertEquals("Photosynthesis", results[0].entity)
        assertEquals("Process by which plants convert sunlight into chemical energy", results[0].definition)
        assertEquals("Chlorophyll", results[1].entity)
    }

    @Test
    fun `parseJsonlDefinitions skips blank lines`() {
        val response = """
            {"entity": "A", "definition": "Def A"}

            {"entity": "B", "definition": "Def B"}
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `parseJsonlDefinitions skips invalid JSON lines`() {
        val response = """
            {"entity": "A", "definition": "Def A"}
            This is not JSON
            {"entity": "B", "definition": "Def B"}
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `parseJsonlDefinitions skips lines with missing fields`() {
        val response = """
            {"entity": "A", "definition": "Def A"}
            {"entity": "B"}
            {"definition": "orphan definition"}
            {"entity": "C", "definition": "Def C"}
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(2, results.size)
        assertEquals("A", results[0].entity)
        assertEquals("C", results[1].entity)
    }

    @Test
    fun `parseJsonlDefinitions skips lines with empty values`() {
        val response = """
            {"entity": "", "definition": "Def A"}
            {"entity": "B", "definition": ""}
            {"entity": "C", "definition": "Def C"}
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(1, results.size)
        assertEquals("C", results[0].entity)
    }

    @Test
    fun `parseJsonlDefinitions strips markdown code fences`() {
        val response = """
            ```json
            {"entity": "A", "definition": "Def A"}
            {"entity": "B", "definition": "Def B"}
            ```
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `parseJsonlDefinitions handles empty response`() {
        val results = parseJsonlDefinitions("")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `parseJsonlDefinitions handles truncated last line`() {
        val response = """
            {"entity": "A", "definition": "Def A"}
            {"entity": "B", "defini
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(1, results.size)
        assertEquals("A", results[0].entity)
    }

    // Standalone copy of parsing logic (same pattern as RelationshipExtractorServiceTest)
    private fun parseJsonlDefinitions(llmResponse: String): List<DefinitionResult> {
        return llmResponse.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val map = objectMapper.readValue<Map<String, String>>(line)
                    val entity = map["entity"]?.takeIf { it.isNotBlank() }
                    val definition = map["definition"]?.takeIf { it.isNotBlank() }
                    if (entity != null && definition != null) DefinitionResult(entity, definition) else null
                } catch (_: Exception) {
                    null
                }
            }
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.definition.DefinitionExtractorServiceTest" --info`
Expected: 8 tests PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorServiceTest.kt
git commit -m "test(definition): add JSONL parsing tests for definition extraction"
```

---

### Task 3: DefinitionExtractorService Implementation

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorService.kt`

**Reference files (read before implementing):**
- `src/main/kotlin/com/agentwork/graphmesh/extraction/relationship/RelationshipExtractorService.kt` — exact pattern to follow
- `src/main/kotlin/com/agentwork/graphmesh/rdf/EntityIdGenerator.kt` — deterministic ID generation
- `src/main/kotlin/com/agentwork/graphmesh/rdf/Quad.kt` — Quad, Triple, NamedGraph
- `src/main/kotlin/com/agentwork/graphmesh/rdf/RdfTerm.kt` — Uri, Literal, QuotedTriple
- `src/main/kotlin/com/agentwork/graphmesh/rdf/QuadConverter.kt` — toStoredQuad()
- `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt` — insertBatch()

- [ ] **Step 1: Create service**

```kotlin
package com.agentwork.graphmesh.extraction.definition

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.Quad
import com.agentwork.graphmesh.rdf.QuadConverter
import com.agentwork.graphmesh.rdf.RdfTerm
import com.agentwork.graphmesh.storage.QuadStore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class DefinitionExtractorService(
    private val promptExecutor: PromptExecutor,
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val modelName: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    companion object {
        private const val RDFS_COMMENT = "http://www.w3.org/2000/01/rdf-schema#comment"
        private const val RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label"
        private const val EXTRACTED_FROM = "http://graphmesh.io/ontology/extractedFrom"
    }

    fun extract(chunkId: String, collectionId: String): DefinitionExtractionResult {
        val content = librarianService.getContent(chunkId)
        val chunkText = String(content, Charsets.UTF_8)

        if (chunkText.isBlank()) {
            return DefinitionExtractionResult(chunkId, 0, emptyList())
        }

        val extractionPrompt = prompt("definition-extraction") {
            system(DefinitionPromptTemplate.systemPrompt())
            user(DefinitionPromptTemplate.userPrompt(chunkText))
        }

        val llmResponse = runBlocking {
            promptExecutor.execute(extractionPrompt, LLModel(LLMProvider.OpenAI, modelName))
        }
        val responseText = llmResponse.first().content

        val definitions = parseJsonlDefinitions(responseText)

        if (definitions.isEmpty()) {
            logger.debug("No definitions extracted from chunk {}", chunkId)
            return DefinitionExtractionResult(chunkId, 0, emptyList())
        }

        // Knowledge quads: (entityId, rdfs:comment, definition) in DEFAULT graph
        val knowledgeQuads = definitions.map { result ->
            Quad(
                subject = EntityIdGenerator.generate(result.entity),
                predicate = RdfTerm.Uri(RDFS_COMMENT),
                objectTerm = RdfTerm.Literal(result.definition),
                graph = NamedGraph.DEFAULT
            )
        }

        // Label quads: (entityId, rdfs:label, entityName) in DEFAULT graph, deduplicated
        val labelQuads = definitions.map { result ->
            Quad(
                subject = EntityIdGenerator.generate(result.entity),
                predicate = RdfTerm.Uri(RDFS_LABEL),
                objectTerm = RdfTerm.Literal(result.entity),
                graph = NamedGraph.DEFAULT
            )
        }.distinctBy { it.subject.toNTriples() }

        // Provenance quads: (<<knowledge triple>>, extractedFrom, urn:chunk:X) in SOURCE graph
        val provenanceQuads = knowledgeQuads.map { quad ->
            Quad(
                subject = RdfTerm.QuotedTriple(quad.triple),
                predicate = RdfTerm.Uri(EXTRACTED_FROM),
                objectTerm = RdfTerm.Uri("urn:chunk:$chunkId"),
                graph = NamedGraph.SOURCE
            )
        }

        val allStoredQuads = (knowledgeQuads + labelQuads + provenanceQuads).map { QuadConverter.toStoredQuad(it) }
        quadStore.insertBatch(collectionId, allStoredQuads)

        logger.info("Extracted {} definitions from chunk {}", definitions.size, chunkId)

        return DefinitionExtractionResult(
            chunkId = chunkId,
            definitionsExtracted = definitions.size,
            entitiesFound = definitions.map { it.entity }
        )
    }

    internal fun parseJsonlDefinitions(llmResponse: String): List<DefinitionResult> {
        return llmResponse.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val map = objectMapper.readValue<Map<String, String>>(line)
                    val entity = map["entity"]?.takeIf { it.isNotBlank() }
                    val definition = map["definition"]?.takeIf { it.isNotBlank() }
                    if (entity != null && definition != null) DefinitionResult(entity, definition) else null
                } catch (_: Exception) {
                    null
                }
            }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorService.kt
git commit -m "feat(definition): add DefinitionExtractorService with LLM extraction and quad generation"
```

---

### Task 4: MockK Tests for extract() Method

**Files:**
- Modify: `src/test/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorServiceTest.kt`

**Reference files (read before implementing):**
- `src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorService.kt` — class under test
- `src/main/kotlin/com/agentwork/graphmesh/rdf/EntityIdGenerator.kt` — generates deterministic URIs
- `src/main/kotlin/com/agentwork/graphmesh/rdf/RdfTerm.kt` — Uri, Literal, QuotedTriple
- `src/main/kotlin/com/agentwork/graphmesh/storage/StoredQuad.kt` — StoredQuad, ObjectType

- [ ] **Step 1: Add MockK imports and extract() tests to existing test file**

Add these imports to the top of the file (after existing imports):

```kotlin
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
```

Add these tests after the existing JSONL parsing tests (before the standalone `parseJsonlDefinitions` method):

```kotlin
    // --- extract() with MockK ---

    @Test
    fun `extract generates knowledge label and provenance quads`() {
        val promptExecutor = mockk<PromptExecutor>()
        val quadStore = mockk<QuadStore>(relaxed = true)
        val librarianService = mockk<LibrarianService>()

        val service = DefinitionExtractorService(promptExecutor, quadStore, librarianService, "gpt-4o")

        every { librarianService.getContent("chunk-1") } returns
            "Photosynthesis is the process by which plants convert sunlight.".toByteArray()

        val llmResponse = """{"entity": "Photosynthesis", "definition": "Process by which plants convert sunlight into chemical energy"}"""
        val message = mockk<Message>()
        every { message.content } returns llmResponse
        coEvery { promptExecutor.execute(any(), any()) } returns listOf(message)

        val result = service.extract("chunk-1", "col-1")

        assertEquals(1, result.definitionsExtracted)
        assertEquals(listOf("Photosynthesis"), result.entitiesFound)
        assertEquals("chunk-1", result.chunkId)

        val quadsSlot = slot<List<StoredQuad>>()
        io.mockk.verify { quadStore.insertBatch("col-1", capture(quadsSlot)) }
        val quads = quadsSlot.captured

        // 1 knowledge + 1 label + 1 provenance = 3 quads
        assertEquals(3, quads.size)

        // Knowledge quad: rdfs:comment
        val knowledgeQuad = quads.first { it.predicate == "http://www.w3.org/2000/01/rdf-schema#comment" }
        assertEquals(EntityIdGenerator.generate("Photosynthesis").value, knowledgeQuad.subject)
        assertEquals("Process by which plants convert sunlight into chemical energy", knowledgeQuad.objectValue)
        assertEquals(ObjectType.LITERAL, knowledgeQuad.objectType)

        // Label quad: rdfs:label
        val labelQuad = quads.first { it.predicate == "http://www.w3.org/2000/01/rdf-schema#label" }
        assertEquals(EntityIdGenerator.generate("Photosynthesis").value, labelQuad.subject)
        assertEquals("Photosynthesis", labelQuad.objectValue)
        assertEquals(ObjectType.LITERAL, labelQuad.objectType)

        // Provenance quad: extractedFrom in SOURCE graph
        val provenanceQuad = quads.first { it.predicate == "http://graphmesh.io/ontology/extractedFrom" }
        assertEquals("urn:chunk:chunk-1", provenanceQuad.objectValue)
        assertEquals("urn:graph:source", provenanceQuad.dataset)
        assertEquals(ObjectType.URI, provenanceQuad.objectType)
    }

    @Test
    fun `extract returns zero result for blank chunk text`() {
        val promptExecutor = mockk<PromptExecutor>()
        val quadStore = mockk<QuadStore>()
        val librarianService = mockk<LibrarianService>()

        val service = DefinitionExtractorService(promptExecutor, quadStore, librarianService, "gpt-4o")

        every { librarianService.getContent("chunk-1") } returns "   ".toByteArray()

        val result = service.extract("chunk-1", "col-1")

        assertEquals(0, result.definitionsExtracted)
        assertTrue(result.entitiesFound.isEmpty())
    }

    @Test
    fun `extract deduplicates label quads by subject`() {
        val promptExecutor = mockk<PromptExecutor>()
        val quadStore = mockk<QuadStore>(relaxed = true)
        val librarianService = mockk<LibrarianService>()

        val service = DefinitionExtractorService(promptExecutor, quadStore, librarianService, "gpt-4o")

        every { librarianService.getContent("chunk-1") } returns "Some text".toByteArray()

        val llmResponse = """
            {"entity": "Photosynthesis", "definition": "Definition one"}
            {"entity": "Photosynthesis", "definition": "Definition two"}
        """.trimIndent()
        val message = mockk<Message>()
        every { message.content } returns llmResponse
        coEvery { promptExecutor.execute(any(), any()) } returns listOf(message)

        service.extract("chunk-1", "col-1")

        val quadsSlot = slot<List<StoredQuad>>()
        io.mockk.verify { quadStore.insertBatch("col-1", capture(quadsSlot)) }
        val quads = quadsSlot.captured

        // 2 knowledge + 1 label (deduplicated) + 2 provenance = 5 quads
        val labelQuads = quads.filter { it.predicate == "http://www.w3.org/2000/01/rdf-schema#label" }
        assertEquals(1, labelQuads.size)
    }
```

- [ ] **Step 2: Run all tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.definition.DefinitionExtractorServiceTest" --info`
Expected: 11 tests PASS (8 parsing + 3 extract)

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorServiceTest.kt
git commit -m "test(definition): add MockK tests for extract() method with quad verification"
```

---

### Task 5: Kafka Consumer

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorConsumer.kt`

**Reference files (read before implementing):**
- `src/main/kotlin/com/agentwork/graphmesh/extraction/relationship/RelationshipExtractorConsumer.kt` — exact pattern to follow

- [ ] **Step 1: Create consumer**

```kotlin
package com.agentwork.graphmesh.extraction.definition

import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class DefinitionExtractorConsumer(
    private val extractorService: DefinitionExtractorService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.chunk.created"], groupId = "graphmesh-definition-extractor")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val chunkId = value["chunkId"].toString()
        val collectionId = value["collectionId"].toString()

        logger.info("Chunk received for definition extraction: chunkId={}", chunkId)

        try {
            val result = extractorService.extract(chunkId, collectionId)
            logger.info(
                "Definition extraction complete: chunkId={}, definitions={}, entities={}",
                chunkId, result.definitionsExtracted, result.entitiesFound.size
            )
        } catch (e: Exception) {
            logger.error("Definition extraction failed for chunk {}", chunkId, e)
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run all definition extractor tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.definition.*" --info`
Expected: 11 tests PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorConsumer.kt
git commit -m "feat(definition): add Kafka consumer for chunk.created events"
```
