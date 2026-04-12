# Feature 38: Topic Extractor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract abstract topics/concepts from text chunks via LLM, store them as SKOS Concepts in the knowledge graph, and optionally match against imported ontologies/taxonomies.

**Architecture:** Follows the existing extractor pattern (DefinitionExtractor). A Kafka consumer receives `chunk.created` events, delegates to `TopicExtractorService` which calls the LLM via Koog `PromptExecutor`, parses JSONL responses, optionally matches against existing SKOS/OWL concepts via `TopicOntologyMatcher`, and stores quads via `QuadStore` with provenance.

**Tech Stack:** Kotlin, Spring Boot, Koog PromptExecutor, Apache Kafka (Avro), Cassandra (via QuadStore), SKOS/RDF vocabulary

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `src/main/kotlin/.../extraction/topic/TopicExtractorModels.kt` | Create | `TopicResult`, `TopicExtractionResult` data classes |
| `src/main/kotlin/.../extraction/topic/TopicPromptTemplate.kt` | Create | System/user prompt generation with optional ontology hints |
| `src/main/kotlin/.../extraction/topic/TopicOntologyMatcher.kt` | Create | Pre-extraction hints + post-extraction URI resolution |
| `src/main/kotlin/.../extraction/topic/TopicExtractorService.kt` | Create | Core extraction logic: LLM call, JSONL parsing, quad generation |
| `src/main/kotlin/.../extraction/topic/TopicExtractorConsumer.kt` | Create | Kafka consumer on `graphmesh.chunk.created` |
| `src/main/resources/application.yml` | Modify | Add `graphmesh.extraction.topic.minConfidence` |
| `src/test/kotlin/.../extraction/topic/TopicJsonlParsingTest.kt` | Create | JSONL edge-case tests |
| `src/test/kotlin/.../extraction/topic/TopicDeduplicationTest.kt` | Create | Normalization + dedup tests |
| `src/test/kotlin/.../extraction/topic/TopicPromptTemplateTest.kt` | Create | Template consistency tests |
| `src/test/kotlin/.../extraction/topic/TopicOntologyMatcherTest.kt` | Create | Ontology matching tests |
| `src/test/kotlin/.../extraction/topic/TopicExtractorServiceTest.kt` | Create | Full extraction flow tests with mocks |

Base package path: `com/agentwork/graphmesh`

---

### Task 1: Data Models

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorModels.kt`

- [ ] **Step 1: Create TopicExtractorModels.kt**

```kotlin
package com.agentwork.graphmesh.extraction.topic

data class TopicResult(
    val topic: String,
    val confidence: Double,
    val rationale: String? = null
)

data class TopicExtractionResult(
    val chunkId: String,
    val topicsExtracted: Int,
    val topics: List<String>
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorModels.kt
git commit -m "feat(topic): add TopicResult and TopicExtractionResult data models"
```

---

### Task 2: Prompt Template

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicPromptTemplate.kt`
- Test: `src/test/kotlin/com/agentwork/graphmesh/extraction/topic/TopicPromptTemplateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.agentwork.graphmesh.extraction.topic

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicPromptTemplateTest {

    @Test
    fun `systemPrompt without hints contains JSONL instruction`() {
        val prompt = TopicPromptTemplate.systemPrompt()
        assertContains(prompt, "JSONL")
        assertContains(prompt, "topic")
        assertContains(prompt, "confidence")
    }

    @Test
    fun `systemPrompt without hints does not contain preferred concepts block`() {
        val prompt = TopicPromptTemplate.systemPrompt()
        assertFalse(prompt.contains("Bevorzuge folgende"))
    }

    @Test
    fun `systemPrompt with hints includes preferred concepts`() {
        val hints = listOf("Insolvenzrecht", "Photosynthese", "EU-Datenschutz")
        val prompt = TopicPromptTemplate.systemPrompt(hints)
        assertContains(prompt, "Bevorzuge folgende")
        assertContains(prompt, "Insolvenzrecht")
        assertContains(prompt, "Photosynthese")
        assertContains(prompt, "EU-Datenschutz")
    }

    @Test
    fun `systemPrompt with empty hints does not contain preferred concepts block`() {
        val prompt = TopicPromptTemplate.systemPrompt(emptyList())
        assertFalse(prompt.contains("Bevorzuge folgende"))
    }

    @Test
    fun `userPrompt contains chunk text`() {
        val prompt = TopicPromptTemplate.userPrompt("Dies ist ein Testtext.")
        assertContains(prompt, "Dies ist ein Testtext.")
        assertContains(prompt, "JSONL")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.topic.TopicPromptTemplateTest"`
Expected: FAIL — `TopicPromptTemplate` not found

- [ ] **Step 3: Implement TopicPromptTemplate.kt**

```kotlin
package com.agentwork.graphmesh.extraction.topic

object TopicPromptTemplate {

    fun systemPrompt(hints: List<String> = emptyList()): String {
        val base = """
            Du bist ein Wissensextraktions-Assistent. Deine Aufgabe ist es,
            die **Themen** zu identifizieren, die einen Text inhaltlich praegen.

            Themen sind abstrakte Konzepte oder Sachgebiete -- NICHT einzelne
            Entitaeten. Beispiele:
              richtig:  "Insolvenzrecht", "Photosynthese", "EU-Datenschutz"
              falsch:   "Angela Merkel", "Berlin", "BMW AG" (das sind Entitaeten)

            Extrahiere fuer jedes Thema ein JSON-Objekt pro Zeile im JSONL-Format:
              {"topic": "<Thema>", "confidence": <0.0..1.0>, "rationale": "<kurzer Grund>"}

            Regeln:
              - Maximal 5 Themen pro Text, nur die wichtigsten.
              - `confidence` spiegelt wider, wie deutlich das Thema im Text auftritt.
              - `rationale` ist ein kurzer Halbsatz (max. 10 Woerter).
              - Verwende kanonische, wiederverwendbare Bezeichnungen.
              - KEINE Entitaeten, Personen, Orte, Firmen.
              - Jedes JSON-Objekt auf einer eigenen Zeile, kein Markdown.
        """.trimIndent()

        if (hints.isEmpty()) return base

        val hintsBlock = """

            Bevorzuge folgende bekannte Konzepte, falls sie zum Text passen:
            ${hints.joinToString("\n") { "  - $it" }}
        """.trimIndent()

        return base + "\n\n" + hintsBlock
    }

    fun userPrompt(chunkText: String): String = """
        Extrahiere die Themen aus folgendem Text:

        ---
        $chunkText
        ---

        Antworte NUR mit JSON-Objekten im JSONL-Format, eines pro Zeile.
    """.trimIndent()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.topic.TopicPromptTemplateTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicPromptTemplate.kt \
       src/test/kotlin/com/agentwork/graphmesh/extraction/topic/TopicPromptTemplateTest.kt
git commit -m "feat(topic): add TopicPromptTemplate with optional ontology hints"
```

---

### Task 3: JSONL Parsing Tests

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/extraction/topic/TopicJsonlParsingTest.kt`

This task tests the JSONL parsing logic that will be implemented in Task 5 (TopicExtractorService). The test uses a standalone copy of the parsing function (same pattern as `DefinitionExtractorServiceTest`).

- [ ] **Step 1: Write TopicJsonlParsingTest.kt**

```kotlin
package com.agentwork.graphmesh.extraction.topic

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopicJsonlParsingTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `parses valid JSONL topics`() {
        val response = """
            {"topic": "Insolvenzrecht", "confidence": 0.95, "rationale": "Hauptthema des Textes"}
            {"topic": "Glaeubigerschutz", "confidence": 0.7, "rationale": "Nebenthema"}
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(2, results.size)
        assertEquals("Insolvenzrecht", results[0].topic)
        assertEquals(0.95, results[0].confidence)
        assertEquals("Hauptthema des Textes", results[0].rationale)
        assertEquals("Glaeubigerschutz", results[1].topic)
    }

    @Test
    fun `skips blank lines`() {
        val response = """
            {"topic": "A", "confidence": 0.9, "rationale": "r"}

            {"topic": "B", "confidence": 0.8, "rationale": "r"}
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `skips invalid JSON lines`() {
        val response = """
            {"topic": "A", "confidence": 0.9, "rationale": "r"}
            This is not JSON
            {"topic": "B", "confidence": 0.8, "rationale": "r"}
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `skips lines with missing topic field`() {
        val response = """
            {"topic": "A", "confidence": 0.9, "rationale": "r"}
            {"confidence": 0.8, "rationale": "missing topic"}
            {"topic": "C", "confidence": 0.7, "rationale": "r"}
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(2, results.size)
        assertEquals("A", results[0].topic)
        assertEquals("C", results[1].topic)
    }

    @Test
    fun `skips lines with blank topic`() {
        val response = """
            {"topic": "", "confidence": 0.9, "rationale": "r"}
            {"topic": "B", "confidence": 0.8, "rationale": "r"}
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(1, results.size)
        assertEquals("B", results[0].topic)
    }

    @Test
    fun `defaults confidence to 1_0 when missing`() {
        val response = """{"topic": "A", "rationale": "r"}"""
        val results = parseJsonlTopics(response)
        assertEquals(1, results.size)
        assertEquals(1.0, results[0].confidence)
    }

    @Test
    fun `clamps confidence to 0_0 to 1_0`() {
        val response = """
            {"topic": "A", "confidence": 1.5, "rationale": "r"}
            {"topic": "B", "confidence": -0.3, "rationale": "r"}
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(1.0, results[0].confidence)
        assertEquals(0.0, results[1].confidence)
    }

    @Test
    fun `rationale is optional`() {
        val response = """{"topic": "A", "confidence": 0.9}"""
        val results = parseJsonlTopics(response)
        assertEquals(1, results.size)
        assertEquals(null, results[0].rationale)
    }

    @Test
    fun `strips markdown code fences`() {
        val response = """
            ```json
            {"topic": "A", "confidence": 0.9, "rationale": "r"}
            {"topic": "B", "confidence": 0.8, "rationale": "r"}
            ```
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `handles empty response`() {
        val results = parseJsonlTopics("")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `handles truncated last line`() {
        val response = """
            {"topic": "A", "confidence": 0.9, "rationale": "r"}
            {"topic": "B", "confiden
        """.trimIndent()

        val results = parseJsonlTopics(response)
        assertEquals(1, results.size)
        assertEquals("A", results[0].topic)
    }

    // Standalone copy of parsing logic (same pattern as DefinitionExtractorServiceTest)
    private fun parseJsonlTopics(llmResponse: String): List<TopicResult> =
        llmResponse.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val map = objectMapper.readValue<Map<String, Any>>(line)
                    val topic = (map["topic"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val confidence = (map["confidence"] as? Number)?.toDouble() ?: 1.0
                    val rationale = map["rationale"] as? String
                    TopicResult(topic, confidence.coerceIn(0.0, 1.0), rationale)
                } catch (_: Exception) {
                    null
                }
            }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.topic.TopicJsonlParsingTest"`
Expected: PASS (the test uses its own standalone parsing copy and the `TopicResult` model from Task 1)

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/extraction/topic/TopicJsonlParsingTest.kt
git commit -m "test(topic): add JSONL parsing edge-case tests"
```

---

### Task 4: Deduplication Tests

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/extraction/topic/TopicDeduplicationTest.kt`

- [ ] **Step 1: Write TopicDeduplicationTest.kt**

```kotlin
package com.agentwork.graphmesh.extraction.topic

import com.agentwork.graphmesh.rdf.EntityIdGenerator
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TopicDeduplicationTest {

    @Test
    fun `normalize trims whitespace`() {
        assertEquals("insolvenzrecht", normalize("  Insolvenzrecht  "))
    }

    @Test
    fun `normalize lowercases`() {
        assertEquals("insolvenzrecht", normalize("INSOLVENZRECHT"))
    }

    @Test
    fun `normalize collapses multiple spaces`() {
        assertEquals("eu datenschutz", normalize("EU   Datenschutz"))
    }

    @Test
    fun `normalize handles mixed whitespace`() {
        assertEquals("a b c", normalize("  A  B  C  "))
    }

    @Test
    fun `same normalized label produces same EntityId`() {
        val id1 = EntityIdGenerator.generate(normalize("Insolvenzrecht"))
        val id2 = EntityIdGenerator.generate(normalize("insolvenzrecht"))
        val id3 = EntityIdGenerator.generate(normalize("  INSOLVENZRECHT  "))
        assertEquals(id1, id2)
        assertEquals(id2, id3)
    }

    @Test
    fun `distinctBy normalized label removes duplicates`() {
        val topics = listOf(
            TopicResult("Insolvenzrecht", 0.9),
            TopicResult("insolvenzrecht", 0.7),
            TopicResult("Photosynthese", 0.8)
        )

        val deduped = topics.distinctBy { normalize(it.topic) }
        assertEquals(2, deduped.size)
        assertEquals("Insolvenzrecht", deduped[0].topic)
        assertEquals("Photosynthese", deduped[1].topic)
    }

    // Standalone copy of normalize (will be in TopicExtractorService)
    private fun normalize(topic: String): String =
        topic.trim().lowercase().replace(Regex("\\s+"), " ")
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.topic.TopicDeduplicationTest"`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/extraction/topic/TopicDeduplicationTest.kt
git commit -m "test(topic): add normalization and deduplication tests"
```

---

### Task 5: TopicOntologyMatcher

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicOntologyMatcher.kt`
- Test: `src/test/kotlin/com/agentwork/graphmesh/extraction/topic/TopicOntologyMatcherTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.agentwork.graphmesh.extraction.topic

import com.agentwork.graphmesh.ontology.LangLabel
import com.agentwork.graphmesh.ontology.Ontology
import com.agentwork.graphmesh.ontology.OntologyClass
import com.agentwork.graphmesh.ontology.OntologyMetadata
import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.skos.SkosConcept
import com.agentwork.graphmesh.skos.SkosConceptScheme
import com.agentwork.graphmesh.skos.SkosService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopicOntologyMatcherTest {

    private val ontologyService = mockk<OntologyService>()
    private val skosService = mockk<SkosService>()
    private val matcher = TopicOntologyMatcher(ontologyService, skosService)

    @Test
    fun `getHints returns empty list when no ontology and no SKOS schemes`() {
        every { ontologyService.get("col-1") } returns null
        every { skosService.getConceptSchemes("col-1") } returns emptyList()

        val hints = matcher.getHints("col-1")
        assertTrue(hints.isEmpty())
    }

    @Test
    fun `getHints returns OWL class labels`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Insolvenzrecht" to OntologyClass(
                    id = "Insolvenzrecht",
                    uri = "http://test.org/Insolvenzrecht",
                    labels = listOf(LangLabel("Insolvenzrecht", "de"))
                ),
                "Datenschutz" to OntologyClass(
                    id = "Datenschutz",
                    uri = "http://test.org/Datenschutz",
                    labels = listOf(LangLabel("Datenschutz", "de"))
                )
            )
        )
        every { ontologyService.get("col-1") } returns ontology
        every { skosService.getConceptSchemes("col-1") } returns emptyList()

        val hints = matcher.getHints("col-1")
        assertEquals(2, hints.size)
        assertTrue(hints.contains("Insolvenzrecht"))
        assertTrue(hints.contains("Datenschutz"))
    }

    @Test
    fun `getHints returns SKOS concept prefLabels`() {
        every { ontologyService.get("col-1") } returns null
        every { skosService.getConceptSchemes("col-1") } returns listOf(
            SkosConceptScheme(uri = "urn:scheme:1", prefLabels = listOf(LangLabel("Test Scheme")), collectionId = "col-1")
        )
        every { skosService.getConcepts("col-1", "urn:scheme:1") } returns listOf(
            SkosConcept(uri = "urn:concept:photo", prefLabels = listOf(LangLabel("Photosynthese", "de")), collectionId = "col-1"),
            SkosConcept(uri = "urn:concept:bio", prefLabels = listOf(LangLabel("Biologie", "de")), collectionId = "col-1")
        )

        val hints = matcher.getHints("col-1")
        assertEquals(2, hints.size)
        assertTrue(hints.contains("Photosynthese"))
        assertTrue(hints.contains("Biologie"))
    }

    @Test
    fun `getHints deduplicates across OWL and SKOS`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Photosynthese" to OntologyClass(
                    id = "Photosynthese",
                    uri = "http://test.org/Photosynthese",
                    labels = listOf(LangLabel("Photosynthese", "de"))
                )
            )
        )
        every { ontologyService.get("col-1") } returns ontology
        every { skosService.getConceptSchemes("col-1") } returns listOf(
            SkosConceptScheme(uri = "urn:scheme:1", prefLabels = listOf(LangLabel("S")), collectionId = "col-1")
        )
        every { skosService.getConcepts("col-1", "urn:scheme:1") } returns listOf(
            SkosConcept(uri = "urn:concept:photo", prefLabels = listOf(LangLabel("Photosynthese", "de")), collectionId = "col-1")
        )

        val hints = matcher.getHints("col-1")
        assertEquals(1, hints.size)
    }

    @Test
    fun `getHints limits to 50 labels`() {
        val classes = (1..60).associate { i ->
            "Class$i" to OntologyClass(
                id = "Class$i",
                uri = "http://test.org/Class$i",
                labels = listOf(LangLabel("Label$i"))
            )
        }
        every { ontologyService.get("col-1") } returns Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = classes
        )
        every { skosService.getConceptSchemes("col-1") } returns emptyList()

        val hints = matcher.getHints("col-1")
        assertEquals(50, hints.size)
    }

    @Test
    fun `resolveOrCreate returns existing SKOS concept URI on match`() {
        every { skosService.findByLabel("col-1", "insolvenzrecht") } returns listOf(
            SkosConcept(
                uri = "urn:concept:existing",
                prefLabels = listOf(LangLabel("insolvenzrecht", "de")),
                collectionId = "col-1"
            )
        )

        val uri = matcher.resolveOrCreate("Insolvenzrecht", "col-1")
        assertEquals("urn:concept:existing", uri.value)
    }

    @Test
    fun `resolveOrCreate falls back to EntityIdGenerator when no match`() {
        every { skosService.findByLabel("col-1", "neues thema") } returns emptyList()

        val uri = matcher.resolveOrCreate("Neues Thema", "col-1")
        assertEquals(EntityIdGenerator.generate("neues thema"), uri)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.topic.TopicOntologyMatcherTest"`
Expected: FAIL — `TopicOntologyMatcher` not found

- [ ] **Step 3: Implement TopicOntologyMatcher.kt**

```kotlin
package com.agentwork.graphmesh.extraction.topic

import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.rdf.RdfTerm
import com.agentwork.graphmesh.skos.SkosService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class TopicOntologyMatcher(
    private val ontologyService: OntologyService,
    private val skosService: SkosService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun getHints(collectionId: String): List<String> {
        val labels = mutableSetOf<String>()

        // OWL classes
        val ontology = ontologyService.get(collectionId)
        if (ontology != null) {
            for ((_, cls) in ontology.classes) {
                cls.labels.firstOrNull()?.let { labels.add(it.value) }
            }
        }

        // SKOS concepts
        val schemes = skosService.getConceptSchemes(collectionId)
        for (scheme in schemes) {
            val concepts = skosService.getConcepts(collectionId, scheme.uri)
            for (concept in concepts) {
                concept.prefLabels.firstOrNull()?.let { labels.add(it.value) }
            }
        }

        return labels.take(50)
    }

    fun resolveOrCreate(label: String, collectionId: String): RdfTerm.Uri {
        val normalized = normalize(label)
        val matches = skosService.findByLabel(collectionId, normalized)
        val exactMatch = matches.firstOrNull { concept ->
            concept.prefLabels.any { it.value.lowercase().trim() == normalized }
        }
        if (exactMatch != null) {
            logger.debug("Matched topic '{}' to existing concept {}", label, exactMatch.uri)
            return RdfTerm.Uri(exactMatch.uri)
        }
        return EntityIdGenerator.generate(normalized)
    }

    private fun normalize(topic: String): String =
        topic.trim().lowercase().replace(Regex("\\s+"), " ")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.topic.TopicOntologyMatcherTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicOntologyMatcher.kt \
       src/test/kotlin/com/agentwork/graphmesh/extraction/topic/TopicOntologyMatcherTest.kt
git commit -m "feat(topic): add TopicOntologyMatcher with OWL/SKOS hint collection and URI resolution"
```

---

### Task 6: TopicExtractorService

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorService.kt`
- Test: `src/test/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorServiceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.agentwork.graphmesh.extraction.topic

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.provenance.ProvenanceService
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.rdf.SkosTypes
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopicExtractorServiceTest {

    private val promptExecutor = mockk<PromptExecutor>()
    private val quadStore = mockk<QuadStore>(relaxed = true)
    private val librarianService = mockk<LibrarianService>()
    private val provenanceService = mockk<ProvenanceService>(relaxed = true)
    private val ontologyMatcher = mockk<TopicOntologyMatcher>()

    private fun buildService(minConfidence: Double = 0.5): TopicExtractorService =
        TopicExtractorService(
            promptExecutor = promptExecutor,
            quadStore = quadStore,
            librarianService = librarianService,
            provenanceService = provenanceService,
            ontologyMatcher = ontologyMatcher,
            modelName = "gpt-4o",
            minConfidence = minConfidence
        )

    @Test
    fun `extract generates type label subject and confidence quads`() {
        every { provenanceService.buildSubgraphQuads(any()) } returns emptyList()
        every { ontologyMatcher.getHints("col-1") } returns emptyList()
        every { ontologyMatcher.resolveOrCreate(any(), eq("col-1")) } answers {
            EntityIdGenerator.generate(firstArg<String>().trim().lowercase().replace(Regex("\\s+"), " "))
        }
        every { librarianService.getContent("chunk-1") } returns
            "Text about Insolvenzrecht and related topics.".toByteArray()

        val llmResponse = """{"topic": "Insolvenzrecht", "confidence": 0.95, "rationale": "Hauptthema"}"""
        val message = mockk<Message.Response>()
        every { message.content } returns llmResponse
        coEvery { promptExecutor.execute(any(), any()) } returns listOf(message)

        val service = buildService()
        val result = service.extract("chunk-1", "col-1")

        assertEquals(1, result.topicsExtracted)
        assertEquals(listOf("Insolvenzrecht"), result.topics)

        val quadsSlot = slot<List<StoredQuad>>()
        io.mockk.verify { quadStore.insertBatch("col-1", capture(quadsSlot)) }
        val quads = quadsSlot.captured

        // rdf:type quad
        val typeQuad = quads.first { it.predicate == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" }
        assertEquals(SkosTypes.CONCEPT, typeQuad.objectValue)
        assertEquals(ObjectType.URI, typeQuad.objectType)

        // rdfs:label quad
        val labelQuad = quads.first { it.predicate == "http://www.w3.org/2000/01/rdf-schema#label" }
        assertEquals("Insolvenzrecht", labelQuad.objectValue)
        assertEquals(ObjectType.LITERAL, labelQuad.objectType)

        // dct:subject quad
        val subjectQuad = quads.first { it.predicate == "http://purl.org/dc/terms/subject" }
        assertEquals("urn:chunk:chunk-1", subjectQuad.subject)

        // confidence quoted triple
        val confidenceQuad = quads.first { it.predicate == "http://graphmesh.io/ontology/topicConfidence" }
        assertEquals("0.95", confidenceQuad.objectValue)
        assertEquals(ObjectType.LITERAL, confidenceQuad.objectType)
        assertEquals(ObjectType.QUOTED_TRIPLE, quads.first { it.predicate == "http://graphmesh.io/ontology/topicConfidence" }.let {
            // The subject of the confidence quad is a quoted triple
            quads.first { q -> q.objectType == ObjectType.QUOTED_TRIPLE }.objectType
        })
    }

    @Test
    fun `extract returns zero result for blank chunk text`() {
        every { librarianService.getContent("chunk-1") } returns "   ".toByteArray()

        val service = buildService()
        val result = service.extract("chunk-1", "col-1")

        assertEquals(0, result.topicsExtracted)
        assertTrue(result.topics.isEmpty())
    }

    @Test
    fun `extract filters topics below minConfidence`() {
        every { provenanceService.buildSubgraphQuads(any()) } returns emptyList()
        every { ontologyMatcher.getHints("col-1") } returns emptyList()
        every { ontologyMatcher.resolveOrCreate(any(), eq("col-1")) } answers {
            EntityIdGenerator.generate(firstArg<String>().trim().lowercase().replace(Regex("\\s+"), " "))
        }
        every { librarianService.getContent("chunk-1") } returns "Some text".toByteArray()

        val llmResponse = """
            {"topic": "HighConf", "confidence": 0.9, "rationale": "r"}
            {"topic": "LowConf", "confidence": 0.3, "rationale": "r"}
        """.trimIndent()
        val message = mockk<Message.Response>()
        every { message.content } returns llmResponse
        coEvery { promptExecutor.execute(any(), any()) } returns listOf(message)

        val service = buildService(minConfidence = 0.5)
        val result = service.extract("chunk-1", "col-1")

        assertEquals(1, result.topicsExtracted)
        assertEquals(listOf("HighConf"), result.topics)
    }

    @Test
    fun `extract deduplicates topics by normalized label`() {
        every { provenanceService.buildSubgraphQuads(any()) } returns emptyList()
        every { ontologyMatcher.getHints("col-1") } returns emptyList()
        every { ontologyMatcher.resolveOrCreate(any(), eq("col-1")) } answers {
            EntityIdGenerator.generate(firstArg<String>().trim().lowercase().replace(Regex("\\s+"), " "))
        }
        every { librarianService.getContent("chunk-1") } returns "Some text".toByteArray()

        val llmResponse = """
            {"topic": "Insolvenzrecht", "confidence": 0.9, "rationale": "r"}
            {"topic": "insolvenzrecht", "confidence": 0.7, "rationale": "r"}
            {"topic": "Photosynthese", "confidence": 0.8, "rationale": "r"}
        """.trimIndent()
        val message = mockk<Message.Response>()
        every { message.content } returns llmResponse
        coEvery { promptExecutor.execute(any(), any()) } returns listOf(message)

        val service = buildService()
        val result = service.extract("chunk-1", "col-1")

        assertEquals(2, result.topicsExtracted)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.topic.TopicExtractorServiceTest"`
Expected: FAIL — `TopicExtractorService` not found

- [ ] **Step 3: Implement TopicExtractorService.kt**

```kotlin
package com.agentwork.graphmesh.extraction.topic

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.llm.resolveLlmModel
import com.agentwork.graphmesh.provenance.ProvenanceService
import com.agentwork.graphmesh.provenance.SubgraphProvenance
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.Quad
import com.agentwork.graphmesh.rdf.QuadConverter
import com.agentwork.graphmesh.rdf.RdfTerm
import com.agentwork.graphmesh.rdf.SkosTypes
import com.agentwork.graphmesh.storage.QuadStore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class TopicExtractorService(
    private val promptExecutor: PromptExecutor,
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService,
    private val provenanceService: ProvenanceService,
    private val ontologyMatcher: TopicOntologyMatcher,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val modelName: String,
    @Value("\${graphmesh.extraction.topic.minConfidence:0.5}") private val minConfidence: Double
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    companion object {
        private const val RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
        private const val RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label"
        private const val DCT_SUBJECT = "http://purl.org/dc/terms/subject"
        private const val TOPIC_CONFIDENCE = "http://graphmesh.io/ontology/topicConfidence"
    }

    fun extract(chunkId: String, collectionId: String): TopicExtractionResult {
        val content = librarianService.getContent(chunkId)
        val chunkText = String(content, Charsets.UTF_8)
        if (chunkText.isBlank()) {
            return TopicExtractionResult(chunkId, 0, emptyList())
        }

        val hints = ontologyMatcher.getHints(collectionId)

        val extractionPrompt = prompt("topic-extraction") {
            system(TopicPromptTemplate.systemPrompt(hints))
            user(TopicPromptTemplate.userPrompt(chunkText))
        }

        val llmResponse = runBlocking {
            promptExecutor.execute(extractionPrompt, resolveLlmModel(modelName))
        }
        val responseText = llmResponse.first().content

        val topics = parseJsonlTopics(responseText)
            .filter { it.confidence >= minConfidence }
            .distinctBy { normalize(it.topic) }

        if (topics.isEmpty()) {
            logger.debug("No topics extracted from chunk {}", chunkId)
            return TopicExtractionResult(chunkId, 0, emptyList())
        }

        val chunkUri = RdfTerm.Uri("urn:chunk:$chunkId")
        val knowledgeQuads = mutableListOf<Quad>()

        for (t in topics) {
            val topicId = ontologyMatcher.resolveOrCreate(t.topic, collectionId)

            knowledgeQuads += Quad(topicId, RdfTerm.Uri(RDF_TYPE), RdfTerm.Uri(SkosTypes.CONCEPT), NamedGraph.DEFAULT)
            knowledgeQuads += Quad(topicId, RdfTerm.Uri(RDFS_LABEL), RdfTerm.Literal(t.topic), NamedGraph.DEFAULT)
            knowledgeQuads += Quad(chunkUri, RdfTerm.Uri(DCT_SUBJECT), topicId, NamedGraph.DEFAULT)

            val assignment = Quad(chunkUri, RdfTerm.Uri(DCT_SUBJECT), topicId, NamedGraph.DEFAULT)
            knowledgeQuads += Quad(
                subject = RdfTerm.QuotedTriple(assignment.triple),
                predicate = RdfTerm.Uri(TOPIC_CONFIDENCE),
                objectTerm = RdfTerm.Literal(t.confidence.toString()),
                graph = NamedGraph.DEFAULT
            )
        }

        val dedupedKnowledge = knowledgeQuads.distinctBy {
            "${it.subject.toNTriples()}|${it.predicate.toNTriples()}|${it.objectTerm.toNTriples()}|${it.graph}"
        }

        val provenanceQuads = provenanceService.buildSubgraphQuads(
            SubgraphProvenance(
                extractedTriples = dedupedKnowledge.map { it.triple },
                chunkUri = "urn:chunk:$chunkId",
                agentLabel = "TopicExtractor",
                modelName = modelName
            )
        )

        val allStored = dedupedKnowledge.map { QuadConverter.toStoredQuad(it) } +
            provenanceQuads.map { QuadConverter.toStoredQuad(it) }
        quadStore.insertBatch(collectionId, allStored)

        logger.info("Extracted {} topics from chunk {}", topics.size, chunkId)
        return TopicExtractionResult(
            chunkId = chunkId,
            topicsExtracted = topics.size,
            topics = topics.map { it.topic }
        )
    }

    private fun normalize(topic: String): String =
        topic.trim().lowercase().replace(Regex("\\s+"), " ")

    internal fun parseJsonlTopics(llmResponse: String): List<TopicResult> =
        llmResponse.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val map = objectMapper.readValue<Map<String, Any>>(line)
                    val topic = (map["topic"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val confidence = (map["confidence"] as? Number)?.toDouble() ?: 1.0
                    val rationale = map["rationale"] as? String
                    TopicResult(topic, confidence.coerceIn(0.0, 1.0), rationale)
                } catch (_: Exception) {
                    null
                }
            }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.topic.TopicExtractorServiceTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorService.kt \
       src/test/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorServiceTest.kt
git commit -m "feat(topic): add TopicExtractorService with LLM extraction, dedup, and provenance"
```

---

### Task 7: Kafka Consumer

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorConsumer.kt`

- [ ] **Step 1: Create TopicExtractorConsumer.kt**

```kotlin
package com.agentwork.graphmesh.extraction.topic

import com.agentwork.graphmesh.librarian.DocumentNotFoundException
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class TopicExtractorConsumer(
    private val extractorService: TopicExtractorService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.chunk.created"], groupId = "graphmesh-topic-extractor")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val chunkId = value["chunkId"].toString()
        val collectionId = value["collectionId"].toString()

        logger.info("Chunk received for topic extraction: chunkId={}", chunkId)

        try {
            val result = extractorService.extract(chunkId, collectionId)
            logger.info(
                "Topic extraction complete: chunkId={}, topics={}",
                chunkId, result.topicsExtracted
            )
        } catch (e: DocumentNotFoundException) {
            logger.info("Skip topic extraction for chunk {}: deleted before processing", chunkId)
        } catch (e: Exception) {
            logger.error("Topic extraction failed for chunk {}", chunkId, e)
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorConsumer.kt
git commit -m "feat(topic): add Kafka consumer for chunk.created events"
```

---

### Task 8: Configuration

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add topic extraction config to application.yml**

After the existing `graphmesh.extraction.model` line, add the topic config block:

```yaml
graphmesh:
  extraction:
    model: ${LLM_EXTRACTION_MODEL:gpt-4o-mini}
    topic:
      minConfidence: 0.5
```

This means adding the following lines after line 91 (`model: ${LLM_EXTRACTION_MODEL:gpt-4o-mini}`):

```yaml
    topic:
      minConfidence: 0.5
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "feat(topic): add topic extraction config to application.yml"
```

---

### Task 9: Full Build Verification

- [ ] **Step 1: Run full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — all tests pass, no compilation errors

- [ ] **Step 2: Verify all topic tests pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.topic.*"`
Expected: All 5 test classes pass

---

## Summary

| Task | Component | Tests |
|---|---|---|
| 1 | TopicExtractorModels | — |
| 2 | TopicPromptTemplate | TopicPromptTemplateTest |
| 3 | — | TopicJsonlParsingTest |
| 4 | — | TopicDeduplicationTest |
| 5 | TopicOntologyMatcher | TopicOntologyMatcherTest |
| 6 | TopicExtractorService | TopicExtractorServiceTest |
| 7 | TopicExtractorConsumer | — |
| 8 | application.yml config | — |
| 9 | Full build verification | — |
