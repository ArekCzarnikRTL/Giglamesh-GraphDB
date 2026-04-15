package com.agentwork.graphmesh.storage

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class CassandraQuadStoreTest {

    private val keyspace = "graphmesh"

    private fun newStore(session: CqlSession): CassandraQuadStore {
        val prepared = mockk<PreparedStatement>()
        val bound = mockk<BoundStatement>()
        every { prepared.bind(*anyVararg()) } returns bound
        every { session.prepare(any<String>()) } returns prepared

        val writer = AsyncCqlWriter(session, maxInflight = 8, timeout = Duration.ofSeconds(5))
        val store = CassandraQuadStore(session, keyspace, writer)
        store.prepareStatements()
        return store
    }

    private fun alwaysCompleted(session: CqlSession) {
        every { session.executeAsync(any<BoundStatement>()) } returns
            CompletableFuture.completedFuture(mockk<AsyncResultSet>(relaxed = true))
    }

    private fun sampleQuad(i: Int = 0) = StoredQuad(
        subject = "http://example/s$i",
        predicate = "http://example/p",
        objectValue = "http://example/o$i",
        dataset = "http://example/d",
        objectType = ObjectType.URI,
        datatype = "",
        language = "",
    )

    @Test
    fun `insert issues 5 async statements and no batch`() {
        val session = mockk<CqlSession>(relaxed = true)
        alwaysCompleted(session)
        val store = newStore(session)

        store.insert("col", sampleQuad())

        verify(exactly = 5) { session.executeAsync(any<BoundStatement>()) }
        verify(exactly = 0) { session.execute(any<BatchStatement>()) }
    }

    @Test
    fun `insertBatch issues 5 async statements per quad`() {
        val session = mockk<CqlSession>(relaxed = true)
        alwaysCompleted(session)
        val store = newStore(session)

        val quads = List(10) { sampleQuad(it) }
        store.insertBatch("col", quads)

        verify(exactly = 50) { session.executeAsync(any<BoundStatement>()) }
        verify(exactly = 0) { session.execute(any<BatchStatement>()) }
    }

    @Test
    fun `delete issues 5 async statements and no batch`() {
        val session = mockk<CqlSession>(relaxed = true)
        alwaysCompleted(session)
        val store = newStore(session)

        store.delete("col", sampleQuad())

        verify(exactly = 5) { session.executeAsync(any<BoundStatement>()) }
        verify(exactly = 0) { session.execute(any<BatchStatement>()) }
    }

    @Test
    fun `insertBatch does not use LOGGED BATCH even with 1000 quads`() {
        val session = mockk<CqlSession>(relaxed = true)
        val counter = AtomicInteger(0)
        every { session.executeAsync(any<BoundStatement>()) } answers {
            counter.incrementAndGet()
            CompletableFuture.completedFuture(mockk<AsyncResultSet>(relaxed = true))
        }
        val store = newStore(session)

        store.insertBatch("col", List(1000) { sampleQuad(it) })

        assertEquals(5000, counter.get())
        verify(exactly = 0) { session.execute(any<BatchStatement>()) }
    }
}
