# Design: Feature 50 — Async Parallel Cassandra Writes

Stand: 2026-04-15
Bezieht sich auf: `docs/features/50-async-parallel-cassandra-writes.md`

## Kontext und Ziel

`CassandraQuadStore` und `CassandraRowStore` senden heute Quad- bzw. Row-Writes als `LOGGED BATCH`. Das produziert serverseitige `batch size exceeds threshold` Warnungen (z.B. 24 KB gegen 5 KB `batch_size_warn_threshold_in_kb`) und bei groesseren Ingestions potenziell harte Abbrueche am `batch_size_fail_threshold_in_kb` (default 50 KB). LOGGED BATCH ist in Cassandra ein Atomicity-Primitive ueber Partitionen, kein Performance-Primitive — fuer unsere idempotenten Multi-Table-Inserts das falsche Werkzeug. Die Apache-Cassandra-FAQ empfiehlt explizit *"asynchronous INSERTs or true bulk-loading methods"*.

Ziel: alle Write-Pfade beider Stores auf parallele `executeAsync`-Inserts mit beschraenkter Inflight-Parallelitaet umstellen. Anwendungsseitige Semantik (Idempotenz) bleibt identisch.

## Entscheidungen (aus Brainstorming)

| Entscheidung | Option | Begruendung |
|---|---|---|
| Scope | **B — Pragmatisch** | Alle Write-Pfade in beiden Stores. Nur so ist Akzeptanzkriterium "kein `BatchStatement`-Import mehr" einhaltbar. Out-of-scope: Reads. |
| Fehler-Semantik | **A — Fail-fast + Cancel** | Erster Fehler cancelt alle inflight Futures ueber `coroutineScope`-Struktur. Exception propagiert sofort, identisch zu heutigem Teilfehler-Verhalten ohne Rollback. |
| Observability | **B — Keine Metrics** | YAGNI. Feature-Ziel ist Warning weg + Latenz runter, nicht Telemetrie. Micrometer kann trivial nachgeruestet werden. |

## Architektur

### Neu: `AsyncCqlWriter`

Zentrale Parallel-Writer-Utility im Paket `com.agentwork.graphmesh.storage`. Cross-Feature-Nutzung (durch `structured`-Modul) erfolgt ueber die Spring-Bean-API, nicht ueber interne Klassen — konform zur Modulith-Regel.

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

Design-Notizen:
- `Semaphore(maxInflight)` begrenzt gleichzeitige Inflight-Futures und schuetzt den Coordinator vor Flood.
- `coroutineScope` propagiert die erste Exception und cancelt alle Siblings — Fehler-Semantik A.
- `withTimeout(timeout)` verhindert haengende Writes, falls der Cluster nicht antwortet.
- Keine eigenen Threads; der Java-Driver nutzt intern einen Netty-Loop. `Dispatchers.IO` ist nur fuer den Coroutine-Join.

**Classpath-Vorbedingung:** `kotlinx.coroutines.future.await` lebt in `kotlinx-coroutines-core` (ab 1.7) bzw. `kotlinx-coroutines-jdk8` (aelter). Aktuell kein expliziter Coroutines-Eintrag in `build.gradle.kts` — Abhaengigkeit kommt transitiv ueber Koog 0.7.3. Der Plan muss pruefen und ggf. `org.jetbrains.kotlinx:kotlinx-coroutines-core` (oder `-jdk8`) explizit ergaenzen.

### Umbau `CassandraQuadStore`

Betroffene Methoden (`com.agentwork.graphmesh.storage.CassandraQuadStore`):

- `insert(collection, quad)` — `BatchStatement` raus, `asyncCqlWriter.executeAll(buildInsertStatements(collection, quad))`.
- `insertBatch(collection, quads)` — `quads.flatMap { buildInsertStatements(collection, it) }`, dann `executeAll`. `chunked(BATCH_CHUNK_SIZE)` entfaellt; `BATCH_CHUNK_SIZE`-Konstante wird geloescht.
- `delete(collection, quad)` — analog mit `buildDeleteStatements`.
- `deleteCollection(collection)` — Entity-Partition-Deletes, die heute sequentiell per Schleife ausgefuehrt werden, werden zu `List<BoundStatement>` gesammelt und via `executeAll` parallelisiert. Der finale Kollektions-Delete bleibt ein einzelner `session.execute`.
- Helper-Methoden `addInsertToBatch` / `addDeleteToBatch` (`BatchStatementBuilder`-basiert) werden durch `buildInsertStatements` / `buildDeleteStatements` (`List<BoundStatement>`-returning) ersetzt.
- Entfernte Imports: `BatchStatement`, `BatchStatementBuilder`, `BatchType`.

Public-API von `QuadStore`-Interface bleibt unveraendert.

### Umbau `CassandraRowStore`

Betroffene Methoden (`com.agentwork.graphmesh.structured.CassandraRowStore`):

- `insert(row)` — `BatchStatement` raus; Schema-Lookup weiterhin synchron *vor* dem Statement-Build (fehler-early-out bei unbekanntem Schema), dann Statements sammeln und `executeAll`.
- `insertBatch(rows)` — analog; Schema-Lookup pro Row, dann gesamte Statement-Liste parallel.
- `deleteByCollection(collection)` und `deleteBySchema(collection, schema)`:
  - **Nebeneffekt**: aktuelle Implementierung konkateniert `collection`/`schemaName`/`indexName` direkt in CQL-Strings (`session.execute("DELETE ... WHERE collection = '${collection}' AND ...")`) — **CQL-Injection-Risiko**. Umbau nutzt ein neues `PreparedStatement` `deleteRows` (`DELETE FROM $keyspace.rows WHERE collection = ? AND schema_name = ? AND index_name = ?`).
  - Row-DELETEs + Partition-DELETEs werden in eine Liste gesammelt und gemeinsam via `executeAll` abgesetzt.
- Entfernte Imports: `BatchStatement`, `BatchType`.

### Konfiguration

`src/main/resources/application.yml`:

```yaml
graphmesh:
  cassandra:
    write:
      max-inflight: 32
      timeout: 30s
```

Sinnvolle Range fuer `max-inflight`: 16–128, abhaengig von Cluster-Groesse und Connection-Pool. Default 32 passt fuer Single-Node-Dev.

## Tests

Alle Tests als Unit-Tests mit gemocktem `CqlSession`. Keine Testcontainers, kein docker-compose.

| Datei | Typ | Inhalt |
|---|---|---|
| `src/test/kotlin/com/agentwork/graphmesh/storage/AsyncCqlWriterTest.kt` | NEU | (1) N Statements -> N `executeAsync`-Calls; (2) `max-inflight=1` -> sequentielle Ausfuehrung (via aktiver-Call-Counter); (3) `max-inflight=4` mit 20 Statements -> nie >4 gleichzeitig; (4) Failure eines Statements -> Exception propagiert, Siblings gecancelt; (5) Leere Liste -> no-op |
| `src/test/kotlin/com/agentwork/graphmesh/storage/CassandraQuadStoreTest.kt` | NEU | Verifiziert `insert`/`insertBatch`/`delete`/`deleteCollection` erzeugen N `executeAsync`-Calls statt `session.execute(BatchStatement)`. Keine `BatchStatement`-Erwartungen mehr. |
| `src/test/kotlin/com/agentwork/graphmesh/structured/CassandraRowStoreTest.kt` | Anpassen | Bestehende Tests von BATCH- auf N-Statements-Semantik. Zusatz: `deleteByCollection`/`deleteBySchema` verwenden `deleteRows`-Prepared-Statement statt inline-SQL. |

**Parallelismus-Messmethode:** `CqlSession`-Mock liefert `CompletableFuture`, das erst auf expliziten `complete()`-Call antwortet. Der Test zaehlt beim Aufruf von `executeAsync` einen atomaren Counter hoch, beim `complete` wieder runter, und beobachtet das Maximum.

## Akzeptanzkriterien (aus Feature-Doc, aktualisiert)

- [ ] Cassandra-Log zeigt bei RDF-Import (>1000 Triples) keine `batch size exceeds threshold`-Warnung mehr.
- [ ] `insertBatch(collection, 1000 Quads)` Laufzeit <= 2x der alten Laufzeit (Regression-Guard).
- [ ] Bei simuliertem Statement-Fehler wird Exception propagiert und Siblings gecancelt (Fehler-Semantik A).
- [ ] `max-inflight=1` in Test -> sequentielle Ausfuehrung beweisbar.
- [ ] `QuadStore`-Interface und `CassandraRowStore`-Public-API bleiben signatur-unveraendert.
- [ ] Kein `BatchStatement`-/`BatchType`-Import mehr in `CassandraQuadStore` und `CassandraRowStore`.
- [ ] Bestehende Tests von Feature 02 / 07 / 22 bleiben gruen.

## Offene Fragen / Risiken

- **Classpath**: `kotlinx-coroutines-core` als direkte Dep noch nicht explizit in `build.gradle.kts`. Plan-Phase: pruefen, ob transitiv verfuegbar, sonst ergaenzen.
- **Connection-Pool-Tuning**: Default-`max-inflight=32` kann bei Single-Node-Dev und aggressiv ingestion-lastigen Smoke-Tests die lokalen Driver-Connections saturieren. Kein Tuning im Scope dieses Features — nur Konfigurierbarkeit.

## Divergenzen von der Feature-Doc

- Feature-Doc spricht von `QuadStoreService` — reale Klasse heisst `CassandraQuadStore`. Implementierung folgt dem realen Namen.
- Feature-Doc nennt fuer `CassandraRowStore` Methoden `insertRow`/`deleteRow` — tatsaechliche API ist `insert`/`insertBatch`/`deleteByCollection`/`deleteBySchema`. Scope B deckt alle vier ab.
- `CassandraQuadStore.insert` (Einzel-Pfad) ist in der Doc nicht explizit benannt, wurde aber per Scope B mit umgestellt.
- Observability (Subsection 4) per Entscheidung B raus.
