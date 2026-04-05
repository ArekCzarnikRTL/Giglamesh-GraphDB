# Feature 22: Structured Data Storage — Done

## Zusammenfassung

Cassandra-basierter Row Store fuer tabellarische Daten implementiert. Dynamische Schema-Verwaltung via ConfigService (ConfigType.SCHEMA), Unified Row Table mit Write-Amplification fuer Index-Lookups, Partition-Tracking fuer effizientes Loeschen.

## Implementierte Dateien

| Datei | Beschreibung |
|---|---|
| `config/ConfigItem.kt` | SCHEMA zu ConfigType hinzugefuegt |
| `structured/Models.kt` | ColumnType, ColumnDescriptor, IndexDefinition, TableSchema, DataRow, StructuredQuery, QueryResult, StoreResult |
| `structured/SchemaStore.kt` | Schema-Persistenz via ConfigService (JSON in ConfigItem) |
| `structured/CassandraRowSchemaInitializer.kt` | DDL fuer rows + row_partitions Tabellen |
| `structured/CassandraRowStore.kt` | Insert mit Write-Amplification, Query, Delete mit Partition-Tracking |
| `structured/StructuredDataService.kt` | Fassade: Schema-CRUD, Row-Validierung, Orchestrierung |

## Abweichungen vom Feature-Dokument

1. **Paket:** `com.agentwork.graphmesh.structured` statt `com.graphmesh.structured`
2. **Serialisierung:** Jackson statt kotlinx.serialization
3. **Sync statt Async:** Keine suspend-Functions — konsistent mit ConfigService
4. **Keine Interfaces:** Direkte Klassen (SchemaStore, CassandraRowStore, StructuredDataService) statt Interfaces — YAGNI
5. **ConfigHandler:** Kein ConfigHandler-Interface — bei Bedarf spaeter via Spring @EventListener (wie OntologyCache)
6. **PartitionTracker:** Kein separater Tracker — Partition-Tracking direkt in CassandraRowStore integriert, kein In-Memory-Cache
7. **CassandraClient:** CqlSession direkt statt CassandraClient-Abstraction
8. **enumValues in ColumnDescriptor:** Entfernt (YAGNI)
9. **offset in StructuredQuery:** Entfernt (Cassandra unterstuetzt kein natives Offset-Paging)
10. **SchemaJsonParser:** Nicht noetig — Jackson ObjectMapper uebernimmt direkt

## Offene Punkte / Technische Schulden

- Kein Schema-Cache (bei Bedarf wie OntologyCache mit @EventListener hinzufuegbar)
- Delete-Operationen nutzen String-Interpolation fuer CQL (kein Prepared Statement) — akzeptabel da collection/schemaName intern kontrolliert
- Keine Paginierung mit Cursor/Token — nur limit-basiert
- Keine Typ-Validierung der Werte gegen ColumnType (bewusst: Typkonvertierung auf Applikationsebene)
