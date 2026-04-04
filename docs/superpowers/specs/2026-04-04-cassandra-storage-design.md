# Feature 02: Cassandra Storage — Design Spec

Vereinfachte Implementierung: Direkte Spring Boot Cassandra Integration mit CqlTemplate, Entity-Centric 2-Table Design, alle 16 Query-Patterns.

## Entscheidungen

| Entscheidung | Wahl | Begruendung |
|---|---|---|
| Modul-Struktur | Spring Modulith Package (kein Submodul) | Konsistent mit Kafka, Single-Module-Setup |
| Datenzugriff | `CqlTemplate` direkt mit Prepared Statements | 16 Query-Patterns brauchen praezise CQL, kein Repository-Pattern passend |
| API-Stil | `@Service` direkt, kein Interface | YAGNI — nur eine Implementation, konsistent mit Kafka |
| Async | Synchron (keine Coroutines) | Konsistent mit Kafka-Entscheidung, CqlTemplate ist synchron |
| Infrastruktur | docker-compose (Cassandra hinzufuegen) | Konsistent mit Kafka, Tests gegen laufende Instanzen |
| Tests | Integration gegen docker-compose | Kein Testcontainers, gleicher Ansatz wie Kafka |
| Scope | Alle 16 Query-Patterns | Kern des Entity-Centric Designs, Query-Logik nicht komplex |

## Entity-Centric 2-Table Design

Jeder Quad `(D, S, P, O)` erzeugt 5 Rows:
- 4 Rows in `quads_by_entity` (eine pro Entity-Rolle: S, P, O, G)
- 1 Row in `quads_by_collection` (Manifest fuer Collection-Operationen)

### Tabellen

```sql
CREATE TABLE IF NOT EXISTS quads_by_entity (
    collection text,
    entity     text,
    role       text,       -- 'S', 'P', 'O', 'G'
    p          text,
    otype      text,       -- 'U' (URI), 'L' (literal), 'T' (quoted triple)
    s          text,
    o          text,
    d          text,
    dtype      text,
    lang       text,
    PRIMARY KEY ((collection, entity), role, p, otype, s, o, d, dtype, lang)
);

CREATE TABLE IF NOT EXISTS quads_by_collection (
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

## Infrastruktur: docker-compose.yaml

Cassandra zur bestehenden Datei hinzufuegen:

```yaml
  cassandra:
    image: cassandra:5.0
    hostname: cassandra
    ports:
      - "9042:9042"
    environment:
      CASSANDRA_CLUSTER_NAME: graphmesh
      CASSANDRA_DC: datacenter1
      HEAP_NEWSIZE: 128M
      MAX_HEAP_SIZE: 512M
```

## Konfiguration (application.yml)

Standard Spring Data Cassandra Properties:

```yaml
spring:
  cassandra:
    contact-points: ${CASSANDRA_CONTACT_POINTS:localhost}
    port: ${CASSANDRA_PORT:9042}
    local-datacenter: datacenter1
    keyspace-name: graphmesh
    schema-action: create-if-not-exists
```

Keine eigenen `GraphMeshCassandraProperties` — alles unter `spring.cassandra.*`.

## Schema-Initialisierung

`CassandraSchemaInitializer` als `@Configuration` Bean:
- Erstellt Keyspace `graphmesh` via CQL (`CREATE KEYSPACE IF NOT EXISTS`)
- Erstellt beide Tabellen via CQL (`CREATE TABLE IF NOT EXISTS`)
- Laeuft beim Application-Start via `@PostConstruct` oder `@EventListener(ApplicationReadyEvent)`
- Nutzt `CqlSession` direkt fuer DDL-Operationen

## Datenmodell

```kotlin
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
    URI("U"), LITERAL("L"), QUOTED_TRIPLE("T")
}

data class QuadQuery(
    val subject: String? = null,
    val predicate: String? = null,
    val objectValue: String? = null,
    val dataset: String? = null,
)
```

Reine Kotlin Data Classes — kein `@Table`, kein Spring Data Mapping.

## QuadStoreService

```kotlin
@Service
class QuadStoreService(private val cql: CqlTemplate) {

    // Prepared Statements werden beim Start vorbereitet
    // Ein Statement pro Query-Pattern (16 Stueck) + Insert/Delete Statements

    fun insert(collection: String, quad: StoredQuad)
    // BATCH: 4 Rows quads_by_entity (S, P, O, G) + 1 Row quads_by_collection

    fun insertBatch(collection: String, quads: List<StoredQuad>)
    // Alle Quads in einem BATCH

    fun delete(collection: String, quad: StoredQuad)
    // BATCH: 4+1 Rows loeschen (inverse von insert)

    fun deleteCollection(collection: String)
    // 1. quads_by_collection lesen (alle Quads der Collection)
    // 2. Unique Entities extrahieren (s, p, o, d Werte)
    // 3. quads_by_entity Partitionen loeschen
    // 4. quads_by_collection Partition loeschen

    fun query(collection: String, query: QuadQuery): List<StoredQuad>
    // Bestimmt optimale Strategie basierend auf gesetzten Feldern
}
```

## Query-Strategie

16 Patterns basierend auf `(S?, P?, O?, D?)`:

### Routing-Logik

Wenn mindestens ein Feld bekannt ist, wird `quads_by_entity` verwendet:
- S bekannt → `entity=S, role='S'` + optionale Filter auf P, O, D
- P bekannt (S unbekannt) → `entity=P, role='P'` + optionale Filter
- O bekannt (S, P unbekannt) → `entity=O, role='O'` + optionale Filter
- D bekannt (S, P, O unbekannt) → `entity=D, role='G'` + optionale Filter

**Priorisierung** wenn mehrere Felder bekannt: S > O > P > D (basierend auf erwarteter Selektivitaet)

### Pattern-Tabelle

| # | S | P | O | D | Entity | Role | Filter |
|---|---|---|---|---|--------|------|--------|
| 1 | x | x | x | x | S | S | p, o, d |
| 2 | x | x | x | ? | S | S | p, o |
| 3 | x | x | ? | x | S | S | p, d |
| 4 | x | x | ? | ? | S | S | p |
| 5 | x | ? | x | x | S | S | o, d |
| 6 | x | ? | x | ? | S | S | o |
| 7 | x | ? | ? | x | S | S | d |
| 8 | x | ? | ? | ? | S | S | — |
| 9 | ? | x | x | x | O | O | p, d |
| 10 | ? | x | x | ? | O | O | p |
| 11 | ? | x | ? | x | P | P | d |
| 12 | ? | x | ? | ? | P | P | — |
| 13 | ? | ? | x | x | O | O | d |
| 14 | ? | ? | x | ? | O | O | — |
| 15 | ? | ? | ? | x | D | G | — |
| 16 | ? | ? | ? | ? | — | — | quads_by_collection full scan |

### Prepared Statements

Alle 16 Query-Patterns + insert/delete als `PreparedStatement` beim Service-Start vorbereitet. Kein dynamisches CQL-Building zur Laufzeit.

**Kein `ALLOW FILTERING`** — alle Queries sind Single-Partition-Reads mit optionalem In-Memory-Filter auf Clustering-Columns.

## Dateistruktur

```
src/main/kotlin/.../storage/
    StoredQuad.kt                  # StoredQuad + ObjectType + QuadQuery
    QuadStoreService.kt            # @Service, insert/delete/query mit CqlTemplate
    CassandraSchemaInitializer.kt  # @Configuration, Keyspace + Tabellen erstellen

src/test/kotlin/.../storage/
    StoredQuadTest.kt              # Unit-Test fuer Data Classes
    QuadStoreIntegrationTest.kt    # Insert, delete, alle 16 Query-Patterns
```

3 Produktionsdateien + 2 Testdateien. Dazu docker-compose.yaml und application.yml Updates.

## Testing

| Test | Typ | Was wird getestet |
|---|---|---|
| `StoredQuadTest` | Unit | Data Class Konstruktion, ObjectType enum codes |
| `QuadStoreIntegrationTest` | Integration (docker-compose) | Insert erzeugt 4+1 Rows, delete entfernt 4+1 Rows, deleteCollection, insertBatch, alle 16 Query-Patterns mit konkreten Testdaten |

Tests setzen voraus, dass `docker compose up` laeuft. `application-test.yml` zeigt auf `localhost:9042`.

## Scope-Ausschluesse

- **Reactive/Coroutines**: Synchroner CqlTemplate reicht, Reactive spaeter bei Bedarf
- **Connection Pooling Config**: Spring Boot Defaults ausreichend fuer Start
- **TTL/Compaction Tuning**: Produktions-Optimierung, nicht in Scope
- **Pagination**: Einfache `List<StoredQuad>` Rueckgabe, Paging spaeter bei Bedarf
