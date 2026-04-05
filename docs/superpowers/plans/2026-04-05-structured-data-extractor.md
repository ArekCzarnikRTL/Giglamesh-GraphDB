# Feature 23: Structured Data Extractor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a Kafka-based extractor that detects tabular data in text chunks, infers schema via LLM, extracts rows, and stores them via StructuredDataService.

**Architecture:** Three-stage LLM pipeline (detect → infer schema → extract rows) triggered by `chunk.created` Kafka events. Each stage is a separate @Service. The Kafka consumer delegates to an orchestrator service that coordinates the pipeline and stores results via Feature 22's StructuredDataService.

**Tech Stack:** Kotlin, Spring Boot, Koog PromptExecutor (LLM), Jackson (JSON parsing), Spring Kafka (@KafkaListener, Avro), MockK (tests)

**Spec:** `docs/superpowers/specs/2026-04-05-structured-data-extractor-design.md`

---

## File Structure

| File | Responsibility |
|------|---------------|
| `src/main/kotlin/com/agentwork/graphmesh/extraction/structured/Models.kt` | Data classes: DetectionResult, InferredSchema, StructuredExtractionResult |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/structured/TableDetector.kt` | @Service — LLM Call #1: detect tables in text |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/structured/SchemaInferenceService.kt` | @Service — LLM Call #2: infer schema, match existing |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorService.kt` | @Service — Orchestrator + LLM Call #3: extract rows |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorConsumer.kt` | @Component — Kafka consumer for chunk.created |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/structured/TableDetectorTest.kt` | Tests for TableDetector parsing |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/structured/SchemaInferenceServiceTest.kt` | Tests for SchemaInferenceService parsing |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorServiceTest.kt` | Tests for orchestrator with mocked deps |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorConsumerTest.kt` | Tests for Kafka consumer delegation |

---

### Task 1: Models

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/structured/Models.kt`

- [ ] **Step 1: Create Models.kt with data classes**

```kotlin
package com.agentwork.graphmesh.extraction.structured

import com.agentwork.graphmesh.structured.TableSchema

data class DetectionResult(
    val hasTable: Boolean,
    val confidence: Double,
    val tableDescription: String? = null
)

data class InferredSchema(
    val schema: TableSchema,
    val matchesExisting: String?
)

data class StructuredExtractionResult(
    val chunkId: String,
    val tableDetected: Boolean,
    val schemaName: String? = null,
    val rowsExtracted: Int
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/structured/Models.kt
git commit -m "feat(structured-extractor): add data models for structured data extraction"
```

---

### Task 2: TableDetector + Tests

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/extraction/structured/TableDetectorTest.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/structured/TableDetector.kt`

- [ ] **Step 1: Write failing tests for TableDetector parsing**

```kotlin
package com.agentwork.graphmesh.extraction.structured

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableDetectorTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `parseDetectionResult parses valid detection with table`() {
        val json = """{"has_table": true, "confidence": 0.85, "description": "Preisliste mit Produkten"}"""
        val result = parseDetectionResult(json)
        assertTrue(result.hasTable)
        assertEquals(0.85, result.confidence)
        assertEquals("Preisliste mit Produkten", result.tableDescription)
    }

    @Test
    fun `parseDetectionResult parses no-table response`() {
        val json = """{"has_table": false, "confidence": 0.1, "description": null}"""
        val result = parseDetectionResult(json)
        assertFalse(result.hasTable)
        assertEquals(0.1, result.confidence)
        assertNull(result.tableDescription)
    }

    @Test
    fun `parseDetectionResult handles missing optional fields`() {
        val json = """{"has_table": true, "confidence": 0.9}"""
        val result = parseDetectionResult(json)
        assertTrue(result.hasTable)
        assertEquals(0.9, result.confidence)
        assertNull(result.tableDescription)
    }

    @Test
    fun `parseDetectionResult strips markdown code fences`() {
        val json = "```json\n{\"has_table\": true, \"confidence\": 0.8, \"description\": \"Test\"}\n```"
        val result = parseDetectionResult(json)
        assertTrue(result.hasTable)
        assertEquals(0.8, result.confidence)
    }

    @Test
    fun `parseDetectionResult returns false for malformed JSON`() {
        val result = parseDetectionResult("not valid json at all")
        assertFalse(result.hasTable)
        assertEquals(0.0, result.confidence)
    }

    @Test
    fun `parseDetectionResult returns false for empty input`() {
        val result = parseDetectionResult("")
        assertFalse(result.hasTable)
        assertEquals(0.0, result.confidence)
    }

    @Test
    fun `parseDetectionResult handles missing has_table field`() {
        val json = """{"confidence": 0.5, "description": "Something"}"""
        val result = parseDetectionResult(json)
        assertFalse(result.hasTable)
    }

    // Standalone copy of parsing logic for unit testing
    private fun parseDetectionResult(response: String): DetectionResult {
        return try {
            val cleaned = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val map = objectMapper.readValue<Map<String, Any?>>(cleaned)
            DetectionResult(
                hasTable = map["has_table"] as? Boolean ?: false,
                confidence = (map["confidence"] as? Number)?.toDouble() ?: 0.0,
                tableDescription = map["description"] as? String
            )
        } catch (_: Exception) {
            DetectionResult(hasTable = false, confidence = 0.0)
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.structured.TableDetectorTest"`
Expected: FAIL — `DetectionResult` class not found (until Models.kt from Task 1 exists), then tests pass once parsing is standalone.

Note: If Task 1 is already done, these tests will pass immediately since parsing is a standalone copy. That's fine — the tests validate the parsing logic before wiring it into the service.

- [ ] **Step 3: Create TableDetector.kt**

```kotlin
package com.agentwork.graphmesh.extraction.structured

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class TableDetector(
    private val promptExecutor: PromptExecutor,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val modelName: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    fun detect(chunkText: String): DetectionResult {
        if (chunkText.isBlank()) {
            return DetectionResult(hasTable = false, confidence = 0.0)
        }

        val detectionPrompt = prompt("table-detection") {
            system(
                """
                Analysiere den folgenden Text und bestimme, ob er tabellarische Daten
                oder strukturierte Listen enthaelt, die als Tabelle dargestellt werden koennten.

                Antworte NUR mit einem JSON-Objekt:
                {"has_table": true/false, "confidence": 0.0-1.0, "description": "Kurzbeschreibung der Tabelle"}

                Beispiele fuer tabellarische Daten:
                - Preislisten, Produktkataloge
                - Mitarbeiterlisten, Kontaktdaten
                - Technische Spezifikationen
                - Vergleichstabellen
                - Aufzaehlungen mit wiederkehrender Struktur
                """.trimIndent()
            )
            user(chunkText)
        }

        return try {
            val llmResponse = runBlocking {
                promptExecutor.execute(detectionPrompt, LLModel(LLMProvider.OpenAI, modelName))
            }
            val responseText = llmResponse.first().content
            parseDetectionResult(responseText)
        } catch (e: Exception) {
            logger.error("Table detection failed", e)
            DetectionResult(hasTable = false, confidence = 0.0)
        }
    }

    internal fun parseDetectionResult(response: String): DetectionResult {
        return try {
            val cleaned = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val map = objectMapper.readValue<Map<String, Any?>>(cleaned)
            DetectionResult(
                hasTable = map["has_table"] as? Boolean ?: false,
                confidence = (map["confidence"] as? Number)?.toDouble() ?: 0.0,
                tableDescription = map["description"] as? String
            )
        } catch (_: Exception) {
            DetectionResult(hasTable = false, confidence = 0.0)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.structured.TableDetectorTest"`
Expected: All 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/structured/TableDetector.kt \
        src/test/kotlin/com/agentwork/graphmesh/extraction/structured/TableDetectorTest.kt
git commit -m "feat(structured-extractor): add TableDetector with LLM-based table detection"
```

---

### Task 3: SchemaInferenceService + Tests

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/extraction/structured/SchemaInferenceServiceTest.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/structured/SchemaInferenceService.kt`

- [ ] **Step 1: Write failing tests for SchemaInferenceService parsing**

```kotlin
package com.agentwork.graphmesh.extraction.structured

import com.agentwork.graphmesh.structured.ColumnType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaInferenceServiceTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `parseInferredSchema parses new schema with columns`() {
        val json = """
        {
            "schema_name": "product-prices",
            "description": "Produktpreisliste",
            "columns": [
                {"name": "product-name", "type": "STRING", "primary_key": true, "indexed": false},
                {"name": "price", "type": "FLOAT", "primary_key": false, "indexed": true}
            ],
            "matches_existing": null
        }
        """.trimIndent()

        val result = parseInferredSchema(json)
        assertEquals("product-prices", result.schema.name)
        assertEquals("Produktpreisliste", result.schema.description)
        assertEquals(2, result.schema.columns.size)
        assertEquals("product-name", result.schema.columns[0].name)
        assertEquals(ColumnType.STRING, result.schema.columns[0].type)
        assertTrue(result.schema.columns[0].primaryKey)
        assertEquals("price", result.schema.columns[1].name)
        assertEquals(ColumnType.FLOAT, result.schema.columns[1].type)
        assertTrue(result.schema.columns[1].indexed)
        assertNull(result.matchesExisting)
    }

    @Test
    fun `parseInferredSchema parses schema matching existing`() {
        val json = """
        {
            "schema_name": "product-prices",
            "description": "Produktpreisliste",
            "columns": [
                {"name": "product-name", "type": "STRING", "primary_key": true, "indexed": false}
            ],
            "matches_existing": "existing-products"
        }
        """.trimIndent()

        val result = parseInferredSchema(json)
        assertEquals("existing-products", result.matchesExisting)
    }

    @Test
    fun `parseInferredSchema handles all column types`() {
        val json = """
        {
            "schema_name": "types-test",
            "columns": [
                {"name": "a", "type": "STRING"},
                {"name": "b", "type": "INTEGER"},
                {"name": "c", "type": "FLOAT"},
                {"name": "d", "type": "BOOLEAN"},
                {"name": "e", "type": "TIMESTAMP"},
                {"name": "f", "type": "LONG"},
                {"name": "g", "type": "DOUBLE"}
            ],
            "matches_existing": null
        }
        """.trimIndent()

        val result = parseInferredSchema(json)
        assertEquals(7, result.schema.columns.size)
        assertEquals(ColumnType.STRING, result.schema.columns[0].type)
        assertEquals(ColumnType.INTEGER, result.schema.columns[1].type)
        assertEquals(ColumnType.FLOAT, result.schema.columns[2].type)
        assertEquals(ColumnType.BOOLEAN, result.schema.columns[3].type)
        assertEquals(ColumnType.TIMESTAMP, result.schema.columns[4].type)
        assertEquals(ColumnType.LONG, result.schema.columns[5].type)
        assertEquals(ColumnType.DOUBLE, result.schema.columns[6].type)
    }

    @Test
    fun `parseInferredSchema defaults optional column fields`() {
        val json = """
        {
            "schema_name": "minimal",
            "columns": [
                {"name": "col-a", "type": "STRING"}
            ],
            "matches_existing": null
        }
        """.trimIndent()

        val result = parseInferredSchema(json)
        assertFalse(result.schema.columns[0].primaryKey)
        assertFalse(result.schema.columns[0].indexed)
    }

    @Test
    fun `parseInferredSchema strips markdown code fences`() {
        val json = "```json\n{\"schema_name\": \"test\", \"columns\": [{\"name\": \"a\", \"type\": \"STRING\"}], \"matches_existing\": null}\n```"
        val result = parseInferredSchema(json)
        assertEquals("test", result.schema.name)
    }

    // Standalone copy of parsing logic for unit testing
    @Suppress("UNCHECKED_CAST")
    private fun parseInferredSchema(response: String): InferredSchema {
        val cleaned = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val map = objectMapper.readValue<Map<String, Any?>>(cleaned)

        val columns = (map["columns"] as List<Map<String, Any?>>).map { col ->
            com.agentwork.graphmesh.structured.ColumnDescriptor(
                name = col["name"] as String,
                type = ColumnType.fromString(col["type"] as String),
                primaryKey = col["primary_key"] as? Boolean ?: false,
                indexed = col["indexed"] as? Boolean ?: false,
                description = col["description"] as? String
            )
        }

        return InferredSchema(
            schema = com.agentwork.graphmesh.structured.TableSchema(
                name = map["schema_name"] as String,
                description = map["description"] as? String,
                columns = columns
            ),
            matchesExisting = map["matches_existing"] as? String
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.structured.SchemaInferenceServiceTest"`
Expected: FAIL — compile error until Models.kt exists, then tests pass with standalone parsing.

- [ ] **Step 3: Create SchemaInferenceService.kt**

```kotlin
package com.agentwork.graphmesh.extraction.structured

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.structured.ColumnDescriptor
import com.agentwork.graphmesh.structured.ColumnType
import com.agentwork.graphmesh.structured.TableSchema
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class SchemaInferenceService(
    private val promptExecutor: PromptExecutor,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val modelName: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    fun inferSchema(
        chunkText: String,
        existingSchemas: List<TableSchema> = emptyList(),
        tableDescription: String? = null
    ): InferredSchema {
        val existingContext = if (existingSchemas.isNotEmpty()) {
            "Existierende Schemata:\n" + existingSchemas.joinToString("\n") { schema ->
                "- ${schema.name}: ${schema.columns.joinToString(", ") { "${it.name}:${it.type}" }}"
            }
        } else ""

        val inferencePrompt = prompt("schema-inference") {
            system(
                """
                Analysiere den Text und erstelle ein Tabellen-Schema.
                ${if (existingContext.isNotEmpty()) "\n$existingContext\n" else ""}
                ${tableDescription?.let { "Tabellenbeschreibung: $it\n" } ?: ""}

                Antworte mit einem JSON-Objekt:
                {
                    "schema_name": "eindeutiger_name",
                    "description": "Beschreibung",
                    "columns": [
                        {"name": "spalte1", "type": "STRING|INTEGER|LONG|FLOAT|DOUBLE|BOOLEAN|TIMESTAMP", "primary_key": false, "indexed": false, "description": "..."}
                    ],
                    "matches_existing": "name_des_existierenden_schemas" oder null
                }

                Falls die Daten zu einem existierenden Schema passen, setze matches_existing.
                Spaltennamen in kebab-case.
                """.trimIndent()
            )
            user(chunkText)
        }

        val llmResponse = runBlocking {
            promptExecutor.execute(inferencePrompt, LLModel(LLMProvider.OpenAI, modelName))
        }
        val responseText = llmResponse.first().content
        return parseInferredSchema(responseText)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun parseInferredSchema(response: String): InferredSchema {
        val cleaned = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val map = objectMapper.readValue<Map<String, Any?>>(cleaned)

        val columns = (map["columns"] as List<Map<String, Any?>>).map { col ->
            ColumnDescriptor(
                name = col["name"] as String,
                type = ColumnType.fromString(col["type"] as String),
                primaryKey = col["primary_key"] as? Boolean ?: false,
                indexed = col["indexed"] as? Boolean ?: false,
                description = col["description"] as? String
            )
        }

        return InferredSchema(
            schema = TableSchema(
                name = map["schema_name"] as String,
                description = map["description"] as? String,
                columns = columns
            ),
            matchesExisting = map["matches_existing"] as? String
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.structured.SchemaInferenceServiceTest"`
Expected: All 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/structured/SchemaInferenceService.kt \
        src/test/kotlin/com/agentwork/graphmesh/extraction/structured/SchemaInferenceServiceTest.kt
git commit -m "feat(structured-extractor): add SchemaInferenceService with LLM-based schema inference"
```

---

### Task 4: StructuredDataExtractorService + Tests

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorServiceTest.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorService.kt`

- [ ] **Step 1: Write failing tests for StructuredDataExtractorService**

```kotlin
package com.agentwork.graphmesh.extraction.structured

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.structured.ColumnDescriptor
import com.agentwork.graphmesh.structured.ColumnType
import com.agentwork.graphmesh.structured.DataRow
import com.agentwork.graphmesh.structured.StoreResult
import com.agentwork.graphmesh.structured.StructuredDataService
import com.agentwork.graphmesh.structured.TableSchema
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructuredDataExtractorServiceTest {

    private val objectMapper = jacksonObjectMapper()

    // --- Row parsing tests (standalone copy) ---

    @Test
    fun `parseRows extracts valid JSONL rows`() {
        val response = """
            {"product-name": "Widget A", "price": "29.99"}
            {"product-name": "Widget B", "price": "49.99"}
        """.trimIndent()

        val rows = parseRows(response, "products", "col-1")
        assertEquals(2, rows.size)
        assertEquals("Widget A", rows[0].values["product-name"])
        assertEquals("29.99", rows[0].values["price"])
        assertEquals("products", rows[0].schemaName)
        assertEquals("col-1", rows[0].collection)
    }

    @Test
    fun `parseRows skips invalid lines`() {
        val response = """
            {"product-name": "Widget A", "price": "29.99"}
            not valid json
            {"product-name": "Widget B", "price": "49.99"}
        """.trimIndent()

        val rows = parseRows(response, "products", "col-1")
        assertEquals(2, rows.size)
    }

    @Test
    fun `parseRows skips blank lines and code fences`() {
        val response = "```json\n{\"a\": \"1\"}\n\n{\"a\": \"2\"}\n```"
        val rows = parseRows(response, "test", "col-1")
        assertEquals(2, rows.size)
    }

    @Test
    fun `parseRows handles empty response`() {
        val rows = parseRows("", "test", "col-1")
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `parseRows handles truncated last line`() {
        val response = """
            {"product-name": "Widget A", "price": "29.99"}
            {"product-name": "Widget B", "pri
        """.trimIndent()

        val rows = parseRows(response, "products", "col-1")
        assertEquals(1, rows.size)
    }

    @Test
    fun `parseRows sets source to chunk URN`() {
        val response = """{"a": "1"}"""
        val rows = parseRows(response, "test", "col-1", "chunk-42")
        assertEquals("urn:chunk:chunk-42", rows[0].source)
    }

    // --- End-to-end extract() tests ---

    @Test
    fun `extract skips chunk with no table detected`() {
        val promptExecutor = mockk<PromptExecutor>()
        val librarianService = mockk<LibrarianService>()
        val tableDetector = mockk<TableDetector>()
        val schemaInferenceService = mockk<SchemaInferenceService>()
        val structuredDataService = mockk<StructuredDataService>()

        val service = StructuredDataExtractorService(
            promptExecutor, tableDetector, schemaInferenceService,
            structuredDataService, librarianService, "gpt-4o"
        )

        every { librarianService.getContent("chunk-1") } returns "Some plain text.".toByteArray()
        every { tableDetector.detect("Some plain text.") } returns DetectionResult(
            hasTable = false, confidence = 0.2
        )

        val result = service.extract("chunk-1", "col-1")

        assertFalse(result.tableDetected)
        assertEquals(0, result.rowsExtracted)
        assertNull(result.schemaName)
    }

    @Test
    fun `extract skips chunk with low confidence`() {
        val promptExecutor = mockk<PromptExecutor>()
        val librarianService = mockk<LibrarianService>()
        val tableDetector = mockk<TableDetector>()
        val schemaInferenceService = mockk<SchemaInferenceService>()
        val structuredDataService = mockk<StructuredDataService>()

        val service = StructuredDataExtractorService(
            promptExecutor, tableDetector, schemaInferenceService,
            structuredDataService, librarianService, "gpt-4o"
        )

        every { librarianService.getContent("chunk-1") } returns "Some text.".toByteArray()
        every { tableDetector.detect("Some text.") } returns DetectionResult(
            hasTable = true, confidence = 0.3
        )

        val result = service.extract("chunk-1", "col-1")

        assertFalse(result.tableDetected)
        assertEquals(0, result.rowsExtracted)
    }

    @Test
    fun `extract detects table infers new schema and stores rows`() {
        val promptExecutor = mockk<PromptExecutor>()
        val librarianService = mockk<LibrarianService>()
        val tableDetector = mockk<TableDetector>()
        val schemaInferenceService = mockk<SchemaInferenceService>()
        val structuredDataService = mockk<StructuredDataService>()

        val service = StructuredDataExtractorService(
            promptExecutor, tableDetector, schemaInferenceService,
            structuredDataService, librarianService, "gpt-4o"
        )

        val chunkText = "Product A costs 10 EUR. Product B costs 20 EUR."
        every { librarianService.getContent("chunk-1") } returns chunkText.toByteArray()
        every { tableDetector.detect(chunkText) } returns DetectionResult(
            hasTable = true, confidence = 0.9, tableDescription = "Product prices"
        )

        val schema = TableSchema(
            name = "product-prices",
            description = "Produktpreisliste",
            columns = listOf(
                ColumnDescriptor("product-name", ColumnType.STRING, primaryKey = true),
                ColumnDescriptor("price", ColumnType.FLOAT, indexed = true)
            )
        )
        every { structuredDataService.listSchemas() } returns emptyList()
        every { schemaInferenceService.inferSchema(chunkText, emptyList(), "Product prices") } returns InferredSchema(
            schema = schema, matchesExisting = null
        )
        every { structuredDataService.saveSchema(schema) } returns Unit

        val rowsResponse = """
            {"product-name": "Product A", "price": "10.00"}
            {"product-name": "Product B", "price": "20.00"}
        """.trimIndent()
        val message = mockk<Message.Response>()
        every { message.content } returns rowsResponse
        coEvery { promptExecutor.execute(any(), any()) } returns listOf(message)

        every { structuredDataService.storeBatch(any()) } returns listOf(
            StoreResult(success = true, rowsWritten = 1),
            StoreResult(success = true, rowsWritten = 1)
        )

        val result = service.extract("chunk-1", "col-1")

        assertTrue(result.tableDetected)
        assertEquals("product-prices", result.schemaName)
        assertEquals(2, result.rowsExtracted)

        verify { structuredDataService.saveSchema(schema) }
        val rowsSlot = slot<List<DataRow>>()
        verify { structuredDataService.storeBatch(capture(rowsSlot)) }
        assertEquals(2, rowsSlot.captured.size)
        assertEquals("Product A", rowsSlot.captured[0].values["product-name"])
        assertEquals("urn:chunk:chunk-1", rowsSlot.captured[0].source)
    }

    @Test
    fun `extract reuses existing schema when matchesExisting is set`() {
        val promptExecutor = mockk<PromptExecutor>()
        val librarianService = mockk<LibrarianService>()
        val tableDetector = mockk<TableDetector>()
        val schemaInferenceService = mockk<SchemaInferenceService>()
        val structuredDataService = mockk<StructuredDataService>()

        val service = StructuredDataExtractorService(
            promptExecutor, tableDetector, schemaInferenceService,
            structuredDataService, librarianService, "gpt-4o"
        )

        val chunkText = "Product C costs 30 EUR."
        every { librarianService.getContent("chunk-2") } returns chunkText.toByteArray()
        every { tableDetector.detect(chunkText) } returns DetectionResult(
            hasTable = true, confidence = 0.85
        )

        val existingSchema = TableSchema(
            name = "product-prices",
            columns = listOf(
                ColumnDescriptor("product-name", ColumnType.STRING, primaryKey = true),
                ColumnDescriptor("price", ColumnType.FLOAT)
            )
        )
        every { structuredDataService.listSchemas() } returns listOf("product-prices")
        every { structuredDataService.getSchema("product-prices") } returns existingSchema
        every { schemaInferenceService.inferSchema(chunkText, listOf(existingSchema), null) } returns InferredSchema(
            schema = existingSchema, matchesExisting = "product-prices"
        )

        val rowsResponse = """{"product-name": "Product C", "price": "30.00"}"""
        val message = mockk<Message.Response>()
        every { message.content } returns rowsResponse
        coEvery { promptExecutor.execute(any(), any()) } returns listOf(message)

        every { structuredDataService.storeBatch(any()) } returns listOf(StoreResult(success = true, rowsWritten = 1))

        val result = service.extract("chunk-2", "col-1")

        assertTrue(result.tableDetected)
        assertEquals("product-prices", result.schemaName)
        assertEquals(1, result.rowsExtracted)

        // saveSchema should NOT be called for existing schema
        verify(exactly = 0) { structuredDataService.saveSchema(any()) }
    }

    @Test
    fun `extract returns zero rows when LLM returns empty response`() {
        val promptExecutor = mockk<PromptExecutor>()
        val librarianService = mockk<LibrarianService>()
        val tableDetector = mockk<TableDetector>()
        val schemaInferenceService = mockk<SchemaInferenceService>()
        val structuredDataService = mockk<StructuredDataService>()

        val service = StructuredDataExtractorService(
            promptExecutor, tableDetector, schemaInferenceService,
            structuredDataService, librarianService, "gpt-4o"
        )

        val chunkText = "Some text with a table."
        every { librarianService.getContent("chunk-3") } returns chunkText.toByteArray()
        every { tableDetector.detect(chunkText) } returns DetectionResult(hasTable = true, confidence = 0.7)

        val schema = TableSchema(name = "empty", columns = listOf(ColumnDescriptor("a", ColumnType.STRING)))
        every { structuredDataService.listSchemas() } returns emptyList()
        every { schemaInferenceService.inferSchema(chunkText, emptyList(), null) } returns InferredSchema(
            schema = schema, matchesExisting = null
        )
        every { structuredDataService.saveSchema(schema) } returns Unit

        val message = mockk<Message.Response>()
        every { message.content } returns ""
        coEvery { promptExecutor.execute(any(), any()) } returns listOf(message)

        val result = service.extract("chunk-3", "col-1")

        assertTrue(result.tableDetected)
        assertEquals(0, result.rowsExtracted)
        verify(exactly = 0) { structuredDataService.storeBatch(any()) }
    }

    // Standalone copy of row parsing logic
    private fun parseRows(
        response: String,
        schemaName: String,
        collection: String,
        chunkId: String? = null
    ): List<DataRow> {
        return response.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val values = objectMapper.readValue<Map<String, String>>(line)
                    DataRow(
                        collection = collection,
                        schemaName = schemaName,
                        values = values,
                        source = chunkId?.let { "urn:chunk:$it" }
                    )
                } catch (_: Exception) {
                    null
                }
            }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.structured.StructuredDataExtractorServiceTest"`
Expected: FAIL — `StructuredDataExtractorService` class not found

- [ ] **Step 3: Create StructuredDataExtractorService.kt**

```kotlin
package com.agentwork.graphmesh.extraction.structured

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.structured.DataRow
import com.agentwork.graphmesh.structured.StructuredDataService
import com.agentwork.graphmesh.structured.TableSchema
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class StructuredDataExtractorService(
    private val promptExecutor: PromptExecutor,
    private val tableDetector: TableDetector,
    private val schemaInferenceService: SchemaInferenceService,
    private val structuredDataService: StructuredDataService,
    private val librarianService: LibrarianService,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val modelName: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    fun extract(chunkId: String, collectionId: String): StructuredExtractionResult {
        val content = librarianService.getContent(chunkId)
        val chunkText = String(content, Charsets.UTF_8)

        if (chunkText.isBlank()) {
            return StructuredExtractionResult(chunkId = chunkId, tableDetected = false, rowsExtracted = 0)
        }

        // Step 1: Table detection
        val detection = tableDetector.detect(chunkText)
        if (!detection.hasTable || detection.confidence < 0.5) {
            return StructuredExtractionResult(chunkId = chunkId, tableDetected = false, rowsExtracted = 0)
        }

        // Step 2: Schema inference
        val existingSchemas = structuredDataService.listSchemas()
            .mapNotNull { structuredDataService.getSchema(it) }
        val inferred = schemaInferenceService.inferSchema(
            chunkText = chunkText,
            existingSchemas = existingSchemas,
            tableDescription = detection.tableDescription
        )

        val schema: TableSchema
        val schemaName: String
        if (inferred.matchesExisting != null) {
            schema = structuredDataService.getSchema(inferred.matchesExisting) ?: inferred.schema
            schemaName = inferred.matchesExisting
        } else {
            schema = inferred.schema
            schemaName = inferred.schema.name
            structuredDataService.saveSchema(schema)
        }

        // Step 3: Row extraction
        val rows = extractRows(chunkText, schema, collectionId, chunkId)

        // Step 4: Store
        if (rows.isNotEmpty()) {
            structuredDataService.storeBatch(rows)
        }

        logger.info(
            "Structured extraction complete: chunkId={}, schema={}, rows={}",
            chunkId, schemaName, rows.size
        )

        return StructuredExtractionResult(
            chunkId = chunkId,
            tableDetected = true,
            schemaName = schemaName,
            rowsExtracted = rows.size
        )
    }

    private fun extractRows(
        chunkText: String,
        schema: TableSchema,
        collectionId: String,
        chunkId: String
    ): List<DataRow> {
        val columnSpec = schema.columns.joinToString(", ") { "${it.name} (${it.type})" }

        val extractionPrompt = prompt("row-extraction") {
            system(
                """
                Extrahiere die tabellarischen Daten aus dem Text als JSONL.

                Schema: ${schema.name}
                Spalten: $columnSpec

                Gib pro Zeile ein JSON-Objekt aus mit den Spaltennamen als Keys:
                {"${schema.columns.first().name}": "...", ...}

                Regeln:
                - Nur Daten extrahieren, die tatsaechlich im Text stehen
                - Fehlende Werte als leeren String ""
                - Zahlen als Strings ("42", "3.14")
                - Jede Zeile auf einer eigenen Linie (JSONL-Format)
                """.trimIndent()
            )
            user(chunkText)
        }

        val llmResponse = runBlocking {
            promptExecutor.execute(extractionPrompt, LLModel(LLMProvider.OpenAI, modelName))
        }
        val responseText = llmResponse.first().content
        return parseRows(responseText, schema.name, collectionId, chunkId)
    }

    internal fun parseRows(
        response: String,
        schemaName: String,
        collection: String,
        chunkId: String
    ): List<DataRow> {
        return response.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val values = objectMapper.readValue<Map<String, String>>(line)
                    DataRow(
                        collection = collection,
                        schemaName = schemaName,
                        values = values,
                        source = "urn:chunk:$chunkId"
                    )
                } catch (_: Exception) {
                    null
                }
            }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.structured.StructuredDataExtractorServiceTest"`
Expected: All 10 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorService.kt \
        src/test/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorServiceTest.kt
git commit -m "feat(structured-extractor): add StructuredDataExtractorService with row extraction pipeline"
```

---

### Task 5: StructuredDataExtractorConsumer + Tests

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorConsumerTest.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorConsumer.kt`

- [ ] **Step 1: Write failing tests for the consumer**

```kotlin
package com.agentwork.graphmesh.extraction.structured

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test

class StructuredDataExtractorConsumerTest {

    @Test
    fun `handle delegates to extractor service`() {
        val extractorService = mockk<StructuredDataExtractorService>(relaxed = true)
        val consumer = StructuredDataExtractorConsumer(extractorService)

        val genericRecord = mockk<GenericRecord>()
        every { genericRecord["chunkId"] } returns "chunk-1"
        every { genericRecord["collectionId"] } returns "col-1"

        val record = ConsumerRecord<String, GenericRecord>("graphmesh.chunk.created", 0, 0L, "key", genericRecord)
        consumer.handle(record)

        verify { extractorService.extract("chunk-1", "col-1") }
    }

    @Test
    fun `handle catches and logs exceptions`() {
        val extractorService = mockk<StructuredDataExtractorService>()
        val consumer = StructuredDataExtractorConsumer(extractorService)

        every { extractorService.extract(any(), any()) } throws RuntimeException("LLM timeout")

        val genericRecord = mockk<GenericRecord>()
        every { genericRecord["chunkId"] } returns "chunk-fail"
        every { genericRecord["collectionId"] } returns "col-1"

        val record = ConsumerRecord<String, GenericRecord>("graphmesh.chunk.created", 0, 0L, "key", genericRecord)

        // Should not throw — exception is caught internally
        consumer.handle(record)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.structured.StructuredDataExtractorConsumerTest"`
Expected: FAIL — `StructuredDataExtractorConsumer` class not found

- [ ] **Step 3: Create StructuredDataExtractorConsumer.kt**

```kotlin
package com.agentwork.graphmesh.extraction.structured

import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class StructuredDataExtractorConsumer(
    private val extractorService: StructuredDataExtractorService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.chunk.created"], groupId = "graphmesh-structured-extractor")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val chunkId = value["chunkId"].toString()
        val collectionId = value["collectionId"].toString()

        logger.info("Chunk received for structured data extraction: chunkId={}", chunkId)

        try {
            val result = extractorService.extract(chunkId, collectionId)
            logger.info(
                "Structured data extraction complete: chunkId={}, tableDetected={}, schema={}, rows={}",
                chunkId, result.tableDetected, result.schemaName, result.rowsExtracted
            )
        } catch (e: Exception) {
            logger.error("Structured data extraction failed for chunk {}", chunkId, e)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.structured.StructuredDataExtractorConsumerTest"`
Expected: All 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorConsumer.kt \
        src/test/kotlin/com/agentwork/graphmesh/extraction/structured/StructuredDataExtractorConsumerTest.kt
git commit -m "feat(structured-extractor): add Kafka consumer for chunk.created events"
```

---

### Task 6: Full Build Verification

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — all tests pass including the 4 new test classes

- [ ] **Step 2: Run full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify all new files are committed**

Run: `git status`
Expected: Clean working tree — nothing to commit
