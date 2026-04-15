# Feature 50: Async Parallel Cassandra Writes — Done

Abschlussdatum: 2026-04-15
Spec: [50-async-parallel-cassandra-writes.md](50-async-parallel-cassandra-writes.md)
Design: [../superpowers/specs/2026-04-15-feature-50-async-parallel-cassandra-writes-design.md](../superpowers/specs/2026-04-15-feature-50-async-parallel-cassandra-writes-design.md)
Plan: [../superpowers/plans/2026-04-15-feature-50-async-parallel-cassandra-writes.md](../superpowers/plans/2026-04-15-feature-50-async-parallel-cassandra-writes.md)

## Zusammenfassung

Alle `LOGGED BATCH`-Writes in `CassandraQuadStore` und `CassandraRowStore` wurden auf parallele `executeAsync`-Inserts umgestellt. Die Cassandra-Warnung `batch size exceeds threshold` und das harte 50-KB-Limit sind damit aus dem Write-Pfad entfernt. Fehler-Semantik: fail-fast mit Cancel der inflight Siblings ueber `coroutineScope`.

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/storage/AsyncCqlWriter.kt`** — NEU. Spring-Component, die eine `List<BoundStatement>` parallel ueber `CqlSession.executeAsync` abarbeitet. Begrenzt durch `Semaphore(maxInflight)`, umschlossen von `withTimeout(timeout)`. Erste Exception cancelt Siblings via `coroutineScope`-Structure und wird via `runBlocking` rethrown. Kein eigener ThreadPool — der Java-Driver nutzt seinen Netty-Loop intern.
- **`src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt`** (Klasse `CassandraQuadStore`) — `insert`/`insertBatch`/`delete`/`deleteCollection` rufen `asyncCqlWriter.executeAll(...)`. `BATCH_CHUNK_SIZE` und die Helper `addInsertToBatch`/`addDeleteToBatch` wurden durch `buildInsertStatements`/`buildDeleteStatements` (List-returning) ersetzt. Keine `BatchStatement`-/`BatchType`-Imports mehr. `QuadStore`-Interface unveraendert.
- **`src/main/kotlin/com/agentwork/graphmesh/structured/CassandraRowStore.kt`** — `insert`/`insertBatch` verwenden `AsyncCqlWriter`. `deleteByCollection`/`deleteBySchema` nutzen ein neues Prepared-Statement `deleteRows`; die frueheren inline-konkatenierten DELETE-SQL-Strings (`"DELETE FROM ... WHERE collection = '${collection}' AND ..."`) sind weg. Als Nebeneffekt: **CQL-Injection-Risiko entfernt**. Keine `BatchStatement`-Imports mehr. Public-API unveraendert.
- **`src/main/resources/application.yml`** — neu: `graphmesh.cassandra.write.max-inflight: 32` und `graphmesh.cassandra.write.timeout: 30s`.

### Tests

- **`src/test/kotlin/com/agentwork/graphmesh/storage/AsyncCqlWriterTest.kt`** — NEU, 5 Tests:
  1. `N` Statements -> `N` `executeAsync`-Calls.
  2. Leere Liste ist no-op.
  3. `max-inflight=1` -> sequentielle Ausfuehrung (per atomarem Counter verifiziert).
  4. `max-inflight=4` mit 20 Statements -> nie >4 gleichzeitig.
  5. Erste Failure propagiert und haengt nicht.
- **`src/test/kotlin/com/agentwork/graphmesh/storage/CassandraQuadStoreTest.kt`** — NEU, 4 Tests: `insert`/`insertBatch`/`delete` erzeugen erwartete `executeAsync`-Counts; Regression-Guard mit 1000 Quads -> 5000 `executeAsync`-Calls, kein `BatchStatement`.
- **`src/test/kotlin/com/agentwork/graphmesh/structured/CassandraRowStoreTest.kt`** — erweitert um 3 Write-Pfad-Tests: `insert`, `insertBatch`, `deleteByCollection` (inkl. Assertion, dass kein inline-SQL `session.execute(String)` mehr gerufen wird). Bestehende `extractIndexValue`-Tests unveraendert.
- **Drive-by-Fix**: `src/test/kotlin/com/agentwork/graphmesh/api/PurgeServiceTest.kt` hatte einen vorbestehenden Compile-Error (fehlendes `orphanSweepService`-Argument aus einem frueheren Commit). Mitrepariert im `AsyncCqlWriter`-Commit, damit das Test-Set ueberhaupt kompiliert.

## Akzeptanzkriterien

- [x] Kein `BatchStatement`-/`BatchType`-Import mehr in `CassandraQuadStore` oder `CassandraRowStore` (grep).
- [x] `insertBatch` mit 1000 Quads erzeugt genau 5000 `executeAsync`-Calls, kein Batch (Unit-Test).
- [x] Bei Statement-Fehler propagiert Exception, Siblings werden gecancelt (Unit-Test).
- [x] `max-inflight=1` -> sequentiell beweisbar (Unit-Test).
- [x] `max-inflight=4` wird eingehalten (Unit-Test, 20 Statements, nie >4 concurrent).
- [x] `QuadStore`-Interface und `CassandraRowStore`-Public-API signaturgleich.
- [x] Bestehende Unit-Tests bleiben gruen (`StoredQuadTest`, `QuadStoreDefaultMethodsTest`, `QuadStoreLimitTest`, `SchemaStoreTest`, `StructuredDataServiceTest`).
- [ ] Cassandra-Log zeigt bei RDF-Import >1000 Triples keine `batch size exceeds threshold`-Warnung mehr — **noch nicht verifiziert**, erfordert laufende Infra + Smoke-Test (`docker-compose up` + `./tests/smoke-test.sh`). Offen fuer manuellen Smoke-Test.
- [ ] `insertBatch(1000)` Laufzeit <= 2x der alten Laufzeit — **noch nicht gemessen**, gleicher Smoke-Test.

## Abweichungen vom Feature-Dokument

- **Klassenname**: Die Feature-Doc nennt `QuadStoreService.insertBatch`. Tatsaechliche Klasse heisst `CassandraQuadStore` (implementiert `QuadStore`), Datei weiterhin `QuadStoreService.kt`.
- **RowStore-Methoden**: Die Feature-Doc erwaehnt `insertRow`/`deleteRow`. Tatsaechliche Methoden: `insert`/`insertBatch`/`deleteByCollection`/`deleteBySchema`. Scope B (Brainstorming-Entscheidung) deckt alle vier ab.
- **Scope-Erweiterung**: `CassandraQuadStore.insert` (Einzel-Pfad) war in der Spec nicht explizit genannt, wurde aber konsequent mit umgestellt (auch hier wurde vorher ein `LOGGED BATCH` fuer ein einzelnes Quad-Set gebaut).
- **`deleteCollection` parallelisiert**: Die sequentielle Schleife `session.execute(deleteEntityPartition.bind(...))` ueber alle Entity-Partitionen laeuft jetzt ueber `AsyncCqlWriter` — Performance-Bonus im gleichen Aufwasch.
- **CQL-Injection-Fix in RowStore**: `deleteByCollection`/`deleteBySchema` bauten bisher DELETE-SQL per String-Interpolation. Nebeneffekt des Refactorings: neues Prepared-Statement `deleteRows` ersetzt die Inline-Strings.
- **Observability (Spec Subsection 4)**: Per Brainstorming-Entscheidung B bewusst ausgelassen (YAGNI). Micrometer-Counter koennen nachgeruestet werden, sobald Bedarf besteht.
- **Fehler-Semantik**: Spec-Prosa sprach von "inflights zu Ende laufen lassen"; Sample-Code zeigte `coroutineScope`-Cancel. Brainstorming-Entscheidung **A (fail-fast + cancel)** — Code folgt dem Sample, Prosa ist im Design-Doc korrigiert.

## Konfiguration

```yaml
graphmesh:
  cassandra:
    write:
      max-inflight: 32   # 16-128 sinnvoll, abhaengig von Cluster & Connection-Pool
      timeout: 30s
```

## Offene Punkte / Technische Schulden

- **Smoke-Test-Verifikation**: Die beiden Akzeptanzkriterien "keine BATCH-Warnung im Cassandra-Log" und "Laufzeit-Regression-Guard" erfordern einen Lauf gegen echte Infra (`docker-compose up` + `./tests/smoke-test.sh`). Nicht durch Unit-Tests abdeckbar.
- **Connection-Pool-Tuning**: Default `max-inflight=32` ist fuer Single-Node-Dev ausgelegt. Bei aggressiven Multi-Node-Deployments kann ein hoeherer Wert (64-128) sinnvoll sein; ggf. im Rahmen eines spaeteren Observability-Features (Micrometer-Metriken) messbar machen.
- **Pre-existing build-issues**: Ambiguer `mainClass` und Koog-Bean-Konflikt sind weiterhin im Build sichtbar, aber out-of-scope dieses Features.

## Commits

```
b301cf3 refactor(structured): switch CassandraRowStore to AsyncCqlWriter (feature 50)
5819ec9 refactor(storage): switch CassandraQuadStore to AsyncCqlWriter (feature 50)
d3f5474 feat(storage): add AsyncCqlWriter for parallel cassandra writes
00645fc config: add graphmesh.cassandra.write.{max-inflight,timeout} for feature 50
```
