# Feature 22: Structured Data Storage — Design Spec

## Zusammenfassung

Cassandra-basierter Row Store fuer tabellarische Daten mit dynamischer Schema-Verwaltung via ConfigService, Write-Amplification fuer Index-Lookups und Partition-Tracking fuer effizientes Loeschen.

## Entscheidungen

| Entscheidung | Wahl | Begruendung |
|---|---|---|
| Schema-Persistenz | ConfigService (ConfigType.SCHEMA) | Bestehendes Pattern (OntologyStore) |
| Partition-Tracking | In CassandraRowStore integriert | Kein separater Tracker, kein In-Memory-Cache |
| Architektur | SchemaStore + CassandraRowStore + StructuredDataService | Klare Trennung, testbar |
| Interfaces | Keine (direkte Klassen) | YAGNI, nur eine Implementierung |
| Sync/Async | Synchron | Konsistent mit ConfigService, OntologyService |
| Serialisierung | Jackson | Projekt-Standard |
| Schema-Cache | Keiner | Schemata werden selten gelesen |

## Paket

`com.agentwork.graphmesh.structured`

## Datenmodell

```kotlin
enum class ColumnType(val cassandraType: String) {
    STRING("text"), INTEGER("int"), LONG("bigint"),
    FLOAT("float"), DOUBLE("double"),
    BOOLEAN("boolean"), TIMESTAMP("timestamp");

    companion object {
        fun fromString(value: String): ColumnType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unbekannter Spaltentyp: $value")
    }
}

data class ColumnDescriptor(
    val name: String,
    val type: ColumnType,
    val nullable: Boolean = true,
    val description: String? = null,
    val primaryKey: Boolean = false,
    val indexed: Boolean = false
)

data class IndexDefinition(val fields: List<String>)

data class TableSchema(
    val name: String,
    val description: String? = null,
    val version: String = "1.0.0",
    val columns: List<ColumnDescriptor>,
    val indexes: List<IndexDefinition> = emptyList()
) {
    val primaryKeyColumns: List<ColumnDescriptor>
        get() = columns.filter { it.primaryKey }

    val indexedColumns: List<ColumnDescriptor>
        get() = columns.filter { it.indexed && !it.primaryKey }

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

data class DataRow(
    val collection: String,
    val schemaName: String,
    val values: Map<String, String>,
    val source: String? = null
)

data class StructuredQuery(
    val collection: String,
    val schemaName: String,
    val indexName: String,
    val indexValue: List<String>,
    val limit: Int = 100
)

data class QueryResult(
    val rows: List<DataRow>,
    val totalCount: Int,
    val hasMore: Boolean
)

data class StoreResult(
    val success: Boolean,
    val rowsWritten: Int = 0,
    val error: String? = null
)
```

## ConfigType-Erweiterung

```kotlin
// In config/ConfigItem.kt
enum class ConfigType {
    ONTOLOGY, FLOW, TOOL, PARAMETER, COLLECTION_SETTINGS, LLM_SETTINGS, SCHEMA
}
```

## SchemaStore

```kotlin
@Component
class SchemaStore(
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper
) {
    fun save(schema: TableSchema): ConfigItem
    fun load(name: String): TableSchema?
    fun listNames(): List<String>
    fun delete(name: String)
}
```

- Serialisiert TableSchema zu JSON via Jackson
- Speichert als ConfigItem mit type=SCHEMA, key=schema.name
- Analog zu OntologyStore-Pattern

## Cassandra-Tabellen

```sql
CREATE TABLE IF NOT EXISTS rows (
    collection text,
    schema_name text,
    index_name text,
    index_value frozen<list<text>>,
    data map<text, text>,
    source text,
    PRIMARY KEY ((collection, schema_name, index_name), index_value)
);

CREATE TABLE IF NOT EXISTS row_partitions (
    collection text,
    schema_name text,
    index_name text,
    PRIMARY KEY ((collection), schema_name, index_name)
);
```

## CassandraRowSchemaInitializer

```kotlin
@Component
class CassandraRowSchemaInitializer(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) {
    @PostConstruct
    fun initializeSchema()  // CREATE TABLE IF NOT EXISTS rows + row_partitions
}
```

## CassandraRowStore

```kotlin
@Service
@DependsOn("cassandraRowSchemaInitializer")
class CassandraRowStore(
    private val session: CqlSession,
    private val schemaStore: SchemaStore,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) {
    // PreparedStatements initialisiert in @PostConstruct

    fun insert(row: DataRow)
    fun insertBatch(rows: List<DataRow>)
    fun query(query: StructuredQuery): QueryResult
    fun deleteByCollection(collection: String)
    fun deleteBySchema(collection: String, schemaName: String)
}
```

**insert() Flow:**
1. Schema laden via schemaStore.load(row.schemaName)
2. Fuer jeden Index in schema.allIndexNames:
   - index_value aus row.values extrahieren
   - INSERT INTO rows (collection, schema_name, index_name, index_value, data, source)
3. INSERT INTO row_partitions fuer jeden Index (idempotent)

**query() Flow:**
1. SELECT FROM rows WHERE collection=? AND schema_name=? AND index_name=? AND index_value=? LIMIT ?
2. Rows in DataRow konvertieren
3. QueryResult mit hasMore = rows.size == limit

**deleteByCollection() Flow:**
1. SELECT schema_name, index_name FROM row_partitions WHERE collection=?
2. Pro Partition: DELETE FROM rows WHERE collection=? AND schema_name=? AND index_name=?
3. DELETE FROM row_partitions WHERE collection=?

**deleteBySchema() Flow:**
1. SELECT index_name FROM row_partitions WHERE collection=? AND schema_name=?
2. Pro Index: DELETE FROM rows WHERE collection=? AND schema_name=? AND index_name=?
3. DELETE FROM row_partitions WHERE collection=? AND schema_name=?

## StructuredDataService

```kotlin
@Service
class StructuredDataService(
    private val schemaStore: SchemaStore,
    private val rowStore: CassandraRowStore
) {
    fun store(row: DataRow): StoreResult
    fun storeBatch(rows: List<DataRow>): List<StoreResult>
    fun query(query: StructuredQuery): QueryResult
    fun saveSchema(schema: TableSchema)
    fun getSchema(name: String): TableSchema?
    fun listSchemas(): List<String>
    fun deleteSchema(name: String)
}
```

**store() Validierung:**
- Schema existiert (sonst error)
- Alle nicht-nullable Columns haben Werte (sonst error)
- Mindestens eine PK-Column hat einen Wert (sonst error)

## Dateien

| Datei | Beschreibung |
|---|---|
| `structured/Models.kt` | ColumnType, ColumnDescriptor, IndexDefinition, TableSchema, DataRow, StructuredQuery, QueryResult, StoreResult |
| `structured/SchemaStore.kt` | Schema-Persistenz via ConfigService |
| `structured/CassandraRowSchemaInitializer.kt` | DDL fuer rows + row_partitions |
| `structured/CassandraRowStore.kt` | Insert mit Write-Amplification, Query, Delete mit Partition-Tracking |
| `structured/StructuredDataService.kt` | Fassade: Validierung + Orchestrierung |
| `config/ConfigItem.kt` | SCHEMA zu ConfigType hinzufuegen |

## Tests

| Testklasse | Fokus |
|---|---|
| `TableSchemaTest` | primaryKeyColumns, indexedColumns, allIndexNames, ColumnType.fromString |
| `SchemaStoreTest` | JSON Round-Trip, ConfigService CRUD (MockK) |
| `CassandraRowStoreTest` | Insert/Write-Amplification, Query, Delete, Batch (MockK fuer CqlSession) |
| `StructuredDataServiceTest` | Store-Validierung, Batch, Query-Delegation (MockK) |
