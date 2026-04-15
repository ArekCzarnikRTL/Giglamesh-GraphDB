package com.agentwork.graphmesh.structured

import com.agentwork.graphmesh.storage.AsyncCqlWriter
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.datastax.oss.driver.api.core.cql.Row
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals

class CassandraRowStoreTest {

    @Test
    fun `extractIndexValue returns single value for single field`() {
        assertEquals(listOf("123"), extractIndexValue("id", mapOf("id" to "123", "name" to "Alice")))
    }

    @Test
    fun `extractIndexValue returns multiple values for composite index`() {
        assertEquals(
            listOf("acme", "123"),
            extractIndexValue("tenant,id", mapOf("tenant" to "acme", "id" to "123", "status" to "active")),
        )
    }

    @Test
    fun `extractIndexValue returns empty string for missing field`() {
        assertEquals(listOf("123", ""), extractIndexValue("id,missing", mapOf("id" to "123")))
    }

    @Test
    fun `extractIndexValue handles all fields missing`() {
        assertEquals(listOf("", "", ""), extractIndexValue("a,b,c", emptyMap()))
    }

    @Test
    fun `extractIndexValue preserves field order from index name`() {
        assertEquals(
            listOf("middle", "first", "last"),
            extractIndexValue("m,a,z", mapOf("z" to "last", "a" to "first", "m" to "middle")),
        )
    }

    private fun newStore(session: CqlSession, schemaStore: SchemaStore): CassandraRowStore {
        val prepared = mockk<PreparedStatement>()
        val bound = mockk<BoundStatement>()
        every { prepared.bind(*anyVararg()) } returns bound
        every { session.prepare(any<String>()) } returns prepared

        val writer = AsyncCqlWriter(session, maxInflight = 8, timeout = Duration.ofSeconds(5))
        val store = CassandraRowStore(session, schemaStore, "graphmesh", writer)
        store.prepareStatements()
        return store
    }

    private fun alwaysCompleted(session: CqlSession) {
        every { session.executeAsync(any<BoundStatement>()) } returns
            CompletableFuture.completedFuture(mockk<AsyncResultSet>(relaxed = true))
    }

    private fun schemaWithTwoIndexes(): SchemaStore {
        val schema = mockk<TableSchema>()
        every { schema.allIndexNames } returns listOf("id", "tenant,id")
        val store = mockk<SchemaStore>()
        every { store.load("orders") } returns schema
        return store
    }

    @Test
    fun `insert issues 2 executeAsync per index and no batch`() {
        val session = mockk<CqlSession>(relaxed = true)
        alwaysCompleted(session)
        val schemas = schemaWithTwoIndexes()
        val store = newStore(session, schemas)

        store.insert(DataRow(
            collection = "c",
            schemaName = "orders",
            values = mapOf("id" to "1", "tenant" to "acme"),
            source = "test",
        ))

        // 2 indexes * 2 statements (rows + row_partitions) = 4
        verify(exactly = 4) { session.executeAsync(any<BoundStatement>()) }
        verify(exactly = 0) { session.execute(any<BoundStatement>()) }
    }

    @Test
    fun `insertBatch uses async writes`() {
        val session = mockk<CqlSession>(relaxed = true)
        alwaysCompleted(session)
        val schemas = schemaWithTwoIndexes()
        val store = newStore(session, schemas)

        val rows = List(3) {
            DataRow(
                collection = "c",
                schemaName = "orders",
                values = mapOf("id" to it.toString(), "tenant" to "acme"),
                source = "test",
            )
        }
        store.insertBatch(rows)

        // 3 rows * 2 indexes * 2 statements = 12
        verify(exactly = 12) { session.executeAsync(any<BoundStatement>()) }
        verify(exactly = 0) { session.execute(any<BoundStatement>()) }
    }

    @Test
    fun `deleteByCollection uses prepared statement for row deletes and no inline SQL`() {
        val session = mockk<CqlSession>(relaxed = true)
        alwaysCompleted(session)

        val partitionRow1 = mockk<Row>()
        every { partitionRow1.getString("schema_name") } returns "orders"
        every { partitionRow1.getString("index_name") } returns "id"
        val partitionRow2 = mockk<Row>()
        every { partitionRow2.getString("schema_name") } returns "orders"
        every { partitionRow2.getString("index_name") } returns "tenant,id"

        val rs = mockk<ResultSet>()
        every { rs.iterator() } returns mutableListOf(partitionRow1, partitionRow2).iterator()
        every { session.execute(any<BoundStatement>()) } returns rs

        val store = newStore(session, mockk())

        store.deleteByCollection("c")

        // 2 partitions * (1 row-delete + 1 partition-delete) = 4 async statements
        verify(exactly = 4) { session.executeAsync(any<BoundStatement>()) }
        // no inline CQL string executed (was injection risk)
        verify(exactly = 0) { session.execute(any<String>()) }
        // only 1 sync execute: the selectPartitions query (not a batch)
        verify(exactly = 1) { session.execute(any<BoundStatement>()) }
    }
}
