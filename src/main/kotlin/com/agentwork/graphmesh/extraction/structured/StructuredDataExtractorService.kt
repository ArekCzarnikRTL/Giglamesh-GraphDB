package com.agentwork.graphmesh.extraction.structured

import com.agentwork.graphmesh.llm.resolveLlmModel

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

@OptIn(kotlin.time.ExperimentalTime::class)
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
            promptExecutor.execute(extractionPrompt, resolveLlmModel(modelName))
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
