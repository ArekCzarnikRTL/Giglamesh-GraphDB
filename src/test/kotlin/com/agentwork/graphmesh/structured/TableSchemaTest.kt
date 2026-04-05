package com.agentwork.graphmesh.structured

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableSchemaTest {

    @Test
    fun `primaryKeyColumns returns only PK columns`() {
        val schema = TableSchema(
            name = "users",
            columns = listOf(
                ColumnDescriptor(name = "id", type = ColumnType.STRING, primaryKey = true),
                ColumnDescriptor(name = "name", type = ColumnType.STRING),
                ColumnDescriptor(name = "email", type = ColumnType.STRING, indexed = true)
            )
        )
        assertEquals(1, schema.primaryKeyColumns.size)
        assertEquals("id", schema.primaryKeyColumns[0].name)
    }

    @Test
    fun `indexedColumns excludes PK columns`() {
        val schema = TableSchema(
            name = "users",
            columns = listOf(
                ColumnDescriptor(name = "id", type = ColumnType.STRING, primaryKey = true, indexed = true),
                ColumnDescriptor(name = "email", type = ColumnType.STRING, indexed = true),
                ColumnDescriptor(name = "name", type = ColumnType.STRING)
            )
        )
        assertEquals(1, schema.indexedColumns.size)
        assertEquals("email", schema.indexedColumns[0].name)
    }

    @Test
    fun `allIndexNames includes PK, indexed columns, and composite indexes`() {
        val schema = TableSchema(
            name = "orders",
            columns = listOf(
                ColumnDescriptor(name = "order_id", type = ColumnType.STRING, primaryKey = true),
                ColumnDescriptor(name = "customer_id", type = ColumnType.STRING, indexed = true),
                ColumnDescriptor(name = "status", type = ColumnType.STRING, indexed = true),
                ColumnDescriptor(name = "total", type = ColumnType.DOUBLE)
            ),
            indexes = listOf(IndexDefinition(fields = listOf("customer_id", "status")))
        )
        val indexNames = schema.allIndexNames
        assertTrue(indexNames.contains("order_id"))          // PK
        assertTrue(indexNames.contains("customer_id"))       // indexed
        assertTrue(indexNames.contains("status"))            // indexed
        assertTrue(indexNames.contains("customer_id,status")) // composite (sorted)
    }

    @Test
    fun `allIndexNames deduplicates`() {
        val schema = TableSchema(
            name = "test",
            columns = listOf(
                ColumnDescriptor(name = "id", type = ColumnType.STRING, primaryKey = true),
                ColumnDescriptor(name = "name", type = ColumnType.STRING, indexed = true)
            ),
            indexes = listOf(IndexDefinition(fields = listOf("name")))
        )
        // "name" appears as both indexed column and composite index — should be deduplicated
        assertEquals(schema.allIndexNames.size, schema.allIndexNames.distinct().size)
    }

    @Test
    fun `ColumnType fromString is case insensitive`() {
        assertEquals(ColumnType.STRING, ColumnType.fromString("string"))
        assertEquals(ColumnType.STRING, ColumnType.fromString("STRING"))
        assertEquals(ColumnType.INTEGER, ColumnType.fromString("integer"))
        assertEquals(ColumnType.TIMESTAMP, ColumnType.fromString("Timestamp"))
    }

    @Test
    fun `ColumnType fromString throws for unknown type`() {
        assertThrows<IllegalArgumentException> {
            ColumnType.fromString("unknown")
        }
    }

    @Test
    fun `ColumnType has correct cassandraType mappings`() {
        assertEquals("text", ColumnType.STRING.cassandraType)
        assertEquals("int", ColumnType.INTEGER.cassandraType)
        assertEquals("bigint", ColumnType.LONG.cassandraType)
        assertEquals("float", ColumnType.FLOAT.cassandraType)
        assertEquals("double", ColumnType.DOUBLE.cassandraType)
        assertEquals("boolean", ColumnType.BOOLEAN.cassandraType)
        assertEquals("timestamp", ColumnType.TIMESTAMP.cassandraType)
    }

    @Test
    fun `allIndexNames with multi-column PK`() {
        val schema = TableSchema(
            name = "composite_pk",
            columns = listOf(
                ColumnDescriptor(name = "tenant", type = ColumnType.STRING, primaryKey = true),
                ColumnDescriptor(name = "id", type = ColumnType.STRING, primaryKey = true),
                ColumnDescriptor(name = "value", type = ColumnType.STRING)
            )
        )
        assertTrue(schema.allIndexNames.contains("id,tenant") || schema.allIndexNames.contains("tenant,id"))
    }
}
