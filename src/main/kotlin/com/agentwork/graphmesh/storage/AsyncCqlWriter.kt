package com.agentwork.graphmesh.storage

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.BoundStatement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Writes a list of BoundStatements to Cassandra in parallel, capped by [maxInflight].
 * First failure cancels in-flight siblings and rethrows.
 */
@Component
class AsyncCqlWriter(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.write.max-inflight:32}") private val maxInflight: Int,
    @Value("\${graphmesh.cassandra.write.timeout:30s}") private val timeout: Duration,
) {
    fun executeAll(statements: List<BoundStatement>) {
        if (statements.isEmpty()) return
        runBlocking {
            withTimeout(timeout.toMillis()) {
                val semaphore = Semaphore(maxInflight)
                coroutineScope {
                    statements.forEach { stmt ->
                        semaphore.acquire()
                        launch(Dispatchers.IO) {
                            try {
                                session.executeAsync(stmt).toCompletableFuture().await()
                            } finally {
                                semaphore.release()
                            }
                        }
                    }
                }
            }
        }
    }
}
