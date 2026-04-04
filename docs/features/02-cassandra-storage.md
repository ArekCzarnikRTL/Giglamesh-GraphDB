# Feature 02: Cassandra Storage Layer

## Problem

GraphMesh benoetigt einen hochperformanten Graph-Speicher fuer RDF-Quads, der alle 16 moeglichen Query-Patterns (
Kombinationen aus Dataset, Subject, Predicate, Object) effizient unterstuetzt. Traditionelle Multi-Table-Ansaetze (6+
Tabellen) fuehren zu hoher Write-Amplification und operativer Komplexitaet. Sekundaerindizes in Cassandra skalieren
schlecht bei hochkardinalem Daten und erzwingen `ALLOW FILTERING`.

## Ziel

Implementierung eines Entity-Centric Cassandra Storage Layers mit einem 2-Table-Design, das alle 16 Query-Patterns ueber
Single-Partition-Reads abdeckt und Label-Resolution als kostenlosen Nebeneffekt bietet.

1. **Entity-Centric 2-Table Design** -- `quads_by_entity` und `quads_by_collection` fuer optimale Query-Performance
2. **QuadStoreService** -- `@Service`-Klasse mit typsicherer API fuer CRUD-Operationen auf RDF-Quads
3. **Alle 16 Query-Patterns** -- Jede Kombination aus D/S/P/O wird ueber Single-Partition-Reads bedient
4. **Schema-Initialisierung** -- Automatische Keyspace- und Tabellen-Erstellung via `CassandraSchemaInitializer`
5. **Spring Modulith Integration** -- Package-basiertes Modul unter `com.agentwork.graphmesh.storage`

## Voraussetzungen

| Abhaengigkeit         | Status     | Blocker? |
|-----------------------|------------|----------|
| Apache Cassandra 4.x+ | Geplant    | Nein     |
| Spring Data Cassandra | Verfuegbar | Nein     |
| DataStax Java Driver  | Verfuegbar | Nein     |

## Architektur

### Entity-Centric Datenmodell

Jedes Quad `(D, S, P, O)` erzeugt 4 Zeilen in `quads_by_entity` (eine pro beteiligter Entitaet) und 1 Zeile in
`quads_by_collection`. Dadurch trifft jede Query mit mindestens einem bekannten Element einen Partition Key.

```sql
CREATE TABLE quads_by_entity (
    collection text,       -- Collection/Tenant-Scope
    entity     text,       -- Die Entitaet, um die es geht
    role       text,       -- 'S', 'P', 'O', 'G'
    p          text,       -- Praedikat des Quads
    otype      text,       -- 'U' (URI), 'L' (Literal), 'T' (Quoted Triple)
    s          text,       -- Subject des Quads
    o          text,       -- Object des Quads
    d          text,       -- Dataset/Graph des Quads
    dtype      text,       -- XSD-Datentyp (bei otype='L')
    lang       text,       -- Sprach-Tag (bei otype='L')
    PRIMARY KEY ((collection, entity), role, p, otype, s, o, d, dtype, lang)
);

CREATE TABLE quads_by_collection (
    collection text,
    d          text,
    s          text,
    p          text,
    o          text,
    otype      text,
    dtype      text,
    lang       text,
    PRIMARY KEY (collection, d, s, p, o, otype, dtype, lang)
);
```

### Query-Pattern-Abdeckung

| #  | Bekannt | Lookup-Entitaet                    | Effizienz               |
|----|---------|------------------------------------|-------------------------|
| 1  | D,S,P,O | entity=S, role='S', p=P            | Perfect Prefix          |
| 2  | D,S,P,? | entity=S, role='S', p=P + Filter D | Partition Scan + Filter |
| 3  | D,S,?,O | entity=S, role='S' + Filter D,O    | Partition Scan + Filter |
| 4  | D,?,P,O | entity=O, role='O', p=P + Filter D | Partition Scan + Filter |
| 5  | ?,S,P,O | entity=S, role='S', p=P + Filter O | Partition Scan + Filter |
| 6  | D,S,?,? | entity=S, role='S' + Filter D      | Partition Scan + Filter |
| 7  | D,?,P,? | entity=P, role='P' + Filter D      | Partition Scan + Filter |
| 8  | D,?,?,O | entity=O, role='O' + Filter D      | Partition Scan + Filter |
| 9  | ?,S,P,? | entity=S, role='S', p=P            | **Perfect Prefix**      |
| 10 | ?,S,?,O | entity=S, role='S' + Filter O      | Partition Scan + Filter |
| 11 | ?,?,P,O | entity=O, role='O', p=P            | **Perfect Prefix**      |
| 12 | D,?,?,? | entity=D, role='G'                 | **Perfect Prefix**      |
| 13 | ?,S,?,? | entity=S, role='S'                 | **Perfect Prefix**      |
| 14 | ?,?,P,? | entity=P, role='P'                 | **Perfect Prefix**      |
| 15 | ?,?,?,O | entity=O, role='O'                 | **Perfect Prefix**      |
| 16 | ?,?,?,? | quads_by_collection (Full Scan)    | Exploration Only        |

### QuadStoreService

```kotlin
package com.agentwork.graphmesh.storage

import org.springframework.stereotype.Service

/**
 * Zentrale Service-Klasse fuer RDF-Quad-Operationen.
 * Verwendet direkt CqlSession fuer alle Cassandra-Zugriffe.
 */
@Service
class QuadStoreService(private val session: CqlSession) {
    /**
     * Schreibt ein Quad (4 Zeilen in quads_by_entity + 1 in quads_by_collection).
     */
    fun insert(collection: String, quad: StoredQuad)

    /**
     * Schreibt mehrere Quads als Batch.
     */
    fun insertBatch(collection: String, quads: List<StoredQuad>)

    /**
     * Loescht ein einzelnes Quad aus allen Tabellen.
     */
    fun delete(collection: String, quad: StoredQuad)

    /**
     * Loescht alle Quads einer Collection.
     */
    fun deleteCollection(collection: String)

    /**
     * Fuehrt eine Quad-Query aus.
     */
    fun query(collection: String, query: QuadQuery): List<StoredQuad>
}

/**
 * Repraesentiert ein gespeichertes Quad.
 */
data class StoredQuad(
    val subject: String,
    val predicate: String,
    val objectValue: String,
    val dataset: String,
    val objectType: ObjectType = ObjectType.URI,
    val datatype: String = "",
    val language: String = ""
)

enum class ObjectType(val code: String) {
    URI("U"),
    LITERAL("L"),
    QUOTED_TRIPLE("T")
}
```

### QuadQuery DSL

```kotlin
package com.agentwork.graphmesh.storage

/**
 * Repraesentiert eine Query ueber Quads.
 * Jedes Feld kann gesetzt (= bekannt) oder null (= Wildcard) sein.
 */
data class QuadQuery(
    val dataset: String? = null,
    val subject: String? = null,
    val predicate: String? = null,
    val objectValue: String? = null,
    val objectType: ObjectType? = null,
    val limit: Int = 100
) {
    /**
     * Bestimmt die optimale Lookup-Strategie basierend auf den bekannten Feldern.
     */
    fun resolveStrategy(): QueryStrategy {
        val hasD = dataset != null
        val hasS = subject != null
        val hasP = predicate != null
        val hasO = objectValue != null

        return when {
            hasS -> QueryStrategy(entity = subject!!, role = "S", filterD = hasD, filterO = hasO, filterP = !hasP)
            hasO -> QueryStrategy(entity = objectValue!!, role = "O", filterD = hasD, filterS = false, filterP = !hasP)
            hasP -> QueryStrategy(entity = predicate!!, role = "P", filterD = hasD)
            hasD -> QueryStrategy(entity = dataset!!, role = "G")
            else -> QueryStrategy(useCollectionTable = true)
        }
    }
}

data class QueryStrategy(
    val entity: String = "",
    val role: String = "",
    val filterD: Boolean = false,
    val filterS: Boolean = false,
    val filterO: Boolean = false,
    val filterP: Boolean = false,
    val useCollectionTable: Boolean = false
)
```

### CassandraSchemaInitializer

```kotlin
package com.agentwork.graphmesh.storage

import com.datastax.oss.driver.api.core.CqlSession
import org.springframework.stereotype.Component

/**
 * Erstellt Keyspace und Tabellen beim Anwendungsstart.
 * Verwendet direkt CqlSession (kein Wrapper).
 */
@Component
class CassandraSchemaInitializer(
    private val session: CqlSession,
    private val keyspace: String
) {
    fun initialize() { /* ensureKeyspace + ensureTables */ }
}
```

### Konfiguration

Cassandra-Verbindung wird ueber die Standard-Spring-Properties (`spring.cassandra.*`) konfiguriert.
Fuer den Keyspace-Namen wird eine einzelne Custom-Property verwendet:

```yaml
spring:
  cassandra:
    contact-points: cassandra-node1,cassandra-node2
    port: 9042
    local-datacenter: datacenter1
    username: graphmesh
    password: secret

graphmesh:
  cassandra:
    keyspace: graphmesh
```

Kein separates `GraphMeshCassandraProperties`-Objekt noetig -- `CqlSession` wird automatisch durch
Spring Boot Auto-Configuration bereitgestellt, der Keyspace-Name per `@Value("${graphmesh.cassandra.keyspace}")` injiziert.

## Betroffene Dateien

### Backend (Spring Modulith Package: `com.agentwork.graphmesh.storage`)

| Datei                                                                                        | Aenderung                              |
|----------------------------------------------------------------------------------------------|----------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/storage/StoredQuad.kt`                              | NEU - Quad-Datenmodell + QuadQuery DSL |
| `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt`                        | NEU - `@Service` fuer CRUD-Operationen |
| `src/main/kotlin/com/agentwork/graphmesh/storage/CassandraSchemaInitializer.kt`              | NEU - Keyspace/Tabellen-Erstellung     |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                            | Aenderung                                    |
|--------------------------------------------------------------------------------------------------|----------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreServiceTest.kt`                        | NEU - QuadStoreService-Tests                 |
| `src/test/kotlin/com/agentwork/graphmesh/storage/QuadQueryTest.kt`                               | NEU - Query-Strategy-Tests + 16 Patterns     |

Tests verwenden docker-compose (`compose.yaml`) statt Testcontainers fuer die Cassandra-Instanz.

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                            |
|-------------------|-------------|--------------------------------------------------|
| Spring Boot (JVM) | Ja          | DataStax Java Driver bietet volle Unterstuetzung |
| KMP Library       | Nein        | DataStax Driver ist JVM-only                     |
| Ktor/Wasm         | Nein        | Kein Cassandra-Driver fuer Wasm/JS               |

## Akzeptanzkriterien

- [ ] `quads_by_entity` und `quads_by_collection` Tabellen werden automatisch via `CassandraSchemaInitializer` erstellt
- [ ] Insert schreibt korrekt 4 Zeilen in `quads_by_entity` + 1 Zeile in `quads_by_collection`
- [ ] Alle 16 Query-Patterns liefern korrekte Ergebnisse (verifiziert durch Tests)
- [ ] Kein Query verwendet `ALLOW FILTERING`
- [ ] Batch-Insert fuer mehrere Quads funktioniert atomar
- [ ] Collection-Delete entfernt alle zugehoerigen Daten aus beiden Tabellen
- [ ] Literal-Objekte mit Datentyp und Sprach-Tag werden korrekt gespeichert und abgefragt
- [ ] Keyspace wird automatisch erstellt falls nicht vorhanden
- [ ] `QuadStoreService` wird per Spring Modulith Package-Scanning als `@Service` erkannt
- [ ] Konfiguration erfolgt ueber `spring.cassandra.*` + `graphmesh.cassandra.keyspace`
- [ ] Alle Methoden sind synchron (kein `suspend fun` / keine Coroutines)
- [ ] Tests laufen mit docker-compose-basierter Cassandra-Instanz
- [ ] Bestehende Funktionalitaet bleibt unberuehrt
