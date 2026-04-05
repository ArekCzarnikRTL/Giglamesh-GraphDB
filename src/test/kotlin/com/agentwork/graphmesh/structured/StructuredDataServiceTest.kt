package com.agentwork.graphmesh.structured

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructuredDataServiceTest {

    private val schemaStore = mockk<SchemaStore>(relaxed = true)
    private val rowStore = mockk<CassandraRowStore>(relaxed = true)
    private val service = StructuredDataService(schemaStore, rowStore)

    private val userSchema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDescriptor(name = "id", type = ColumnType.STRING, primaryKey = true),
            ColumnDescriptor(name = "name", type = ColumnType.STRING, nullable = false),
            ColumnDescriptor(name = "email", type = ColumnType.STRING, indexed = true),
            ColumnDescriptor(name = "age", type = ColumnType.INTEGER)
        )
    )

    @Test
    fun `store succeeds with valid row`() {
        every { schemaStore.load("users") } returns userSchema

        val row = DataRow(
            collection = "col-1",
            schemaName = "users",
            values = mapOf("id" to "1", "name" to "Alice", "email" to "alice@example.com")
        )
        val result = service.store(row)

        assertTrue(result.success)
        assertEquals(1, result.rowsWritten)
        verify { rowStore.insert(row) }
    }

    @Test
    fun `store fails when schema not found`() {
        every { schemaStore.load("nonexistent") } returns null

        val row = DataRow(collection = "col-1", schemaName = "nonexistent", values = mapOf("id" to "1"))
        val result = service.store(row)

        assertFalse(result.success)
        assertTrue(result.error!!.contains("not found"))
        verify(exactly = 0) { rowStore.insert(any()) }
    }

    @Test
    fun `store fails when required column is missing`() {
        every { schemaStore.load("users") } returns userSchema

        val row = DataRow(
            collection = "col-1",
            schemaName = "users",
            values = mapOf("id" to "1", "email" to "alice@example.com")
            // "name" is required but missing
        )
        val result = service.store(row)

        assertFalse(result.success)
        assertTrue(result.error!!.contains("name"))
        verify(exactly = 0) { rowStore.insert(any()) }
    }

    @Test
    fun `store fails when required column is blank`() {
        every { schemaStore.load("users") } returns userSchema

        val row = DataRow(
            collection = "col-1",
            schemaName = "users",
            values = mapOf("id" to "1", "name" to "", "email" to "test@test.com")
        )
        val result = service.store(row)

        assertFalse(result.success)
        assertTrue(result.error!!.contains("name"))
    }

    @Test
    fun `store fails when all PK columns are missing`() {
        every { schemaStore.load("users") } returns userSchema

        val row = DataRow(
            collection = "col-1",
            schemaName = "users",
            values = mapOf("name" to "Alice", "email" to "alice@example.com")
            // "id" (PK) is missing
        )
        val result = service.store(row)

        assertFalse(result.success)
        assertTrue(result.error!!.contains("primary key"))
    }

    @Test
    fun `storeBatch processes valid and invalid rows`() {
        every { schemaStore.load("users") } returns userSchema
        every { schemaStore.load("nonexistent") } returns null

        val rows = listOf(
            DataRow(collection = "col-1", schemaName = "users", values = mapOf("id" to "1", "name" to "Alice")),
            DataRow(collection = "col-1", schemaName = "nonexistent", values = mapOf("id" to "2")),
            DataRow(collection = "col-1", schemaName = "users", values = mapOf("id" to "3", "name" to "Bob"))
        )

        val results = service.storeBatch(rows)

        assertEquals(3, results.size)
        assertTrue(results[0].success)
        assertFalse(results[1].success)
        assertTrue(results[2].success)
        verify { rowStore.insertBatch(match { it.size == 2 }) }
    }

    @Test
    fun `storeBatch returns empty for empty input`() {
        val results = service.storeBatch(emptyList())
        assertTrue(results.isEmpty())
        verify(exactly = 0) { rowStore.insertBatch(any()) }
    }

    @Test
    fun `query delegates to rowStore`() {
        val query = StructuredQuery(
            collection = "col-1", schemaName = "users",
            indexName = "email", indexValue = listOf("alice@example.com")
        )
        val expected = QueryResult(
            rows = listOf(DataRow(collection = "col-1", schemaName = "users", values = mapOf("id" to "1", "name" to "Alice"))),
            totalCount = 1, hasMore = false
        )
        every { rowStore.query(query) } returns expected

        val result = service.query(query)

        assertEquals(expected, result)
    }

    @Test
    fun `saveSchema delegates to schemaStore`() {
        service.saveSchema(userSchema)
        verify { schemaStore.save(userSchema) }
    }

    @Test
    fun `getSchema delegates to schemaStore`() {
        every { schemaStore.load("users") } returns userSchema
        assertEquals(userSchema, service.getSchema("users"))
    }

    @Test
    fun `listSchemas delegates to schemaStore`() {
        every { schemaStore.listNames() } returns listOf("users", "orders")
        assertEquals(listOf("users", "orders"), service.listSchemas())
    }

    @Test
    fun `deleteSchema delegates to schemaStore`() {
        service.deleteSchema("users")
        verify { schemaStore.delete("users") }
    }

    @Test
    fun `validateRow returns null for valid row`() {
        val row = DataRow(collection = "col-1", schemaName = "users", values = mapOf("id" to "1", "name" to "Alice"))
        assertNull(service.validateRow(row, userSchema))
    }
}
