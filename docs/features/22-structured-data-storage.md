# Feature 22: Structured Data Storage

## Problem

Viele Enterprise-Datenquellen enthalten tabellarische Daten (Kundendaten, Transaktionslogs, Inventarlisten), die neben
dem Knowledge Graph in einem strukturierten Format gespeichert werden muessen. Der bestehende QuadStore (Feature 02) ist
auf RDF-Triples optimiert, nicht auf tabellarische Daten mit dynamischen Schemata. Es fehlt eine flexible
Row-Store-Schicht, die dynamische Schema-Verwaltung, CRUD-Operationen und effiziente Abfragen auf tabellarischen Daten
unterstuetzt.

## Ziel

Implementierung eines Cassandra-basierten Row Stores fuer tabellarische Daten mit dynamischer Schema-Verwaltung,
CRUD-Operationen und einer Query-API mit Filter-, Sort- und Paginierungs-Unterstuetzung.

1. **Dynamische Schema-Verwaltung** -- Schemata als Config-Items ueber ConfigService (Feature 06) verwaltet
2. **Unified Row Table** -- Einzelne Cassandra-Tabelle fuer alle Schemata statt pro-Schema-Tabellen
3. **Index-basierte Abfragen** -- Zeilen werden pro indiziertem Feld dupliziert fuer effiziente Lookups
4. **CRUD-Operationen** -- Erstellen, Lesen, Aktualisieren (Append-Only) und Loeschen von Zeilen
5. **Query-API** -- Filterung, Sortierung und Paginierung ueber strukturierte Abfragen
6. **Schema-Descriptor** -- Spalten-Metadaten mit Name, Typ, Nullable und Beschreibung

## Voraussetzungen

| Abhaengigkeit                                                    | Status     | Blocker? |
|------------------------------------------------------------------|------------|----------|
| Feature 02: Cassandra Storage Layer (CassandraClient)            | Geplant    | Ja       |
| Feature 06: Configuration Service (ConfigService, ConfigHandler) | Geplant    | Ja       |
| Spring Boot 3.x                                                  | Verfuegbar | Nein     |

## Architektur

### Datenmodell

```kotlin
package com.graphmesh.structured

import kotlinx.serialization.Serializable

/**
 * Spalten-Beschreibung mit Typ-Information und Metadaten.
 */
@Serializable
data class ColumnDescriptor(
    val name: String,
    val type: ColumnType,
    val nullable: Boolean = true,
    val description: String? = null,
    val primaryKey: Boolean = false,
    val indexed: Boolean = false,
    val enumValues: List<String>? = null
)

/**
 * Unterstuetzte Spaltentypen mit Cassandra-Mapping.
 */
enum class ColumnType(val cassandraType: String) {
    STRING("text"),
    INTEGER("int"),
    LONG("bigint"),
    FLOAT("float"),
    DOUBLE("double"),
    BOOLEAN("boolean"),
    TIMESTAMP("timestamp");

    companion object {
        fun fromString(value: String): ColumnType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unbekannter Spaltentyp: $value")
    }
}

/**
 * Schema-Definition fuer eine strukturierte Datentabelle.
 * Wird als Config-Item vom Typ "schema" gespeichert.
 */
@Serializable
data class TableSchema(
    val name: String,
    val description: String? = null,
    val version: String = "1.0.0",
    val columns: List<ColumnDescriptor>,
    val indexes: List<IndexDefinition> = emptyList()
) {
    /**
     * Gibt die Primary-Key-Spalten zurueck.
     */
    val primaryKeyColumns: List<ColumnDescriptor>
        get() = columns.filter { it.primaryKey }

    /**
     * Gibt die indizierten Spalten zurueck (ohne Primary Key).
     */
    val indexedColumns: List<ColumnDescriptor>
        get() = columns.filter { it.indexed && !it.primaryKey }

    /**
     * Alle Index-Namen (Primary Key + indizierte Felder + explizite Indexes).
     */
    val allIndexNames: List<String>
        get() {
            val names = mutableListOf<String>()
            if (primaryKeyColumns.isNotEmpty()) {
                names.add(primaryKeyColumns.joinToString(",") { it.name })
            }
            names.addAll(indexedColumns.map { it.name })
            names.addAll(indexes.map { it.fields.sorted().joinToString(",") })
            return names.distinct()
        }
}

/**
 * Zusammengesetzter Index ueber mehrere Spalten.
 */
@Serializable
data class IndexDefinition(
    val fields: List<String>
)

/**
 * Eine Datenzeile mit Werten als Key-Value-Map.
 * Alle Werte werden als Strings gespeichert; Typkonvertierung erfolgt
 * auf Applikationsebene anhand des Schemas.
 */
data class DataRow(
    val collection: String,
    val schemaName: String,
    val values: Map<String, String>,
    val source: String? = null
)

/**
 * Strukturierte Abfrage gegen den Row Store.
 */
data class StructuredQuery(
    val collection: String,
    val schemaName: String,
    val indexName: String,
    val indexValue: List<String>,
    val limit: Int = 100,
    val offset: Int = 0
)

/**
 * Ergebnis einer strukturierten Abfrage.
 */
data class QueryResult(
    val rows: List<DataRow>,
    val totalCount: Int,
    val hasMore: Boolean
)
```

### Cassandra-Tabellenstruktur

```kotlin
package com.graphmesh.structured

/**
 * Cassandra-Schema fuer den Unified Row Store.
 *
 * Alle Zeilen werden in einer einzigen Tabelle gespeichert,
 * unabhaengig vom Schema. Zeilen werden pro indiziertem Feld
 * dupliziert fuer effiziente Index-Lookups.
 *
 * CREATE TABLE rows (
 *     collection text,
 *     schema_name text,
 *     index_name text,
 *     index_value frozen<list<text>>,
 *     data map<text, text>,
 *     source text,
 *     PRIMARY KEY ((collection, schema_name, index_name), index_value)
 * );
 *
 * CREATE TABLE row_partitions (
 *     collection text,
 *     schema_name text,
 *     index_name text,
 *     PRIMARY KEY ((collection), schema_name, index_name)
 * );
 */
object RowTableSchema {
    const val ROWS_TABLE = "rows"
    const val PARTITIONS_TABLE = "row_partitions"

    val CREATE_ROWS_TABLE = """
        CREATE TABLE IF NOT EXISTS $ROWS_TABLE (
            collection text,
            schema_name text,
            index_name text,
            index_value frozen<list<text>>,
            data map<text, text>,
            source text,
            PRIMARY KEY ((collection, schema_name, index_name), index_value)
        )
    """.trimIndent()

    val CREATE_PARTITIONS_TABLE = """
        CREATE TABLE IF NOT EXISTS $PARTITIONS_TABLE (
            collection text,
            schema_name text,
            index_name text,
            PRIMARY KEY ((collection), schema_name, index_name)
        )
    """.trimIndent()
}
```

### SchemaService

```kotlin
package com.graphmesh.structured

import com.graphmesh.config.ConfigService
import com.graphmesh.config.ConfigHandler
import com.graphmesh.config.ConfigItem

/**
 * Verwaltet Tabellen-Schemata als Config-Items.
 * Reagiert auf Schema-Aenderungen und aktualisiert den Cache.
 */
interface SchemaService {

    /**
     * Speichert oder aktualisiert ein Schema.
     */
    suspend fun saveSchema(schema: TableSchema)

    /**
     * Laedt ein Schema anhand seines Namens.
     */
    suspend fun getSchema(name: String): TableSchema?

    /**
     * Listet alle verfuegbaren Schema-Namen.
     */
    suspend fun listSchemas(): List<String>

    /**
     * Loescht ein Schema.
     */
    suspend fun deleteSchema(name: String)
}

/**
 * ConfigHandler fuer Schema-Items.
 */
class SchemaConfigHandler(
    private val schemaCache: MutableMap<String, TableSchema>
) : ConfigHandler {

    override val configType: String = "schema"

    override fun onConfigUpdated(item: ConfigItem) {
        val schema = SchemaJsonParser.parse(item.value)
        schemaCache[item.key] = schema
    }

    override fun onConfigDeleted(key: String) {
        schemaCache.remove(key)
    }
}
```

### RowStore

```kotlin
package com.graphmesh.structured

/**
 * Persistenz-Schicht fuer tabellarische Daten.
 * Nutzt Cassandra mit der Unified-Row-Table-Architektur.
 */
interface RowStore {

    /**
     * Speichert eine Zeile. Die Zeile wird pro indiziertem Feld
     * dupliziert (Write-Amplification).
     */
    suspend fun insert(row: DataRow)

    /**
     * Speichert mehrere Zeilen als Batch.
     */
    suspend fun insertBatch(rows: List<DataRow>)

    /**
     * Fuehrt eine strukturierte Abfrage aus.
     */
    suspend fun query(query: StructuredQuery): QueryResult

    /**
     * Loescht alle Zeilen einer Collection.
     */
    suspend fun deleteCollection(collection: String)

    /**
     * Loescht alle Zeilen einer Collection und eines Schemas.
     */
    suspend fun deleteBySchema(collection: String, schemaName: String)
}
```

### StructuredDataStore (Fassade)

```kotlin
package com.graphmesh.structured

/**
 * Hauptservice fuer strukturierte Daten.
 * Koordiniert SchemaService und RowStore.
 */
interface StructuredDataStore {

    /**
     * Speichert eine Zeile mit automatischer Schema-Validierung.
     */
    suspend fun store(row: DataRow): StoreResult

    /**
     * Speichert mehrere Zeilen als Batch.
     */
    suspend fun storeBatch(rows: List<DataRow>): List<StoreResult>

    /**
     * Fuehrt eine Abfrage aus.
     */
    suspend fun query(query: StructuredQuery): QueryResult

    /**
     * Gibt den SchemaService zurueck.
     */
    val schemaService: SchemaService

    /**
     * Gibt den RowStore zurueck.
     */
    val rowStore: RowStore
}

data class StoreResult(
    val success: Boolean,
    val rowsWritten: Int = 0,
    val error: String? = null
)
```

### Partition-Tracking

```kotlin
package com.graphmesh.structured

/**
 * Verwaltet die Partition-Tracking-Tabelle fuer effizientes Loeschen.
 *
 * Der PartitionTracker registriert (collection, schema_name, index_name)-Tupel
 * beim ersten Schreiben und haelt einen In-Memory-Cache.
 */
class PartitionTracker(
    private val cassandraClient: com.graphmesh.storage.cassandra.CassandraClient
) {
    private val registeredPartitions = mutableSetOf<Triple<String, String, String>>()

    /**
     * Registriert Partitionen fuer eine neue Zeile, falls noch nicht geschehen.
     */
    suspend fun ensureRegistered(
        collection: String,
        schemaName: String,
        indexNames: List<String>
    ) {
        val key = Triple(collection, schemaName, indexNames.joinToString(","))
        if (key !in registeredPartitions) {
            for (indexName in indexNames) {
                cassandraClient.execute(
                    "INSERT INTO ${RowTableSchema.PARTITIONS_TABLE} " +
                    "(collection, schema_name, index_name) VALUES (?, ?, ?)",
                    listOf(collection, schemaName, indexName)
                )
            }
            registeredPartitions.add(key)
        }
    }

    /**
     * Gibt alle Index-Namen fuer eine Collection und ein Schema zurueck.
     */
    suspend fun getPartitions(collection: String, schemaName: String? = null): List<String> {
        val query = if (schemaName != null) {
            "SELECT index_name FROM ${RowTableSchema.PARTITIONS_TABLE} " +
            "WHERE collection = ? AND schema_name = ?"
        } else {
            "SELECT schema_name, index_name FROM ${RowTableSchema.PARTITIONS_TABLE} " +
            "WHERE collection = ?"
        }
        val params = listOfNotNull(collection, schemaName)
        return cassandraClient.query(query, params).map { it["index_name"] as String }
    }

    /**
     * Invalidiert den Cache (z.B. nach Schema-Aenderung).
     */
    fun invalidateCache(collection: String, schemaName: String) {
        registeredPartitions.removeAll { (c, s, _) -> c == collection && s == schemaName }
    }
}
```

## Betroffene Dateien

### Backend

| Datei                                                                        | Aenderung                                                                 |
|------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| `structured/src/main/kotlin/com/graphmesh/structured/TableSchema.kt`         | NEU - Schema-Datenmodell (TableSchema, ColumnDescriptor, IndexDefinition) |
| `structured/src/main/kotlin/com/graphmesh/structured/ColumnType.kt`          | NEU - Spaltentyp-Enum mit Cassandra-Mapping                               |
| `structured/src/main/kotlin/com/graphmesh/structured/DataRow.kt`             | NEU - Datenzeile als Key-Value-Map                                        |
| `structured/src/main/kotlin/com/graphmesh/structured/StructuredQuery.kt`     | NEU - Abfrage-Datenklasse                                                 |
| `structured/src/main/kotlin/com/graphmesh/structured/RowStore.kt`            | NEU - Persistenz-Interface                                                |
| `structured/src/main/kotlin/com/graphmesh/structured/SchemaService.kt`       | NEU - Schema-Verwaltungs-Interface                                        |
| `structured/src/main/kotlin/com/graphmesh/structured/StructuredDataStore.kt` | NEU - Fassaden-Interface                                                  |
| `structured/src/main/kotlin/com/graphmesh/structured/RowTableSchema.kt`      | NEU - Cassandra-DDL fuer Unified Row Table                                |
| `structured/src/main/kotlin/com/graphmesh/structured/PartitionTracker.kt`    | NEU - Partition-Tracking fuer effizientes Loeschen                        |
| `structured/src/main/kotlin/com/graphmesh/structured/SchemaConfigHandler.kt` | NEU - ConfigHandler fuer Schema-Aenderungen                               |
| `structured/src/main/kotlin/com/graphmesh/structured/SchemaJsonParser.kt`    | NEU - JSON-Parsing von Schema-Definitionen                                |
| `structured/src/main/kotlin/com/graphmesh/structured/CassandraRowStore.kt`   | NEU - Cassandra-Implementierung des RowStore                              |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                            | Aenderung                                                 |
|----------------------------------------------------------------------------------|-----------------------------------------------------------|
| `structured/src/test/kotlin/com/graphmesh/structured/TableSchemaTest.kt`         | NEU - Schema-Validierung und Index-Berechnung             |
| `structured/src/test/kotlin/com/graphmesh/structured/CassandraRowStoreTest.kt`   | NEU - CRUD-Operationen, Write-Amplification, Batch-Insert |
| `structured/src/test/kotlin/com/graphmesh/structured/PartitionTrackerTest.kt`    | NEU - Partition-Registrierung und Loeschung               |
| `structured/src/test/kotlin/com/graphmesh/structured/SchemaConfigHandlerTest.kt` | NEU - Config-Event-Verarbeitung                           |
| `structured/src/test/kotlin/com/graphmesh/structured/StructuredQueryTest.kt`     | NEU - Abfrage-Ausfuehrung mit verschiedenen Indexes       |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                              |
|-------------------|-------------|------------------------------------|
| Spring Boot (JVM) | Ja          | CassandraClient, ConfigService     |
| KMP Library       | Nein        | Abhaengigkeit zu CassandraClient   |
| Ktor/Wasm         | Nein        | CassandraClient ist JVM-spezifisch |

## Akzeptanzkriterien

- [ ] Schemata werden als Config-Items vom Typ `schema` ueber ConfigService gespeichert
- [ ] ColumnDescriptor definiert Name, Typ, Nullable, Beschreibung, PrimaryKey und Indexed
- [ ] Unified Row Table (`rows`) speichert alle Zeilen unabhaengig vom Schema
- [ ] Zeilen werden pro indiziertem Feld dupliziert (Write-Amplification fuer Index-Lookups)
- [ ] Partition-Tracking-Tabelle (`row_partitions`) ermoeglicht effizientes Loeschen
- [ ] PartitionTracker registriert Partitionen beim ersten Schreiben und haelt In-Memory-Cache
- [ ] StructuredQuery unterstuetzt exakte Suche auf `(collection, schema_name, index_name, index_value)`
- [ ] Collection-weites Loeschen entfernt alle Zeilen und Partition-Eintraege
- [ ] Schema+Collection-Loeschen entfernt nur die betroffenen Partitionen
- [ ] Batch-Insert speichert mehrere Zeilen effizient
- [ ] SchemaConfigHandler reagiert auf Config-Aenderungen und invalidiert den Cache
- [ ] Typkonvertierung erfolgt auf Applikationsebene anhand des Schemas
- [ ] Append-Only-Modell: keine in-place Updates von Zeilen
