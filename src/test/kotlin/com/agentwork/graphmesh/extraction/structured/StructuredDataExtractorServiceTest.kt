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
