# Feature 06: Configuration Service

## Problem

GraphMesh besteht aus mehreren verteilten Services, die alle auf gemeinsame Konfigurationen (Ontologien, Flows, Tools,
Parameter) zugreifen muessen. Ohne zentralen Configuration Service muss jeder Service seine eigene Konfiguration
verwalten, was zu Inkonsistenzen fuehrt. Konfigurationsaenderungen erfordern Neustarts, und es gibt keine Versionierung
oder Audit-Trail. Ausserdem fehlt ein zentralisiertes Logging-Setup fuer die gesamte Plattform.

## Ziel

Bereitstellung eines zentralen Configuration Service mit Cassandra-Persistenz, Kafka-basierter Change-Notification und
Handler-Registration-Pattern fuer reaktive Konfigurationsaenderungen.

1. **ConfigStore** -- Cassandra-basierte Persistenz fuer Konfigurationselemente mit Versionierung
2. **ConfigService** -- CRUD-API fuer Konfigurationselemente (Ontologien, Flows, Tools, Parameter)
3. **Config Push via Kafka** -- Benachrichtigung aller Services ueber Konfigurationsaenderungen
4. **ConfigHandler Pattern** -- Services registrieren sich fuer bestimmte Config-Aenderungen
5. **Loki-basiertes Logging** -- Zentralisiertes Logging-Setup mit Loki-Integration

## Voraussetzungen

| Abhaengigkeit                              | Status  | Blocker? |
|--------------------------------------------|---------|----------|
| Feature 01: Kafka Messaging Infrastructure | Geplant | Ja       |
| Feature 02: Cassandra Storage Layer        | Geplant | Ja       |
| Apache Cassandra                           | Geplant | Nein     |
| Apache Kafka                               | Geplant | Nein     |
| Grafana Loki                               | Geplant | Nein     |

## Architektur

### Konfigurationsmodell

```kotlin
package com.graphmesh.config

import java.time.Instant

/**
 * Typen von Konfigurationselementen.
 */
enum class ConfigType {
    ONTOLOGY,
    FLOW,
    TOOL,
    PARAMETER,
    COLLECTION_SETTINGS,
    LLM_SETTINGS
}

/**
 * Ein versioniertes Konfigurationselement.
 */
data class ConfigItem(
    val id: String,
    val type: ConfigType,
    val key: String,
    val value: String,               // JSON-serialisierter Wert
    val version: Int = 1,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val createdBy: String = "system",
    val description: String = ""
)

/**
 * Event das bei Konfigurationsaenderungen ueber Kafka verteilt wird.
 */
data class ConfigChangeEvent(
    val configId: String,
    val configType: ConfigType,
    val key: String,
    val action: ConfigAction,
    val version: Int,
    val timestamp: Instant = Instant.now(),
    val changedBy: String = "system"
)

enum class ConfigAction {
    CREATED, UPDATED, DELETED
}
```

### ConfigStore (Cassandra-Persistenz)

```kotlin
package com.graphmesh.config

/**
 * Persistenzschicht fuer Konfigurationselemente in Cassandra.
 */
interface ConfigStore {

    /**
     * Speichert oder aktualisiert ein Konfigurationselement.
     * Erhoeht automatisch die Versionsnummer bei Updates.
     */
    suspend fun save(item: ConfigItem): ConfigItem

    /**
     * Laedt ein Konfigurationselement anhand seiner ID.
     */
    suspend fun findById(id: String): ConfigItem?

    /**
     * Laedt alle Konfigurationselemente eines bestimmten Typs.
     */
    suspend fun findByType(type: ConfigType): List<ConfigItem>

    /**
     * Laedt ein Konfigurationselement anhand von Typ und Key.
     */
    suspend fun findByTypeAndKey(type: ConfigType, key: String): ConfigItem?

    /**
     * Loescht ein Konfigurationselement.
     */
    suspend fun delete(id: String)

    /**
     * Gibt die Versionshistorie eines Konfigurationselements zurueck.
     */
    suspend fun history(id: String, limit: Int = 10): List<ConfigItem>
}
```

### Cassandra Schema

```sql
-- Aktuelle Konfiguration
CREATE TABLE config_items (
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

-- Index fuer Typ-basierte Abfragen
CREATE TABLE config_by_type (
    type        text,
    key         text,
    id          text,
    value       text,
    version     int,
    updated_at  timestamp,
    PRIMARY KEY (type, key)
);

-- Versionshistorie
CREATE TABLE config_history (
    id          text,
    version     int,
    value       text,
    updated_at  timestamp,
    updated_by  text,
    PRIMARY KEY (id, version)
) WITH CLUSTERING ORDER BY (version DESC);
```

### ConfigService

```kotlin
package com.graphmesh.config

import com.graphmesh.messaging.MessageProducer

/**
 * Zentraler Service fuer Konfigurationsmanagement.
 * Kombiniert Persistenz (ConfigStore) mit Benachrichtigung (Kafka).
 */
class ConfigService(
    private val store: ConfigStore,
    private val eventProducer: MessageProducer<ConfigChangeEvent>
) {

    /**
     * Erstellt oder aktualisiert eine Konfiguration und benachrichtigt alle Listener.
     */
    suspend fun save(item: ConfigItem): ConfigItem {
        val existing = store.findById(item.id)
        val action = if (existing == null) ConfigAction.CREATED else ConfigAction.UPDATED
        val version = (existing?.version ?: 0) + 1

        val saved = store.save(item.copy(version = version, updatedAt = java.time.Instant.now()))

        // Benachrichtigung ueber Kafka
        eventProducer.send(
            ConfigChangeEvent(
                configId = saved.id,
                configType = saved.type,
                key = saved.key,
                action = action,
                version = saved.version
            )
        )

        return saved
    }

    suspend fun findById(id: String): ConfigItem? = store.findById(id)
    suspend fun findByType(type: ConfigType): List<ConfigItem> = store.findByType(type)
    suspend fun findByTypeAndKey(type: ConfigType, key: String): ConfigItem? = store.findByTypeAndKey(type, key)

    suspend fun delete(id: String) {
        val item = store.findById(id) ?: return
        store.delete(id)

        eventProducer.send(
            ConfigChangeEvent(
                configId = item.id,
                configType = item.type,
                key = item.key,
                action = ConfigAction.DELETED,
                version = item.version
            )
        )
    }
}
```

### ConfigHandler Pattern

```kotlin
package com.graphmesh.config

/**
 * Interface fuer Services, die auf Konfigurationsaenderungen reagieren wollen.
 * Services implementieren dieses Interface und registrieren sich beim ConfigHandlerRegistry.
 */
interface ConfigHandler {

    /**
     * Die Config-Typen, auf die dieser Handler reagiert.
     */
    val handledTypes: Set<ConfigType>

    /**
     * Wird aufgerufen, wenn eine relevante Konfigurationsaenderung eintritt.
     */
    suspend fun onConfigChange(event: ConfigChangeEvent, item: ConfigItem?)
}

/**
 * Registry und Dispatcher fuer ConfigHandler.
 * Empfaengt Kafka-Events und leitet sie an registrierte Handler weiter.
 */
class ConfigHandlerRegistry(
    private val configStore: ConfigStore
) {
    private val handlers = mutableListOf<ConfigHandler>()

    fun register(handler: ConfigHandler) {
        handlers.add(handler)
    }

    /**
     * Verarbeitet ein ConfigChangeEvent und leitet es an alle relevanten Handler weiter.
     */
    suspend fun dispatch(event: ConfigChangeEvent) {
        val item = if (event.action != ConfigAction.DELETED) {
            configStore.findById(event.configId)
        } else null

        handlers
            .filter { event.configType in it.handledTypes }
            .forEach { handler ->
                try {
                    handler.onConfigChange(event, item)
                } catch (e: Exception) {
                    // Log error but continue with other handlers
                    org.slf4j.LoggerFactory.getLogger(javaClass)
                        .error("ConfigHandler Fehler: ${handler.javaClass.simpleName}", e)
                }
            }
    }
}
```

### Logging-Konfiguration (Loki)

```kotlin
package com.graphmesh.config.logging

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Zentralisierte Logging-Konfiguration mit Loki-Integration.
 */
@ConfigurationProperties(prefix = "graphmesh.logging")
data class LoggingProperties(
    val level: String = "INFO",
    val loki: LokiProperties = LokiProperties()
)

data class LokiProperties(
    val enabled: Boolean = true,
    val url: String = "http://loki:3100/loki/api/v1/push",
    val username: String? = null,
    val password: String? = null,
    val batchSize: Int = 500,
    val labels: Map<String, String> = emptyMap()
)
```

### Spring Boot Auto-Configuration

```kotlin
package com.graphmesh.config.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "graphmesh.config")
data class GraphMeshConfigProperties(
    val cassandraKeyspace: String = "graphmesh_config",
    val kafkaTopic: String = "graphmesh.config.push",
    val historyRetention: Int = 50
)
```

### application.yml Beispiel

```yaml
graphmesh:
  config:
    cassandra-keyspace: graphmesh_config
    kafka-topic: graphmesh.config.push
    history-retention: 50

  logging:
    level: INFO
    loki:
      enabled: true
      url: http://loki:3100/loki/api/v1/push
      batch-size: 500
      labels:
        service: graphmesh
        environment: production
```

## Betroffene Dateien

### Backend

| Datei                                                                                                                | Aenderung                             |
|----------------------------------------------------------------------------------------------------------------------|---------------------------------------|
| `config-service/src/main/kotlin/com/graphmesh/config/ConfigItem.kt`                                                  | NEU - Konfigurations-Datenmodell      |
| `config-service/src/main/kotlin/com/graphmesh/config/ConfigType.kt`                                                  | NEU - Konfigurations-Typen            |
| `config-service/src/main/kotlin/com/graphmesh/config/ConfigChangeEvent.kt`                                           | NEU - Change-Event-Modell             |
| `config-service/src/main/kotlin/com/graphmesh/config/ConfigStore.kt`                                                 | NEU - Persistenz-Interface            |
| `config-service/src/main/kotlin/com/graphmesh/config/ConfigService.kt`                                               | NEU - Service-Implementierung         |
| `config-service/src/main/kotlin/com/graphmesh/config/ConfigHandler.kt`                                               | NEU - Handler-Interface               |
| `config-service/src/main/kotlin/com/graphmesh/config/ConfigHandlerRegistry.kt`                                       | NEU - Handler-Registry                |
| `config-service/src/main/kotlin/com/graphmesh/config/impl/CassandraConfigStore.kt`                                   | NEU - Cassandra-Implementierung       |
| `config-service/src/main/kotlin/com/graphmesh/config/impl/KafkaConfigEventConsumer.kt`                               | NEU - Kafka-Event-Consumer            |
| `config-service/src/main/kotlin/com/graphmesh/config/logging/LoggingProperties.kt`                                   | NEU - Logging-Properties              |
| `config-service/src/main/kotlin/com/graphmesh/config/logging/LokiLogbackAppender.kt`                                 | NEU - Loki-Appender                   |
| `config-service/src/main/kotlin/com/graphmesh/config/autoconfigure/GraphMeshConfigAutoConfiguration.kt`              | NEU - Auto-Configuration              |
| `config-service/src/main/kotlin/com/graphmesh/config/autoconfigure/GraphMeshConfigProperties.kt`                     | NEU - Properties                      |
| `config-service/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | NEU - Auto-Configuration-Registration |
| `config-service/build.gradle.kts`                                                                                    | NEU - Gradle-Modul                    |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                             | Aenderung                                   |
|---------------------------------------------------------------------------------------------------|---------------------------------------------|
| `config-service/src/test/kotlin/com/graphmesh/config/ConfigServiceTest.kt`                        | NEU - Service-Unit-Tests                    |
| `config-service/src/test/kotlin/com/graphmesh/config/ConfigHandlerRegistryTest.kt`                | NEU - Handler-Registry-Tests                |
| `config-service/src/test/kotlin/com/graphmesh/config/CassandraConfigStoreTest.kt`                 | NEU - Store-Unit-Tests                      |
| `config-service/src/test/kotlin/com/graphmesh/config/integration/ConfigServiceIntegrationTest.kt` | NEU - Integrationstests (Cassandra + Kafka) |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                        |
|-------------------|-------------|----------------------------------------------|
| Spring Boot (JVM) | Ja          | Cassandra-Driver und Spring Kafka verfuegbar |
| KMP Library       | Nein        | Abhaengig von Cassandra und Kafka (JVM-only) |
| Ktor/Wasm         | Nein        | Kein Cassandra/Kafka-Support fuer Wasm/JS    |

## Akzeptanzkriterien

- [ ] CRUD-Operationen fuer ConfigItems funktionieren korrekt (Create, Read, Update, Delete)
- [ ] ConfigItems werden versioniert -- jedes Update erhoeht die Versionsnummer
- [ ] Versionshistorie ist abrufbar mit konfigurierbarer Retention
- [ ] Bei jeder Aenderung wird ein `ConfigChangeEvent` ueber das Kafka-Topic `graphmesh.config.push` publiziert
- [ ] `ConfigHandler`-Implementierungen werden korrekt aufgerufen, wenn relevante Aenderungen eintreffen
- [ ] Handler-Fehler verhindern nicht die Benachrichtigung anderer Handler
- [ ] Konfigurationen koennen nach Typ gefiltert abgefragt werden (z.B. alle Ontologien)
- [ ] Cassandra-Schema wird automatisch erstellt
- [ ] Loki-Logging-Integration funktioniert mit konfigurierbaren Labels
- [ ] Spring Boot Auto-Configuration funktioniert ohne manuelle Bean-Definition
- [ ] Integrationstests mit Testcontainers (Cassandra + Kafka) laufen erfolgreich
- [ ] Bestehende Funktionalitaet bleibt unberuehrt
