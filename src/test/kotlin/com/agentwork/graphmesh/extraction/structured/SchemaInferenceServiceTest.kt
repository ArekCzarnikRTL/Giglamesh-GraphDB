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
