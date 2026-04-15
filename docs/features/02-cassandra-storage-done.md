# Feature 02: Cassandra Storage Layer — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/storage/StoredQuad.kt`** — Datenmodell: `StoredQuad(subject, predicate, objectValue, dataset, objectType, datatype, language)` plus `enum ObjectType { URI("U"), LITERAL("L"), QUOTED_TRIPLE("T") }` mit `fromCode`-Lookup. `QuadQuery(subject?, predicate?, objectValue?, dataset?)` als Wildcard-Query-DSL (jedes Feld nullable).
- **`src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt`** — Interface `QuadStore` mit `insert`, `insertBatch`, `delete`, `deleteCollection`, `query(..., limit)`, `findSubjects`, `scrollAll`, `isEmpty`, `aggregateMetadata`, `deleteByDataset`, `stats` sowie Default-Methoden `findByEntities` und provenance-spezifische Helfer `findSubgraphsForChunks` / `findQuotedTriplesForSubgraphs`. `QuadStoreStats` + `GraphMetadataView` als Rueckgaben.
- **`src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt`** — `@Service` `CassandraQuadStore` mit `@DependsOn("cassandraSchemaInitializer")`. Nutzt direkt `CqlSession`, bereitet in `@PostConstruct` 16 `PreparedStatement`s fuer alle Query-Patterns sowie Insert/Delete vor. `resolvePattern(s,p,o,d)` bildet Kombinationen aus bekannten Feldern auf Pattern 1-16 ab, waehlt die passende Lookup-Entity (`role = S|P|O|G`) und bindet gegebenenfalls `p`. `matchesFilter(...)` filtert in-memory, was CQL nicht per `WHERE` abdecken kann. `insert`/`insertBatch` schreiben 4 Zeilen in `quads_by_entity` (je Rolle S/P/O/G) + 1 Zeile in `quads_by_collection` in einem `BatchStatement(BatchType.LOGGED)`; `insertBatch` chunked auf 20 Quads (Schutz gegen Cassandra-Batch-Size-Limit). `deleteCollection` scannt die Collection-Partition, sammelt alle beteiligten Entitaeten, loescht die Entity-Partitionen einzeln und anschliessend die Collection-Partition.
- **`src/main/kotlin/com/agentwork/graphmesh/storage/CassandraSchemaInitializer.kt`** — `@Component` mit `@PostConstruct`-Initialisierung: legt Keyspace mit `SimpleStrategy`/`replication_factor=1` an, erstellt `quads_by_entity` und `quads_by_collection` gemaess Spec-Schema sowie zusaetzlich `context_cores` (fuer Feature ContextCore).
- **`src/main/kotlin/com/agentwork/graphmesh/storage/GraphMetadataView.kt`** — Datenklasse mit `datasets`, `predicates`, `entityTypes` (je max. 200 sortiert).
- **`src/main/kotlin/com/agentwork/graphmesh/storage/OrphanSweepService.kt`** — Zusatz-Service (nicht im Spec) fuer Orphan-Sweep einer Collection.
- **`src/main/resources/application.yml`** — `spring.cassandra.contact-points`/`port`/`local-datacenter`/`schema-action: none` plus `graphmesh.cassandra.keyspace: graphmesh`. `CqlSession` kommt ueber Spring-Boot-Auto-Configuration.

### Tests

- **`QuadStoreIntegrationTest`** (22 Tests, SpringBoot + Docker-Cassandra) — deckt alle 16 Query-Patterns, Literale mit Typ/Lang, Dataset-Filter, `deleteCollection`, `deleteByDataset` etc. ab.
- **`QuadStoreLimitTest`** (5 Tests) — Verhalten des `limit`-Parameters.
- **`QuadStoreDefaultMethodsTest`** (7 Tests) — `findByEntities`, `findSubgraphsForChunks`, `findQuotedTriplesForSubgraphs` gegen `InMemoryQuadStore`.
- **`StoredQuadTest`** (6 Tests) — Datenmodell und `ObjectType.fromCode`.
- **`InMemoryQuadStore`** — Test-Doppel fuer alle Module, implementiert das `QuadStore`-Interface.

## Abweichungen vom Feature-Dokument

- **Interface statt direkter Service-Klasse**: Spec nennt `QuadStoreService` als konkrete Klasse. Implementiert ist ein `QuadStore`-Interface plus Implementierung `CassandraQuadStore` (Bean-Name `cassandraQuadStore`). Erlaubt `InMemoryQuadStore` fuer Tests.
- **Feldname `objectValue` und `datatype`/`language`**: Spec zeigt `objectType`, `datatype`, `language` — uebernommen. `objectValue` heisst im Spec ebenfalls so, aber `QuadQuery` hat die Felder in anderer Reihenfolge als im Spec-Beispiel (keine funktionale Abweichung).
- **`QuadQuery.resolveStrategy()` / `QueryStrategy` nicht existent**: Die Strategie-Logik ist als `resolvePattern(...)` (Int 1-16) plus vorbereitete `PreparedStatement`-Map direkt im Service implementiert, nicht als separate Datenklasse. Sauberer, weil alle Statements beim Start praepariert werden.
- **Zusaetzliche Interface-Methoden**: `findSubjects`, `aggregateMetadata`, `scrollAll`, `isEmpty`, `deleteByDataset`, `stats`, `findByEntities`, `findSubgraphsForChunks`, `findQuotedTriplesForSubgraphs` sind im Spec nicht genannt — sie kamen mit spaeteren Features dazu, leben aber konsistent im selben Interface.
- **`context_cores`-Tabelle**: `CassandraSchemaInitializer` erstellt ausserdem eine Tabelle fuer das ContextCore-Feature. Im Spec (Feature 02) nicht vorgesehen, aber zentral hier konsolidiert.
- **Pattern-Filterung teils in-memory**: Einige Patterns koennen nicht ausschliesslich ueber den Partition-Key abgebildet werden und nutzen `matchesFilter` nach der CQL-Query. `ALLOW FILTERING` wird trotzdem nicht verwendet — Spec-Kriterium erfuellt.
- **Package**: Spec nennt `com.agentwork.graphmesh.storage` — stimmt exakt. (Memory-Hinweis: Feature-Specs verweisen oft auf veraltete Packages; hier passen Spec und Realitaet, in Feature 03/04/05 jedoch nicht.)

## Akzeptanzkriterien

- [x] `quads_by_entity` und `quads_by_collection` werden automatisch erstellt — `CassandraSchemaInitializer.createTables()`.
- [x] Insert schreibt 4+1 Zeilen — `addInsertToBatch(...)` fuegt 4 Entity-Rows + 1 Collection-Row.
- [x] Alle 16 Query-Patterns liefern korrekte Ergebnisse — `resolvePattern`/`queryStatements` + 22 Tests in `QuadStoreIntegrationTest`.
- [x] Kein `ALLOW FILTERING` — alle `PreparedStatement`s nutzen Partition-Key-Prefix.
- [x] Batch-Insert funktioniert — `insertBatch` mit `BatchType.LOGGED`, chunked.
- [x] Collection-Delete — `deleteCollection` loescht Entity-Partitionen und Collection-Partition.
- [x] Literal mit Datentyp/Sprache — `otype`, `dtype`, `lang` in beiden Tabellen.
- [x] Keyspace-Autocreate — `CREATE KEYSPACE IF NOT EXISTS`.
- [x] `@Service`-Erkennung ueber Package-Scanning — `CassandraQuadStore` ist `@Service`.
- [x] Konfiguration `spring.cassandra.*` + `graphmesh.cassandra.keyspace` — siehe `application.yml`.
- [x] Synchron — keine `suspend`-Methoden.
- [x] Tests mit docker-compose — `@SpringBootTest` gegen lokale Cassandra.
- [x] Bestehende Funktionalitaet unberuehrt.

## Offene Punkte

- Keine.
