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
2. **QuadStore Interface** -- Typsichere API fuer CRUD-Operationen auf RDF-Quads
3. **Alle 16 Query-Patterns** -- Jede Kombination aus D/S/P/O wird ueber Single-Partition-Reads bedient
4. **Connection Pooling & Keyspace Management** -- Konfigurierbare Verbindungspools und automatische Keyspace-Erstellung
5. **Spring Boot Auto-Configuration** -- Integration mit Spring Data Cassandra

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

### Core Interfaces

```kotlin
package com.graphmesh.storage.cassandra

/**
 * Zentrale Schnittstelle fuer RDF-Quad-Operationen.
 */
interface QuadStore {
    /**
     * Schreibt ein Quad (4 Zeilen in quads_by_entity + 1 in quads_by_collection).
     */
    suspend fun insert(collection: String, quad: StoredQuad)

    /**
     * Schreibt mehrere Quads als Batch.
     */
    suspend fun insertBatch(collection: String, quads: List<StoredQuad>)

    /**
     * Loescht ein einzelnes Quad aus allen Tabellen.
     */
    suspend fun delete(collection: String, quad: StoredQuad)

    /**
     * Loescht alle Quads einer Collection.
     */
    suspend fun deleteCollection(collection: String)

    /**
     * Fuehrt eine Quad-Query aus.
     */
    suspend fun query(collection: String, query: QuadQuery): List<StoredQuad>
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
package com.graphmesh.storage.cassandra

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

### CassandraClient

```kotlin
package com.graphmesh.storage.cassandra

import com.datastax.oss.driver.api.core.CqlSession

/**
 * Low-Level Cassandra-Client mit Connection Pooling und Keyspace Management.
 */
interface CassandraClient {
    val session: CqlSession

    fun ensureKeyspace(keyspace: String, replicationFactor: Int = 1)
    fun ensureTables(keyspace: String)
    fun close()
}
```

### Spring Boot Auto-Configuration

```kotlin
package com.graphmesh.storage.cassandra.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "graphmesh.storage.cassandra")
data class GraphMeshCassandraProperties(
    val contactPoints: List<String> = listOf("localhost"),
    val port: Int = 9042,
    val keyspace: String = "graphmesh",
    val datacenter: String = "datacenter1",
    val replicationFactor: Int = 1,
    val username: String? = null,
    val password: String? = null,
    val pool: PoolProperties = PoolProperties()
)

data class PoolProperties(
    val localSize: Int = 2,
    val remoteSize: Int = 1
)
```

### application.yml Beispiel

```yaml
graphmesh:
  storage:
    cassandra:
      contact-points:
        - cassandra-node1
        - cassandra-node2
      port: 9042
      keyspace: graphmesh
      datacenter: datacenter1
      replication-factor: 3
      username: graphmesh
      password: secret
      pool:
        local-size: 4
        remote-size: 1
```

## Betroffene Dateien

### Backend

| Datei                                                                                                                    | Aenderung                             |
|--------------------------------------------------------------------------------------------------------------------------|---------------------------------------|
| `storage-cassandra/src/main/kotlin/com/graphmesh/storage/cassandra/QuadStore.kt`                                         | NEU - QuadStore-Interface             |
| `storage-cassandra/src/main/kotlin/com/graphmesh/storage/cassandra/StoredQuad.kt`                                        | NEU - Quad-Datenmodell                |
| `storage-cassandra/src/main/kotlin/com/graphmesh/storage/cassandra/QuadQuery.kt`                                         | NEU - Query-DSL                       |
| `storage-cassandra/src/main/kotlin/com/graphmesh/storage/cassandra/CassandraClient.kt`                                   | NEU - Client-Interface                |
| `storage-cassandra/src/main/kotlin/com/graphmesh/storage/cassandra/impl/EntityCentricQuadStore.kt`                       | NEU - QuadStore-Implementierung       |
| `storage-cassandra/src/main/kotlin/com/graphmesh/storage/cassandra/impl/DefaultCassandraClient.kt`                       | NEU - Client-Implementierung          |
| `storage-cassandra/src/main/kotlin/com/graphmesh/storage/cassandra/autoconfigure/GraphMeshCassandraAutoConfiguration.kt` | NEU - Auto-Configuration              |
| `storage-cassandra/src/main/kotlin/com/graphmesh/storage/cassandra/autoconfigure/GraphMeshCassandraProperties.kt`        | NEU - Properties                      |
| `storage-cassandra/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`  | NEU - Auto-Configuration-Registration |
| `storage-cassandra/build.gradle.kts`                                                                                     | NEU - Gradle-Modul                    |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                                       | Aenderung                                  |
|-------------------------------------------------------------------------------------------------------------|--------------------------------------------|
| `storage-cassandra/src/test/kotlin/com/graphmesh/storage/cassandra/EntityCentricQuadStoreTest.kt`           | NEU - QuadStore-Unit-Tests                 |
| `storage-cassandra/src/test/kotlin/com/graphmesh/storage/cassandra/QuadQueryTest.kt`                        | NEU - Query-Strategy-Tests                 |
| `storage-cassandra/src/test/kotlin/com/graphmesh/storage/cassandra/integration/CassandraIntegrationTest.kt` | NEU - Integrationstests mit Testcontainers |
| `storage-cassandra/src/test/kotlin/com/graphmesh/storage/cassandra/integration/QueryPatternTest.kt`         | NEU - Alle 16 Query-Patterns testen        |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                            |
|-------------------|-------------|--------------------------------------------------|
| Spring Boot (JVM) | Ja          | DataStax Java Driver bietet volle Unterstuetzung |
| KMP Library       | Nein        | DataStax Driver ist JVM-only                     |
| Ktor/Wasm         | Nein        | Kein Cassandra-Driver fuer Wasm/JS               |

## Akzeptanzkriterien

- [ ] `quads_by_entity` und `quads_by_collection` Tabellen werden automatisch erstellt
- [ ] Insert schreibt korrekt 4 Zeilen in `quads_by_entity` + 1 Zeile in `quads_by_collection`
- [ ] Alle 16 Query-Patterns liefern korrekte Ergebnisse (verifiziert durch Integrationstests)
- [ ] Kein Query verwendet `ALLOW FILTERING`
- [ ] Batch-Insert fuer mehrere Quads funktioniert atomar
- [ ] Collection-Delete entfernt alle zugehoerigen Daten aus beiden Tabellen
- [ ] Literal-Objekte mit Datentyp und Sprach-Tag werden korrekt gespeichert und abgefragt
- [ ] Connection Pooling ist konfigurierbar ueber `application.yml`
- [ ] Keyspace wird automatisch erstellt falls nicht vorhanden
- [ ] Spring Boot Auto-Configuration funktioniert ohne manuelle Bean-Definition
- [ ] Integrationstests mit Testcontainers (Cassandra) laufen erfolgreich
- [ ] Bestehende Funktionalitaet bleibt unberuehrt
