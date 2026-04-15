# Feature 50: Async Parallel Cassandra Writes (statt LOGGED BATCH)

## Problem

Beim Schreiben von Quads (RDF-Import, Extraction-Pipelines) loggt Cassandra serverseitig Warnungen:

```
Batch for [graphmesh.quads_by_collection, graphmesh.quads_by_entity]
is of size 24227, exceeding specified threshold of 5120 by 19107.
```

`QuadStoreService.insertBatch` (Zeile 81–86) und `CassandraRowStore` senden mehrere Statements pro Quad in einem `LOGGED BATCH`. Bei `BATCH_CHUNK_SIZE = 20` entstehen 100 Statements pro Batch (je Quad 5 Inserts über 2 Tabellen), was den Cassandra-Schwellwert `batch_size_warn_threshold_in_kb` (5 KB) deutlich überschreitet.

`LOGGED BATCH` ist in Cassandra **kein Performance-Primitive**, sondern ein Atomicity-Primitive ueber Partitionen hinweg — mit teurer Coordinator-Semantik (BatchLog-Write auf 2 Nodes, erhoehter GC-Druck, lineare Latenz bei wachsender Statement-Anzahl). Fuer unser Muster (Quad wird idempotent in mehrere Lookup-Tabellen geschrieben, Atomicity ueber Partitionen nicht erforderlich, da eventuelle Re-Ingestion desselben Quads folgenlos ist) ist das das falsche Werkzeug.

Hard-Limit `batch_size_fail_threshold_in_kb` (default 50 KB) bricht bei groesseren Ingestions komplette Batches ab.

## Ziel

Umstellung aller Cassandra-Write-Pfade im Graph- und Row-Store von `LOGGED BATCH` auf **parallele `executeAsync`-Inserts** mit beschraenkter Inflight-Parallelitaet und Back-Pressure. Anwendungsseitige Semantik bleibt identisch (Idempotenz der Quad/Row-Inserts).

1. **Parallel-Writer-Helper** — zentrale Utility `AsyncCqlWriter`, die eine `List<BoundStatement>` mit konfigurierbarem Parallelismus via `session.executeAsync` abarbeitet.
2. **QuadStoreService-Umbau** — `insertBatch` und `delete` nutzen `AsyncCqlWriter`, keine `BatchStatement` mehr (ausser wo explizite Atomicity gefordert ist, aktuell: nirgends).
3. **CassandraRowStore-Umbau** — analog fuer strukturierte Daten.
4. **Konfigurierbarer Parallelismus** — `graphmesh.cassandra.write.max-inflight` (default 32) + `graphmesh.cassandra.write.timeout` (default 30s).
5. **Warning weg, Latenz runter** — Verifiziert durch RDF-Import-Smoke-Test und Cassandra-Logs (keine `batch size exceeds threshold` Warns).

## Voraussetzungen

| Abhaengigkeit                                           | Status           | Blocker? |
|---------------------------------------------------------|------------------|----------|
| `com.datastax.oss:java-driver-core` (schon in Classpath) | Verfuegbar       | Nein     |
| `kotlinx-coroutines-core` (schon in Classpath)          | Verfuegbar       | Nein     |
| Feature 02 (Cassandra Storage)                          | Implementiert    | Nein     |
| Feature 07 (RDF Graph Model)                            | Implementiert    | Nein     |

Keine Infra-Aenderung in Cassandra / docker-compose.

## Architektur

### Ist-Zustand

```kotlin
// QuadStoreService.kt:81
override fun insertBatch(collection: String, quads: List<StoredQuad>) {
    quads.chunked(BATCH_CHUNK_SIZE).forEach { chunk ->
        val batch = BatchStatement.builder(BatchType.LOGGED)
        chunk.forEach { addInsertToBatch(batch, collection, it) }
        session.execute(batch.build())   // grosser LOGGED BATCH, cross-partition
    }
}
```

Ein Quad erzeugt 5 Statements (4× `quads_by_entity` mit unterschiedlichen Partition-Keys S/P/O/G, 1× `quads_by_collection`). 20 Quads → 100 Statements → typisch 20–30 KB Batch-Payload → Warning.

### Soll-Zustand

```kotlin
override fun insertBatch(collection: String, quads: List<StoredQuad>) {
    val statements = quads.flatMap { quad ->
        buildInsertStatements(collection, quad)   // List<BoundStatement>, 5 Stmts/Quad
    }
    asyncCqlWriter.executeAll(statements)
}
```

`asyncCqlWriter.executeAll` arbeitet die Statements mit einem Sliding-Window (default 32 inflight) parallel ab und blockt, bis alle Futures komplettieren oder eines fehlschlaegt. Bei einem Fehler: schon gestartete Inflight-Statements zu Ende laufen lassen, Exception rethrowen. Keine Rollback-Semantik — identisch zum heutigen Verhalten bei Teilschreibfehlern in einem LOGGED BATCH (das `applied`-Flag wird auch heute nicht ausgewertet).

### Subsection 1: AsyncCqlWriter-Utility

```kotlin
@Component
class AsyncCqlWriter(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.write.max-inflight:32}")
    private val maxInflight: Int,
    @Value("\${graphmesh.cassandra.write.timeout:30s}")
    private val timeout: Duration,
) {
    fun executeAll(statements: List<BoundStatement>) = runBlocking {
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
```

- Keine eigene ThreadPool-Anlage; der Cassandra-Driver nutzt seinen Netty-Loop intern. `Dispatchers.IO` ist nur fuer den Coroutine-Join noetig.
- `Semaphore` begrenzt Inflight-Futures → schuetzt Coordinator vor Flood.
- Ein Fehler kippt den `coroutineScope` → verbleibende Acquires werden gecancelt, laufende Inflights haengen nicht.

### Subsection 2: Statement-Builder trennen von Execution

```kotlin
private fun buildInsertStatements(
    collection: String,
    quad: StoredQuad
): List<BoundStatement> {
    val (s, p, o, d, otype, dtype, lang) = quad.unpack()
    return listOf(
        insertEntity.bind(collection, s, "S", p, otype, s, o, d, dtype, lang),
        insertEntity.bind(collection, p, "P", p, otype, s, o, d, dtype, lang),
        insertEntity.bind(collection, o, "O", p, otype, s, o, d, dtype, lang),
        insertEntity.bind(collection, d, "G", p, otype, s, o, d, dtype, lang),
        insertCollection.bind(collection, d, s, p, o, otype, dtype, lang),
    )
}
```

Analoge Umstellung fuer `delete`, `insertRow`, `deleteRow`.

### Subsection 3: Konfiguration

```yaml
# application.yml
graphmesh:
  cassandra:
    write:
      max-inflight: 32
      timeout: 30s
```

Werte werden per `@Value` in `AsyncCqlWriter` injiziert. Sinnvolle Range fuer `max-inflight`: 16–128, abhaengig von Cluster-Groesse und Connection-Pool. Default 32 passt fuer Single-Node-Dev.

### Subsection 4: Beobachtbarkeit (Nice-to-have, optional)

Micrometer-Counter in `AsyncCqlWriter`:

- `graphmesh.cassandra.write.statements.total`
- `graphmesh.cassandra.write.failures.total`
- `graphmesh.cassandra.write.latency` (Timer)

Erlaubt spaetere Tuning-Entscheidungen (siehe Feature 39 LLM Observability als Vorbild).

## Betroffene Dateien

### Backend

| Datei                                                                                                 | Aenderung                                                                                |
|-------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/storage/AsyncCqlWriter.kt`                                  | NEU — zentrale Parallel-Writer-Utility                                                    |
| `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt`                                | `insertBatch`, `insert`, `delete` auf `AsyncCqlWriter` umstellen; `BatchStatement`-Code entfernen |
| `src/main/kotlin/com/agentwork/graphmesh/structured/CassandraRowStore.kt`                            | `insertRow`, `deleteRow` analog umstellen                                                 |
| `src/main/resources/application.yml`                                                                 | `graphmesh.cassandra.write.max-inflight` + `.timeout` ergaenzen                           |

### Frontend

Keine Aenderungen.

### Tests

| Datei                                                                                          | Aenderung                                                                                       |
|------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/storage/AsyncCqlWriterTest.kt`                       | NEU — Unit-Test mit gemocktem `CqlSession`: Parallelismus respektiert max-inflight, Fehler werden propagiert, Success-Pfad fuer N Statements |
| `src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreServiceTest.kt`                     | Assertions an neue Semantik anpassen (kein `BatchStatement` mehr gemockt, stattdessen N `executeAsync`-Aufrufe) |
| `src/test/kotlin/com/agentwork/graphmesh/structured/CassandraRowStoreTest.kt`                 | Analog anpassen                                                                                  |
| `tests/smoke-test.sh` (RDF-Import-Schritt)                                                    | Laufzeitvergleich vorher/nachher im Kommentar dokumentieren                                      |

## Akzeptanzkriterien

- [ ] Im Cassandra-Log erscheint bei RDF-Import (>1000 Triples) keine `batch size exceeds threshold`-Warnung mehr.
- [ ] `QuadStoreService.insertBatch(collection, quads)` mit 1000 Quads schlaegt < 2× so lange wie vorher fehl _oder_ ist schneller (Regression-Guard).
- [ ] Bei simuliertem Statement-Fehler (eines von N) wird eine Exception propagiert, die verbliebenen Inflights werden abgebrochen/abgewartet, der Aufrufer erhaelt den Fehler.
- [ ] `max-inflight` ist per `application.yml` konfigurierbar und greift (nachgewiesen durch Test mit `max-inflight=1` → sequentielle Ausfuehrung).
- [ ] `QuadStoreService`-API bleibt unveraendert (Signaturen, Return-Typen, Idempotenz-Verhalten).
- [ ] Kein `BatchStatement`-Import mehr in `QuadStoreService` oder `CassandraRowStore`.
- [ ] Bestehende Tests von Feature 02 / 07 / 22 bleiben gruen.
- [ ] Bestehende Funktionalitaet bleibt unberuehrt.
