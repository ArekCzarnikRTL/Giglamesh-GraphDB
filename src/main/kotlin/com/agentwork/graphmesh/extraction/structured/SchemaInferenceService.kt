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

@OptIn(kotlin.time.ExperimentalTime::class)
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
