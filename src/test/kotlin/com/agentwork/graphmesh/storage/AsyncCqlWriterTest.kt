package com.agentwork.graphmesh.storage

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.BoundStatement
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsyncCqlWriterTest {

    private fun writer(session: CqlSession, maxInflight: Int = 4, timeout: Duration = Duration.ofSeconds(5)) =
        AsyncCqlWriter(session, maxInflight, timeout)

    private fun completedFuture(): CompletableFuture<AsyncResultSet> =
        CompletableFuture.completedFuture(mockk(relaxed = true))

    @Test
    fun `executeAll issues one executeAsync call per statement`() {
        val session = mockk<CqlSession>()
        every { session.executeAsync(any<BoundStatement>()) } returns completedFuture()

        val statements = List(7) { mockk<BoundStatement>() }
        writer(session).executeAll(statements)

        verify(exactly = 7) { session.executeAsync(any<BoundStatement>()) }
    }

    @Test
    fun `executeAll with empty list is a no-op`() {
        val session = mockk<CqlSession>()
        writer(session).executeAll(emptyList())
        verify(exactly = 0) { session.executeAsync(any<BoundStatement>()) }
    }

    @Test
    fun `executeAll with max-inflight 1 runs sequentially`() {
        val session = mockk<CqlSession>()
        val inflight = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        every { session.executeAsync(any<BoundStatement>()) } answers {
            val current = inflight.incrementAndGet()
            maxSeen.updateAndGet { maxOf(it, current) }
            CompletableFuture.supplyAsync<AsyncResultSet> {
                Thread.sleep(10)
                inflight.decrementAndGet()
                mockk(relaxed = true)
            }
        }

        writer(session, maxInflight = 1).executeAll(List(5) { mockk<BoundStatement>() })

        assertEquals(1, maxSeen.get(), "max-inflight=1 must never run 2 concurrently")
    }

    @Test
    fun `executeAll with max-inflight 4 respects the cap`() {
        val session = mockk<CqlSession>()
        val inflight = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        every { session.executeAsync(any<BoundStatement>()) } answers {
            val current = inflight.incrementAndGet()
            maxSeen.updateAndGet { maxOf(it, current) }
            CompletableFuture.supplyAsync<AsyncResultSet> {
                Thread.sleep(10)
                inflight.decrementAndGet()
                mockk(relaxed = true)
            }
        }

        writer(session, maxInflight = 4).executeAll(List(20) { mockk<BoundStatement>() })

        assertTrue(maxSeen.get() <= 4, "expected <=4 concurrent, saw ${maxSeen.get()}")
    }

    @Test
    fun `executeAll rethrows first failure and does not hang`() {
        val session = mockk<CqlSession>()
        val boom = RuntimeException("write failed")
        val callCount = AtomicInteger(0)
        every { session.executeAsync(any<BoundStatement>()) } answers {
            val n = callCount.incrementAndGet()
            if (n == 3) CompletableFuture.failedFuture(boom)
            else CompletableFuture.completedFuture(mockk<AsyncResultSet>(relaxed = true))
        }

        val ex = assertThrows<RuntimeException> {
            writer(session, maxInflight = 2).executeAll(List(10) { mockk<BoundStatement>() })
        }
        assertTrue(ex == boom || ex.cause == boom, "expected original failure propagated, got $ex")
    }
}
