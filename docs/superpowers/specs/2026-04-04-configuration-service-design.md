# Feature 06: Configuration Service — Design Spec

## Entscheidung

Zentraler Config-Service mit Cassandra-Persistenz, Spring ApplicationEvent fuer lokalen Dispatch, und Kafka fuer Cross-Instance-Propagation. Kein eigenes Handler-Framework — Spring's `@EventListener` wird genutzt. Loki-Logging ausgeschlossen (YAGNI). Kein Gradle-Submodul. Blocking API (kein suspend).

## Datenmodell

```kotlin
package com.agentwork.graphmesh.config

enum class ConfigType {
    ONTOLOGY, FLOW, TOOL, PARAMETER, COLLECTION_SETTINGS, LLM_SETTINGS
}

enum class ConfigAction { CREATED, UPDATED, DELETED }

data class ConfigItem(
    val id: String,
    val type: ConfigType,
    val key: String,
    val value: String,              // JSON-serialisierter Wert
    val version: Int = 1,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val createdBy: String = "system",
    val description: String = ""
)

// Spring ApplicationEvent fuer lokalen Dispatch
data class ConfigChangedEvent(
    val configId: String,
    val configType: ConfigType,
    val key: String,
    val action: ConfigAction,
    val version: Int
)
```

## ConfigStore Interface

```kotlin
interface ConfigStore {
    fun save(item: ConfigItem): ConfigItem
    fun findById(id: String): ConfigItem?
    fun findByType(type: ConfigType): List<ConfigItem>
    fun findByTypeAndKey(type: ConfigType, key: String): ConfigItem?
    fun delete(id: String)
    fun history(id: String, limit: Int = 10): List<ConfigItem>
}
```

Implementierung: `CassandraConfigStore` mit direkter `CqlSession`-Nutzung, selbes Pattern wie `CassandraQuadStore`.

## Cassandra Schema

```sql
CREATE TABLE IF NOT EXISTS config_items (
    id          text,
    type        text,
    key         text,
    value       text,
    version     int,
    created_at  timestamp,
    updated_at  timestamp,
    created_by  text,
    description text,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS config_by_type (
    type        text,
    key         text,
    id          text,
    value       text,
    version     int,
    updated_at  timestamp,
    PRIMARY KEY (type, key)
);

CREATE TABLE IF NOT EXISTS config_history (
    id          text,
    version     int,
    value       text,
    updated_at  timestamp,
    updated_by  text,
    PRIMARY KEY (id, version)
) WITH CLUSTERING ORDER BY (version DESC);
```

Schema-Erstellung via `ConfigSchemaInitializer` (analog zu `CassandraSchemaInitializer`).

## ConfigService

```kotlin
@Service
class ConfigService(
    private val store: ConfigStore,
    private val eventPublisher: ApplicationEventPublisher,
    private val configChangeProducer: ConfigChangeProducer
) {
    fun save(item: ConfigItem): ConfigItem {
        val existing = store.findById(item.id)
        val action = if (existing == null) ConfigAction.CREATED else ConfigAction.UPDATED
        val version = (existing?.version ?: 0) + 1
        val saved = store.save(item.copy(version = version, updatedAt = Instant.now()))

        val event = ConfigChangedEvent(saved.id, saved.type, saved.key, action, saved.version)
        eventPublisher.publishEvent(event)
        configChangeProducer.send(event)
        return saved
    }

    fun findById(id: String): ConfigItem? = store.findById(id)
    fun findByType(type: ConfigType): List<ConfigItem> = store.findByType(type)
    fun findByTypeAndKey(type: ConfigType, key: String): ConfigItem? = store.findByTypeAndKey(type, key)
    fun history(id: String, limit: Int = 10): List<ConfigItem> = store.history(id, limit)

    fun delete(id: String) {
        val item = store.findById(id) ?: return
        store.delete(id)
        val event = ConfigChangedEvent(item.id, item.type, item.key, ConfigAction.DELETED, item.version)
        eventPublisher.publishEvent(event)
        configChangeProducer.send(event)
    }
}
```

## Kafka-Propagation

### Producer

`ConfigChangeProducer` sendet Avro-serialisiertes Event an Topic `graphmesh.config.changed` mit CloudEvent-Headers (selbes Pattern wie `DocumentIngestedProducer`).

### Consumer

`ConfigChangeConsumer` empfaengt Events von anderen Instanzen und published sie als lokales Spring ApplicationEvent. Filtert Events die von der eigenen Instanz stammen (um Doppel-Dispatch zu vermeiden).

### Handler-Registrierung

Services reagieren per `@EventListener` — kein eigenes Registry-Pattern:

```kotlin
@Component
class SomeFeatureConfigHandler {
    @EventListener
    fun onConfigChanged(event: ConfigChangedEvent) {
        if (event.configType == ConfigType.LLM_SETTINGS) {
            // reload...
        }
    }
}
```

## Avro Schema

```json
{
  "type": "record",
  "name": "ConfigChanged",
  "namespace": "com.agentwork.graphmesh.config",
  "fields": [
    {"name": "configId", "type": "string"},
    {"name": "configType", "type": "string"},
    {"name": "key", "type": "string"},
    {"name": "action", "type": "string"},
    {"name": "version", "type": "int"}
  ]
}
```

## Test-Strategie

- **Unit-Tests**: `ConfigService` mit gemocktem `ConfigStore` + `ApplicationEventPublisher`
- **Unit-Tests**: `ConfigChangedEvent` dispatch verifizieren
- **Integration-Test**: `CassandraConfigStore` gegen echte Cassandra (docker-compose)
- **Integration-Test**: Kafka roundtrip fuer ConfigChangeProducer/Consumer

## Betroffene Dateien

| Datei | Aenderung |
|---|---|
| `config/ConfigItem.kt` | NEU — Datenmodell (ConfigItem, ConfigType, ConfigAction, ConfigChangedEvent) |
| `config/ConfigStore.kt` | NEU — Interface |
| `config/CassandraConfigStore.kt` | NEU — Cassandra-Implementierung |
| `config/ConfigSchemaInitializer.kt` | NEU — Schema-Erstellung |
| `config/ConfigService.kt` | NEU — CRUD + Event-Publishing |
| `config/ConfigChangeProducer.kt` | NEU — Kafka-Producer |
| `config/ConfigChangeConsumer.kt` | NEU — Kafka-Consumer → Spring Event |
| `resources/avro/config-changed.avsc` | NEU — Avro-Schema |
| `application.yml` | Config-Properties ergaenzen |
| `application-test.yml` | Test-Config ergaenzen |

Alles unter `com.agentwork.graphmesh.config`, kein Submodul.
