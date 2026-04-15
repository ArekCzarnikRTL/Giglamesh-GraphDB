# Feature 50 — Async Parallel Cassandra Writes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Umstellung aller Cassandra-Write-Pfade in `CassandraQuadStore` und `CassandraRowStore` von `LOGGED BATCH` auf parallele `executeAsync`-Inserts mit beschraenkter Inflight-Parallelitaet.

**Architecture:** Neue zentrale Utility `AsyncCqlWriter` (Spring-Component) fuehrt `List<BoundStatement>` parallel via `CqlSession.executeAsync` aus, begrenzt durch `Semaphore(maxInflight)` und `withTimeout`. Fehler-Semantik: fail-fast mit Cancel via `coroutineScope`. Beide Stores refactorn ihre Write-Pfade so, dass sie Statements in Listen sammeln und `AsyncCqlWriter.executeAll` rufen. Kein `BatchStatement` mehr.

**Tech Stack:** Kotlin 2.2.21, Java 21, Spring Boot 4.0.5, DataStax Java Driver 4 (via `spring-boot-starter-cassandra`), kotlinx-coroutines (transitiv via Koog 0.7.3), MockK 1.13.16, kotlin-test-junit5.

**Spec:** `docs/superpowers/specs/2026-04-15-feature-50-async-parallel-cassandra-writes-design.md`

**Feature-Doc:** `docs/features/50-async-parallel-cassandra-writes.md`

---

## File Structure

### Erstellen
- `src/main/kotlin/com/agentwork/graphmesh/storage/AsyncCqlWriter.kt` — Utility
- `src/test/kotlin/com/agentwork/graphmesh/storage/AsyncCqlWriterTest.kt` — Unit-Tests
- `src/test/kotlin/com/agentwork/graphmesh/storage/CassandraQuadStoreTest.kt` — NEU

### Aendern
- `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt` (Datei enthaelt Klasse `CassandraQuadStore`) — BATCH raus, Statement-Builder + `AsyncCqlWriter`-Nutzung rein.
- `src/main/kotlin/com/agentwork/graphmesh/structured/CassandraRowStore.kt` — BATCH raus, `deleteRows`-Prepared-Statement rein, inline-SQL-DELETEs raus.
- `src/main/resources/application.yml` — `graphmesh.cassandra.write.max-inflight` + `.timeout`.
- `src/test/kotlin/com/agentwork/graphmesh/structured/CassandraRowStoreTest.kt` — neue Unit-Tests fuer den refaktorierten Store (bisher nur `extractIndexValue`-Tests).
- `build.gradle.kts` — *nur falls* `kotlinx-coroutines-core` nicht transitiv verfuegbar ist (Task 1 klaert das).

---

## Task 1: Coroutines-Dependency verifizieren

**Files:**
- Check: `build.gradle.kts`
- Potentially modify: `build.gradle.kts`

- [ ] **Step 1: Pruefen, ob `kotlinx-coroutines-core` transitiv verfuegbar ist**

Run:
```bash
./gradlew dependencies --configuration runtimeClasspath | grep -E "kotlinx-coroutines-(core|jdk8)"
```

Expected: mindestens eine Zeile mit `kotlinx-coroutines-core` (Version 1.8+ via Koog 0.7.3). Wenn das Kommando Ausgabe liefert, weiter zu Step 3.

- [ ] **Step 2: Falls fehlend — explizit ergaenzen**

Nur ausfuehren, wenn Step 1 leer ist. In `build.gradle.kts` unter `dependencies { ... }` nach der Koog-Zeile ergaenzen:

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
```

Dann erneut:

```bash
./gradlew dependencies --configuration runtimeClasspath | grep kotlinx-coroutines-core
```

Expected: Version >=1.8 wird gelistet.

- [ ] **Step 3: `kotlinx.coroutines.future.await` verfuegbar**

Ab coroutines 1.8+ lebt `kotlinx.coroutines.future.await()` fuer `CompletionStage` im Main-Artefakt `kotlinx-coroutines-core` (keine separate `-jdk8`-Dep noetig). Nichts zu tun — nur dokumentieren.

- [ ] **Step 4: Commit (nur falls Step 2 ausgefuehrt)**

```bash
git add build.gradle.kts
git commit -m "build: add explicit kotlinx-coroutines-core dependency for feature 50"
```

Wenn Step 2 nicht noetig war: kein Commit, weiter zur naechsten Task.

---

## Task 2: `application.yml` Konfiguration

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Konfig-Block ergaenzen**

In `src/main/resources/application.yml` unter dem bestehenden `graphmesh:`-Root ergaenzen (bzw. erweitern, falls `graphmesh.cassandra` bereits existiert):

```yaml
graphmesh:
  cassandra:
    write:
      max-inflight: 32
      timeout: 30s
```

Hinweis: Falls `graphmesh.cassandra.keyspace` oder andere Sub-Keys bereits existieren, nicht ueberschreiben — nur den `write`-Teilbaum hinzufuegen.

- [ ] **Step 2: App-Kontext laedt ohne Fehler**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL. (Voller Kontext-Test folgt in Task 6.)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "config: add graphmesh.cassandra.write.{max-inflight,timeout} for feature 50"
```

---

## Task 3: `AsyncCqlWriter` — Test-First

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/storage/AsyncCqlWriterTest.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/AsyncCqlWriter.kt`

- [ ] **Step 1: Failing Test schreiben**

Erstelle `src/test/kotlin/com/agentwork/graphmesh/storage/AsyncCqlWriterTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Test laufen lassen, Failure verifizieren**

```bash
./gradlew test --tests "com.agentwork.graphmesh.storage.AsyncCqlWriterTest"
```

Expected: Compile-Fehler `unresolved reference: AsyncCqlWriter`.

- [ ] **Step 3: `AsyncCqlWriter` implementieren**

Erstelle `src/main/kotlin/com/agentwork/graphmesh/storage/AsyncCqlWriter.kt`:

```kotlin
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
```

- [ ] **Step 4: Tests laufen lassen, alle gruen**

```bash
./gradlew test --tests "com.agentwork.graphmesh.storage.AsyncCqlWriterTest"
```

Expected: 5 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/AsyncCqlWriter.kt \
        src/test/kotlin/com/agentwork/graphmesh/storage/AsyncCqlWriterTest.kt
git commit -m "feat(storage): add AsyncCqlWriter for parallel cassandra writes"
```

---

## Task 4: `CassandraQuadStore` refaktorieren

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/storage/CassandraQuadStoreTest.kt`

- [ ] **Step 1: Failing Test schreiben**

Erstelle `src/test/kotlin/com/agentwork/graphmesh/storage/CassandraQuadStoreTest.kt`:

```kotlin
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
        objectType = ObjectType.IRI,
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
```

- [ ] **Step 2: Test laufen lassen, Failure verifizieren**

```bash
./gradlew test --tests "com.agentwork.graphmesh.storage.CassandraQuadStoreTest"
```

Expected: Compile-Fehler (Konstruktor-Signatur von `CassandraQuadStore` hat noch kein `AsyncCqlWriter`-Argument).

- [ ] **Step 3: `CassandraQuadStore` umbauen**

Ersetze den Inhalt von `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt` durch:

```kotlin
package com.agentwork.graphmesh.storage

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service

@Service
@DependsOn("cassandraSchemaInitializer")
class CassandraQuadStore(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String,
    private val asyncCqlWriter: AsyncCqlWriter,
) : QuadStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    private lateinit var insertEntity: PreparedStatement
    private lateinit var insertCollection: PreparedStatement
    private lateinit var deleteEntity: PreparedStatement
    private lateinit var deleteCollectionRow: PreparedStatement
    private lateinit var selectCollection: PreparedStatement
    private lateinit var deleteEntityPartition: PreparedStatement

    private lateinit var queryStatements: Map<Int, PreparedStatement>

    @jakarta.annotation.PostConstruct
    fun prepareStatements() {
        insertEntity = session.prepare("""
            INSERT INTO $keyspace.quads_by_entity
                (collection, entity, role, p, otype, s, o, d, dtype, lang)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())

        insertCollection = session.prepare("""
            INSERT INTO $keyspace.quads_by_collection
                (collection, d, s, p, o, otype, dtype, lang)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())

        deleteEntity = session.prepare("""
            DELETE FROM $keyspace.quads_by_entity
            WHERE collection = ? AND entity = ? AND role = ?
                AND p = ? AND otype = ? AND s = ? AND o = ? AND d = ? AND dtype = ? AND lang = ?
        """.trimIndent())

        deleteCollectionRow = session.prepare("""
            DELETE FROM $keyspace.quads_by_collection
            WHERE collection = ? AND d = ? AND s = ? AND p = ? AND o = ? AND otype = ? AND dtype = ? AND lang = ?
        """.trimIndent())

        selectCollection = session.prepare("""
            SELECT d, s, p, o, otype, dtype, lang FROM $keyspace.quads_by_collection
            WHERE collection = ?
        """.trimIndent())

        deleteEntityPartition = session.prepare("""
            DELETE FROM $keyspace.quads_by_entity
            WHERE collection = ? AND entity = ?
        """.trimIndent())

        prepareQueryStatements()
    }

    override fun insert(collection: String, quad: StoredQuad) {
        asyncCqlWriter.executeAll(buildInsertStatements(collection, quad))
    }

    override fun insertBatch(collection: String, quads: List<StoredQuad>) {
        val statements = quads.flatMap { buildInsertStatements(collection, it) }
        asyncCqlWriter.executeAll(statements)
    }

    override fun delete(collection: String, quad: StoredQuad) {
        asyncCqlWriter.executeAll(buildDeleteStatements(collection, quad))
    }

    override fun deleteCollection(collection: String) {
        val rows = session.execute(selectCollection.bind(collection))
        val entities = mutableSetOf<String>()
        for (row in rows) {
            entities.add(row.getString("s")!!)
            entities.add(row.getString("p")!!)
            entities.add(row.getString("o")!!)
            entities.add(row.getString("d")!!)
        }

        val partitionDeletes = entities.map { entity ->
            deleteEntityPartition.bind(collection, entity)
        }
        asyncCqlWriter.executeAll(partitionDeletes)

        session.execute("DELETE FROM $keyspace.quads_by_collection WHERE collection = ?", collection)
    }

    private fun buildInsertStatements(collection: String, quad: StoredQuad): List<BoundStatement> {
        val s = quad.subject
        val p = quad.predicate
        val o = quad.objectValue
        val d = quad.dataset
        val otype = quad.objectType.code
        val dtype = quad.datatype
        val lang = quad.language
        return listOf(
            insertEntity.bind(collection, s, "S", p, otype, s, o, d, dtype, lang),
            insertEntity.bind(collection, p, "P", p, otype, s, o, d, dtype, lang),
            insertEntity.bind(collection, o, "O", p, otype, s, o, d, dtype, lang),
            insertEntity.bind(collection, d, "G", p, otype, s, o, d, dtype, lang),
            insertCollection.bind(collection, d, s, p, o, otype, dtype, lang),
        )
    }

    private fun buildDeleteStatements(collection: String, quad: StoredQuad): List<BoundStatement> {
        val s = quad.subject
        val p = quad.predicate
        val o = quad.objectValue
        val d = quad.dataset
        val otype = quad.objectType.code
        val dtype = quad.datatype
        val lang = quad.language
        return listOf(
            deleteEntity.bind(collection, s, "S", p, otype, s, o, d, dtype, lang),
            deleteEntity.bind(collection, p, "P", p, otype, s, o, d, dtype, lang),
            deleteEntity.bind(collection, o, "O", p, otype, s, o, d, dtype, lang),
            deleteEntity.bind(collection, d, "G", p, otype, s, o, d, dtype, lang),
            deleteCollectionRow.bind(collection, d, s, p, o, otype, dtype, lang),
        )
    }

    override fun query(collection: String, query: QuadQuery, limit: Int?): List<StoredQuad> {
        val s = query.subject
        val p = query.predicate
        val o = query.objectValue
        val d = query.dataset
        val pattern = resolvePattern(s, p, o, d)
        val stmt = queryStatements.getValue(pattern)

        val bound = when (pattern) {
            1  -> stmt.bind(collection, s, "S", p)
            2  -> stmt.bind(collection, s, "S", p)
            3  -> stmt.bind(collection, s, "S", p)
            4  -> stmt.bind(collection, s, "S", p)
            5  -> stmt.bind(collection, s, "S")
            6  -> stmt.bind(collection, s, "S")
            7  -> stmt.bind(collection, s, "S")
            8  -> stmt.bind(collection, s, "S")
            9  -> stmt.bind(collection, o, "O", p)
            10 -> stmt.bind(collection, o, "O", p)
            11 -> stmt.bind(collection, p, "P")
            12 -> stmt.bind(collection, p, "P")
            13 -> stmt.bind(collection, o, "O")
            14 -> stmt.bind(collection, o, "O")
            15 -> stmt.bind(collection, d, "G")
            16 -> stmt.bind(collection)
            else -> error("Unknown pattern: $pattern")
        }

        val rows = session.execute(bound)
        val result = rows.mapNotNull { row ->
            val quad = StoredQuad(
                subject = row.getString("s")!!,
                predicate = row.getString("p")!!,
                objectValue = row.getString("o")!!,
                dataset = row.getString("d")!!,
                objectType = ObjectType.fromCode(row.getString("otype")!!),
                datatype = row.getString("dtype") ?: "",
                language = row.getString("lang") ?: ""
            )
            if (matchesFilter(quad, s, p, o, d)) quad else null
        }
        return if (limit != null) result.take(limit) else result
    }

    override fun findSubjects(collection: String, substringMatch: String, limit: Int): List<String> {
        val needle = substringMatch.lowercase()
        return query(collection, QuadQuery(), limit = null)
            .asSequence()
            .map { it.subject }
            .filter { it.lowercase().contains(needle) }
            .distinct()
            .take(limit)
            .toList()
    }

    override fun scrollAll(collection: String): List<StoredQuad> = query(collection, QuadQuery())

    override fun isEmpty(collection: String): Boolean =
        query(collection, QuadQuery(), limit = 1).isEmpty()

    override fun aggregateMetadata(collection: String): GraphMetadataView {
        val all = query(collection, QuadQuery(), limit = null)
        val datasets = all.map { it.dataset }.distinct().sorted().take(200)
        val predicates = all.map { it.predicate }.distinct().sorted().take(200)
        val entityTypes = all.asSequence()
            .filter { it.predicate == RDF_TYPE_URI }
            .map { it.objectValue }
            .distinct()
            .sorted()
            .take(200)
            .toList()
        return GraphMetadataView(datasets, predicates, entityTypes)
    }

    override fun deleteByDataset(collection: String, dataset: String): Long {
        val quads = query(collection, QuadQuery(dataset = dataset))
        quads.forEach { delete(collection, it) }
        return quads.size.toLong()
    }

    override fun stats(collection: String): QuadStoreStats {
        val meta = aggregateMetadata(collection)
        val allQuads = scrollAll(collection)
        return QuadStoreStats(
            tripleCount = allQuads.size.toLong(),
            entityCount = allQuads.map { it.subject }.distinct().size.toLong(),
            predicateCount = meta.predicates.size.toLong(),
            datasets = meta.datasets
        )
    }

    private fun resolvePattern(s: String?, p: String?, o: String?, d: String?): Int {
        val known = listOf(s != null, p != null, o != null, d != null)
        return when (known) {
            listOf(true, true, true, true)    -> 1
            listOf(true, true, true, false)   -> 2
            listOf(true, true, false, true)   -> 3
            listOf(true, true, false, false)  -> 4
            listOf(true, false, true, true)   -> 5
            listOf(true, false, true, false)  -> 6
            listOf(true, false, false, true)  -> 7
            listOf(true, false, false, false) -> 8
            listOf(false, true, true, true)   -> 9
            listOf(false, true, true, false)  -> 10
            listOf(false, true, false, true)  -> 11
            listOf(false, true, false, false) -> 12
            listOf(false, false, true, true)  -> 13
            listOf(false, false, true, false) -> 14
            listOf(false, false, false, true) -> 15
            listOf(false, false, false, false) -> 16
            else -> error("Invalid pattern")
        }
    }

    private fun matchesFilter(quad: StoredQuad, s: String?, p: String?, o: String?, d: String?): Boolean {
        if (s != null && quad.subject != s) return false
        if (p != null && quad.predicate != p) return false
        if (o != null && quad.objectValue != o) return false
        if (d != null && quad.dataset != d) return false
        return true
    }

    private fun prepareQueryStatements() {
        val base = "SELECT s, p, o, d, otype, dtype, lang FROM $keyspace.quads_by_entity WHERE collection = ? AND entity = ? AND role = ?"
        queryStatements = mapOf(
            1  to session.prepare("$base AND p = ?"),
            2  to session.prepare("$base AND p = ?"),
            3  to session.prepare("$base AND p = ?"),
            4  to session.prepare("$base AND p = ?"),
            5  to session.prepare("$base"),
            6  to session.prepare("$base"),
            7  to session.prepare("$base"),
            8  to session.prepare("$base"),
            9  to session.prepare("$base AND p = ?"),
            10 to session.prepare("$base AND p = ?"),
            11 to session.prepare("$base"),
            12 to session.prepare("$base"),
            13 to session.prepare("$base"),
            14 to session.prepare("$base"),
            15 to session.prepare("$base"),
            16 to session.prepare("SELECT s, p, o, d, otype, dtype, lang FROM $keyspace.quads_by_collection WHERE collection = ?")
        )
    }
}
```

Wichtige Aenderungen gegenueber dem Original:
- Neuer Konstruktor-Parameter `asyncCqlWriter: AsyncCqlWriter`.
- `insert`/`insertBatch`/`delete` rufen `asyncCqlWriter.executeAll(...)` statt `session.execute(batch.build())`.
- `deleteCollection` parallelisiert die Entity-Partition-Deletes, finaler Kollektions-Delete bleibt sync.
- Konstante `BATCH_CHUNK_SIZE` und Helper `addInsertToBatch`/`addDeleteToBatch` geloescht — ersetzt durch `buildInsertStatements`/`buildDeleteStatements`.
- Imports `BatchStatement`, `BatchStatementBuilder`, `BatchType` entfernt.

- [ ] **Step 4: Tests laufen lassen, alle gruen**

```bash
./gradlew test --tests "com.agentwork.graphmesh.storage.CassandraQuadStoreTest"
./gradlew test --tests "com.agentwork.graphmesh.storage.*"
```

Expected: alle Tests in `com.agentwork.graphmesh.storage.*` gruen (inkl. `StoredQuadTest`, `QuadStoreDefaultMethodsTest`, `QuadStoreLimitTest`). `QuadStoreIntegrationTest` braucht docker-compose und kann erwartungsgemaess fehlschlagen/skippen — das war vorher auch so.

- [ ] **Step 5: Kein BatchStatement-Import mehr**

```bash
grep -n "BatchStatement\|BatchType" src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt
```

Expected: keine Ausgabe (exit 1).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt \
        src/test/kotlin/com/agentwork/graphmesh/storage/CassandraQuadStoreTest.kt
git commit -m "refactor(storage): switch CassandraQuadStore to AsyncCqlWriter (feature 50)"
```

---

## Task 5: `CassandraRowStore` refaktorieren

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/structured/CassandraRowStore.kt`
- Modify: `src/test/kotlin/com/agentwork/graphmesh/structured/CassandraRowStoreTest.kt`

- [ ] **Step 1: Failing Tests erweitern**

Ersetze den Inhalt von `src/test/kotlin/com/agentwork/graphmesh/structured/CassandraRowStoreTest.kt` durch:

```kotlin
package com.agentwork.graphmesh.structured

import com.agentwork.graphmesh.storage.AsyncCqlWriter
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.BatchStatement
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
        verify(exactly = 0) { session.execute(any<BatchStatement>()) }
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
        verify(exactly = 0) { session.execute(any<BatchStatement>()) }
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
        verify(exactly = 0) { session.execute(any<String>()) }
        verify(exactly = 0) { session.execute(any<BatchStatement>()) }
    }
}
```

- [ ] **Step 2: Test laufen lassen, Failure verifizieren**

```bash
./gradlew test --tests "com.agentwork.graphmesh.structured.CassandraRowStoreTest"
```

Expected: Compile-Fehler (Konstruktor-Signatur + `prepareStatements` bisher nicht public + neue Prepared-Statement-API fehlt).

- [ ] **Step 3: `CassandraRowStore` umbauen**

Ersetze den Inhalt von `src/main/kotlin/com/agentwork/graphmesh/structured/CassandraRowStore.kt` durch:

```kotlin
package com.agentwork.graphmesh.structured

import com.agentwork.graphmesh.storage.AsyncCqlWriter
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service

@Service
@DependsOn("cassandraRowSchemaInitializer")
class CassandraRowStore(
    private val session: CqlSession,
    private val schemaStore: SchemaStore,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String,
    private val asyncCqlWriter: AsyncCqlWriter,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private lateinit var insertRow: PreparedStatement
    private lateinit var insertPartition: PreparedStatement
    private lateinit var selectRows: PreparedStatement
    private lateinit var selectPartitions: PreparedStatement
    private lateinit var selectPartitionsBySchema: PreparedStatement
    private lateinit var deletePartition: PreparedStatement
    private lateinit var deleteRows: PreparedStatement

    @PostConstruct
    fun prepareStatements() {
        insertRow = session.prepare("""
            INSERT INTO $keyspace.rows (collection, schema_name, index_name, index_value, data, source)
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent())

        insertPartition = session.prepare("""
            INSERT INTO $keyspace.row_partitions (collection, schema_name, index_name)
            VALUES (?, ?, ?)
        """.trimIndent())

        selectRows = session.prepare("""
            SELECT data, source FROM $keyspace.rows
            WHERE collection = ? AND schema_name = ? AND index_name = ? AND index_value = ?
            LIMIT ?
        """.trimIndent())

        selectPartitions = session.prepare("""
            SELECT schema_name, index_name FROM $keyspace.row_partitions
            WHERE collection = ?
        """.trimIndent())

        selectPartitionsBySchema = session.prepare("""
            SELECT index_name FROM $keyspace.row_partitions
            WHERE collection = ? AND schema_name = ?
        """.trimIndent())

        deletePartition = session.prepare("""
            DELETE FROM $keyspace.row_partitions
            WHERE collection = ? AND schema_name = ? AND index_name = ?
        """.trimIndent())

        deleteRows = session.prepare("""
            DELETE FROM $keyspace.rows
            WHERE collection = ? AND schema_name = ? AND index_name = ?
        """.trimIndent())
    }

    fun insert(row: DataRow) {
        val schema = schemaStore.load(row.schemaName)
            ?: throw IllegalArgumentException("Schema '${row.schemaName}' not found")
        asyncCqlWriter.executeAll(buildInsertStatements(row, schema))
        logger.debug("Inserted row into {}/{} with {} indexes",
            row.collection, row.schemaName, schema.allIndexNames.size)
    }

    fun insertBatch(rows: List<DataRow>) {
        if (rows.isEmpty()) return
        val statements = rows.flatMap { row ->
            val schema = schemaStore.load(row.schemaName)
                ?: throw IllegalArgumentException("Schema '${row.schemaName}' not found")
            buildInsertStatements(row, schema)
        }
        asyncCqlWriter.executeAll(statements)
        logger.debug("Batch inserted {} rows", rows.size)
    }

    private fun buildInsertStatements(row: DataRow, schema: TableSchema): List<BoundStatement> {
        val statements = mutableListOf<BoundStatement>()
        for (indexName in schema.allIndexNames) {
            val indexValue = extractIndexValue(indexName, row.values)
            statements += insertRow.bind(
                row.collection, row.schemaName, indexName, indexValue, row.values, row.source,
            )
            statements += insertPartition.bind(row.collection, row.schemaName, indexName)
        }
        return statements
    }

    fun query(query: StructuredQuery): QueryResult {
        val resultSet = session.execute(selectRows.bind(
            query.collection, query.schemaName, query.indexName, query.indexValue, query.limit + 1
        ))

        val rows = mutableListOf<DataRow>()
        for (cassandraRow in resultSet) {
            if (rows.size >= query.limit) break
            rows.add(DataRow(
                collection = query.collection,
                schemaName = query.schemaName,
                values = cassandraRow.getMap("data", String::class.java, String::class.java) ?: emptyMap(),
                source = cassandraRow.getString("source")
            ))
        }

        val hasMore = resultSet.availableWithoutFetching > 0 || rows.size == query.limit
        return QueryResult(rows = rows, totalCount = rows.size, hasMore = hasMore)
    }

    fun deleteByCollection(collection: String) {
        val partitions = session.execute(selectPartitions.bind(collection))
        val statements = mutableListOf<BoundStatement>()
        for (row in partitions) {
            val schemaName = row.getString("schema_name")!!
            val indexName = row.getString("index_name")!!
            statements += deleteRows.bind(collection, schemaName, indexName)
            statements += deletePartition.bind(collection, schemaName, indexName)
        }
        asyncCqlWriter.executeAll(statements)
        logger.info("Deleted all rows for collection {}", collection)
    }

    fun deleteBySchema(collection: String, schemaName: String) {
        val partitions = session.execute(selectPartitionsBySchema.bind(collection, schemaName))
        val statements = mutableListOf<BoundStatement>()
        for (row in partitions) {
            val indexName = row.getString("index_name")!!
            statements += deleteRows.bind(collection, schemaName, indexName)
            statements += deletePartition.bind(collection, schemaName, indexName)
        }
        asyncCqlWriter.executeAll(statements)
        logger.info("Deleted rows for collection {}, schema {}", collection, schemaName)
    }
}

internal fun extractIndexValue(indexName: String, values: Map<String, String>): List<String> {
    val fields = indexName.split(",")
    return fields.map { field -> values[field] ?: "" }
}
```

Wichtige Aenderungen:
- Konstruktor bekommt `asyncCqlWriter: AsyncCqlWriter`.
- Neuer Prepared-Statement `deleteRows` ersetzt die inline-konkatenierten `DELETE FROM $keyspace.rows WHERE ... = '${...}'`-Strings (CQL-Injection-Fix als Nebeneffekt).
- `BatchStatement`/`BatchType`-Imports entfernt.
- `insert`/`insertBatch`/`deleteByCollection`/`deleteBySchema` sammeln Statements und rufen `asyncCqlWriter.executeAll`.

- [ ] **Step 4: Tests laufen lassen, alle gruen**

```bash
./gradlew test --tests "com.agentwork.graphmesh.structured.*"
```

Expected: alle Tests in `com.agentwork.graphmesh.structured.*` gruen.

- [ ] **Step 5: Kein BatchStatement-Import mehr**

```bash
grep -n "BatchStatement\|BatchType" src/main/kotlin/com/agentwork/graphmesh/structured/CassandraRowStore.kt
```

Expected: keine Ausgabe.

- [ ] **Step 6: Kein inline-konkatenierter DELETE-SQL mehr**

```bash
grep -n "DELETE FROM.*collection.*=.*\\\$" src/main/kotlin/com/agentwork/graphmesh/structured/CassandraRowStore.kt
```

Expected: keine Ausgabe.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/structured/CassandraRowStore.kt \
        src/test/kotlin/com/agentwork/graphmesh/structured/CassandraRowStoreTest.kt
git commit -m "refactor(structured): switch CassandraRowStore to AsyncCqlWriter (feature 50)"
```

---

## Task 6: Build + Test-Gesamtlauf

- [ ] **Step 1: Kompletten Build ausfuehren**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL (Integration-Tests werden mit `-x test` uebersprungen — die brauchen docker-compose und sind nicht Scope dieser Feature-Arbeit).

Falls es fehlschlaegt an einer anderen Stelle als den Feature-50-Dateien — das sind die bekannten Pre-existing Build-Issues (ambiguer mainClass, Koog-Bean-Konflikt). Nicht im Scope fixen, aber dokumentieren in der `-done.md`.

- [ ] **Step 2: Alle Unit-Tests laufen**

```bash
./gradlew test -x test --tests "com.agentwork.graphmesh.storage.*" \
                      --tests "com.agentwork.graphmesh.structured.*" \
                      --tests "com.agentwork.graphmesh.**.*Test" \
                      -PexcludeTags=integration
```

Falls es keine `integration`-Tags gibt, stattdessen explizit nur die Unit-Tests:

```bash
./gradlew test --tests "com.agentwork.graphmesh.storage.AsyncCqlWriterTest" \
               --tests "com.agentwork.graphmesh.storage.CassandraQuadStoreTest" \
               --tests "com.agentwork.graphmesh.storage.StoredQuadTest" \
               --tests "com.agentwork.graphmesh.storage.QuadStoreDefaultMethodsTest" \
               --tests "com.agentwork.graphmesh.storage.QuadStoreLimitTest" \
               --tests "com.agentwork.graphmesh.structured.*"
```

Expected: alle gruen.

- [ ] **Step 3: Keine BATCH-Referenzen mehr in den refaktorierten Dateien**

```bash
grep -rn "BatchStatement\|BatchType\|BATCH_CHUNK_SIZE" \
    src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt \
    src/main/kotlin/com/agentwork/graphmesh/structured/CassandraRowStore.kt
```

Expected: keine Ausgabe.

- [ ] **Step 4: Kein Commit noetig**

Alle Commits sind bereits einzeln in den Tasks erfolgt.

---

## Akzeptanzkriterien (Abgleich)

- [x] Keine `BatchStatement`-Imports mehr in `CassandraQuadStore` / `CassandraRowStore` — Task 4 Step 5, Task 5 Step 5.
- [x] `max-inflight=1` -> sequentiell — Task 3 Step 1, Test 3.
- [x] Fehler propagiert, Siblings gecancelt — Task 3 Step 1, Test 5.
- [x] `QuadStore`-Interface signaturgleich — Task 4 Step 3 (kein Interface-Change, nur Klassen-Ctor-Erweiterung).
- [x] `CassandraRowStore`-Public-API signaturgleich — Task 5 Step 3 (nur Ctor-Erweiterung).
- [ ] Cassandra-Log ohne `batch size exceeds threshold` bei RDF-Import >1000 Triples — wird beim Abschluss durch Smoke-Test (`./tests/smoke-test.sh` gegen laufende Infra) verifiziert; siehe `finishing-a-development-branch`-Phase.
- [ ] Regression-Guard `insertBatch(1000 quads)` <= 2x Laufzeit — gleich, manueller Smoke-Test-Schritt.

---

## Hinweise fuer den Subagenten

- **Keine Umlaute** in Code/Docs/Commits. ae/oe/ue/ss.
- **Keine Testcontainers**, nur Unit-Tests mit MockK.
- **Direkt auf `main` committen**, kein PR, **niemals `git push`**.
- **Wenn ein Test wegen Mock-Signaturen nicht greift** (z.B. `PreparedStatement.bind(vararg Any?)` wegen Overloads): in MockK mit `*anyVararg()` bzw. explizit getippten Overload `bind(any(), any(), ...)`. Die vorgegebenen Tests nutzen `*anyVararg()` — sollte reichen.
- **`session.execute(any<BoundStatement>())`**: der DataStax-Driver hat mehrere `execute`-Overloads (String, Statement). Wenn MockK-Verifys am Overload scheitern, die Signatur gezielt casten.
- **Wenn Koog-Bean-Konflikte den Unit-Test-Start blockieren**: das ist das bekannte Pre-existing-Problem. Unit-Tests hier laden KEIN Spring-Kontext (nur MockK) — sollte nicht auftreten.
