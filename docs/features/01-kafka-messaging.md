# Feature 01: Kafka Messaging Infrastructure

## Problem

GraphMesh benoetigt eine einheitliche Messaging-Infrastruktur fuer die asynchrone Kommunikation zwischen Microservices.
Ohne eine standardisierte Abstraktionsschicht muessten alle Services direkt mit Kafka-Client-APIs arbeiten, was zu
inkonsistenter Fehlerbehandlung, Serialisierung und Topic-Verwaltung fuehrt. Ausserdem fehlt eine Loesung fuer Graceful
Shutdown, bei der In-Flight-Nachrichten verloren gehen koennen.

## Ziel

Bereitstellung einer Spring-Boot-nativen Kafka-Abstraktionsschicht mit typsicherer Producer/Consumer-API, automatischer
JSON-Serialisierung und Graceful-Shutdown-Unterstuetzung.

1. **MessageProducer<T> / MessageConsumer<T>** -- Typsichere, generische Interfaces fuer Nachrichtenversand und -empfang
2. **Request/Response-Korrelation** -- Header-basierte Korrelation fuer synchrone Kommunikationsmuster ueber Kafka
3. **Topic-Naming-Konvention** -- Standardisiertes Benennungsschema `graphmesh.<domain>.<action>` fuer alle Topics
4. **Auto-Configuration** -- Spring Boot Starter mit automatischer Konfiguration ueber `application.yml`
5. **Graceful Shutdown** -- Queue-Draining und sauberes Herunterfahren ohne Nachrichtenverlust

## Voraussetzungen

| Abhaengigkeit                 | Status     | Blocker? |
|-------------------------------|------------|----------|
| Apache Kafka (Broker)         | Verfuegbar | Nein     |
| Spring Boot 3.x               | Verfuegbar | Nein     |
| Jackson (JSON-Serialisierung) | Verfuegbar | Nein     |
| Spring Kafka                  | Verfuegbar | Nein     |

## Architektur

### Topic-Naming-Konvention

Alle Kafka-Topics folgen dem Schema `graphmesh.<domain>.<action>`:

| Topic                            | Beschreibung                          |
|----------------------------------|---------------------------------------|
| `graphmesh.config.push`          | Konfigurationsaenderungen broadcasten |
| `graphmesh.document.uploaded`    | Neues Dokument hochgeladen            |
| `graphmesh.document.chunked`     | Dokument in Chunks zerlegt            |
| `graphmesh.extraction.completed` | Extraktion abgeschlossen              |
| `graphmesh.embedding.requested`  | Embedding-Generierung angefordert     |

### Core Interfaces

```kotlin
package com.graphmesh.messaging

import java.time.Duration

/**
 * Typsicherer Message Producer.
 * Sendet Nachrichten an ein Kafka-Topic mit optionalen Headern.
 */
interface MessageProducer<T : Any> {
    val topic: String

    suspend fun send(message: T, headers: Map<String, String> = emptyMap())
    suspend fun sendWithKey(key: String, message: T, headers: Map<String, String> = emptyMap())
    fun close()
}

/**
 * Typsicherer Message Consumer.
 * Empfaengt Nachrichten von einem Kafka-Topic.
 */
interface MessageConsumer<T : Any> {
    val topic: String
    val groupId: String

    fun subscribe(handler: suspend (Message<T>) -> Unit)
    fun close()
}

/**
 * Wrapper fuer eine empfangene Nachricht mit Metadaten.
 */
data class Message<T>(
    val payload: T,
    val key: String?,
    val headers: Map<String, String>,
    val topic: String,
    val partition: Int,
    val offset: Long,
    val timestamp: Long
)
```

### Request/Response-Korrelation

Fuer synchrone Kommunikationsmuster (z.B. LLM-Aufrufe) wird eine Korrelation ueber Kafka-Header realisiert:

```kotlin
package com.graphmesh.messaging

import java.util.UUID
import java.time.Duration

/**
 * Header-Keys fuer Request/Response-Korrelation.
 */
object MessageHeaders {
    const val CORRELATION_ID = "X-Correlation-Id"
    const val REPLY_TOPIC = "X-Reply-Topic"
    const val SOURCE_SERVICE = "X-Source-Service"
    const val TIMESTAMP = "X-Timestamp"
    const val MESSAGE_TYPE = "X-Message-Type"
}

/**
 * Korrelierter Request/Response ueber Kafka-Topics.
 */
interface RequestReplyProducer<REQ : Any, RES : Any> {
    suspend fun request(
        request: REQ,
        timeout: Duration = Duration.ofSeconds(30)
    ): RES

    fun close()
}
```

### Topic-Konfiguration

```kotlin
package com.graphmesh.messaging

import org.apache.kafka.clients.admin.NewTopic

/**
 * Konfiguration fuer ein Kafka-Topic.
 */
data class KafkaTopicConfig(
    val name: String,
    val partitions: Int = 3,
    val replicationFactor: Short = 1,
    val configs: Map<String, String> = emptyMap()
) {
    fun toNewTopic(): NewTopic =
        NewTopic(name, partitions, replicationFactor).configs(configs)
}

/**
 * Registry fuer alle Topics der Anwendung.
 */
interface TopicRegistry {
    fun allTopics(): List<KafkaTopicConfig>
    fun ensureTopicsExist()
}
```

### Spring Boot Auto-Configuration

```kotlin
package com.graphmesh.messaging.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.apache.kafka.clients.producer.KafkaProducer

@ConfigurationProperties(prefix = "graphmesh.messaging.kafka")
data class GraphMeshKafkaProperties(
    val bootstrapServers: String = "localhost:9092",
    val groupIdPrefix: String = "graphmesh",
    val autoCreateTopics: Boolean = true,
    val defaultPartitions: Int = 3,
    val defaultReplicationFactor: Short = 1,
    val gracefulShutdown: GracefulShutdownProperties = GracefulShutdownProperties()
)

data class GracefulShutdownProperties(
    val enabled: Boolean = true,
    val drainTimeoutMs: Long = 5000,
    val awaitTerminationMs: Long = 10000
)

@AutoConfiguration
@ConditionalOnClass(KafkaProducer::class)
@ConditionalOnProperty(prefix = "graphmesh.messaging.kafka", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(GraphMeshKafkaProperties::class)
class GraphMeshKafkaAutoConfiguration {

    @Bean
    fun topicRegistry(properties: GraphMeshKafkaProperties): TopicRegistry =
        DefaultTopicRegistry(properties)

    @Bean
    fun kafkaMessageProducerFactory(properties: GraphMeshKafkaProperties): MessageProducerFactory =
        KafkaMessageProducerFactory(properties)
}
```

### Graceful Shutdown

```kotlin
package com.graphmesh.messaging

import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.slf4j.LoggerFactory

/**
 * Koordiniert das saubere Herunterfahren aller Producer und Consumer.
 * Stellt sicher, dass In-Flight-Nachrichten zugestellt werden.
 */
class GracefulShutdownCoordinator(
    private val properties: GracefulShutdownProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val producers = mutableListOf<MessageProducer<*>>()
    private val consumers = mutableListOf<MessageConsumer<*>>()

    fun register(producer: MessageProducer<*>) { producers.add(producer) }
    fun register(consumer: MessageConsumer<*>) { consumers.add(consumer) }

    @EventListener(ContextClosedEvent::class)
    fun onShutdown() {
        log.info("Graceful shutdown gestartet, drainTimeout={}ms", properties.drainTimeoutMs)

        // 1. Consumer stoppen (keine neuen Nachrichten)
        consumers.forEach { it.close() }
        log.info("{} Consumer geschlossen", consumers.size)

        // 2. Producer flushen und schliessen
        producers.forEach { it.close() }
        log.info("{} Producer geschlossen", producers.size)

        log.info("Graceful shutdown abgeschlossen")
    }
}
```

### application.yml Beispiel

```yaml
graphmesh:
  messaging:
    kafka:
      enabled: true
      bootstrap-servers: localhost:9092
      group-id-prefix: graphmesh
      auto-create-topics: true
      default-partitions: 3
      default-replication-factor: 1
      graceful-shutdown:
        enabled: true
        drain-timeout-ms: 5000
        await-termination-ms: 10000
```

## Betroffene Dateien

### Backend

| Datei                                                                                                           | Aenderung                             |
|-----------------------------------------------------------------------------------------------------------------|---------------------------------------|
| `messaging/src/main/kotlin/com/graphmesh/messaging/MessageProducer.kt`                                          | NEU - Producer-Interface              |
| `messaging/src/main/kotlin/com/graphmesh/messaging/MessageConsumer.kt`                                          | NEU - Consumer-Interface              |
| `messaging/src/main/kotlin/com/graphmesh/messaging/Message.kt`                                                  | NEU - Message-Wrapper                 |
| `messaging/src/main/kotlin/com/graphmesh/messaging/MessageHeaders.kt`                                           | NEU - Header-Konstanten               |
| `messaging/src/main/kotlin/com/graphmesh/messaging/RequestReplyProducer.kt`                                     | NEU - Request/Response-Pattern        |
| `messaging/src/main/kotlin/com/graphmesh/messaging/KafkaTopicConfig.kt`                                         | NEU - Topic-Konfiguration             |
| `messaging/src/main/kotlin/com/graphmesh/messaging/TopicRegistry.kt`                                            | NEU - Topic-Registry                  |
| `messaging/src/main/kotlin/com/graphmesh/messaging/GracefulShutdownCoordinator.kt`                              | NEU - Shutdown-Koordination           |
| `messaging/src/main/kotlin/com/graphmesh/messaging/impl/KafkaMessageProducer.kt`                                | NEU - Kafka-Producer-Implementierung  |
| `messaging/src/main/kotlin/com/graphmesh/messaging/impl/KafkaMessageConsumer.kt`                                | NEU - Kafka-Consumer-Implementierung  |
| `messaging/src/main/kotlin/com/graphmesh/messaging/impl/KafkaRequestReplyProducer.kt`                           | NEU - Request/Reply-Implementierung   |
| `messaging/src/main/kotlin/com/graphmesh/messaging/autoconfigure/GraphMeshKafkaAutoConfiguration.kt`            | NEU - Auto-Configuration              |
| `messaging/src/main/kotlin/com/graphmesh/messaging/autoconfigure/GraphMeshKafkaProperties.kt`                   | NEU - Properties                      |
| `messaging/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | NEU - Auto-Configuration-Registration |
| `messaging/build.gradle.kts`                                                                                    | NEU - Gradle-Modul                    |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                   | Aenderung                                  |
|-----------------------------------------------------------------------------------------|--------------------------------------------|
| `messaging/src/test/kotlin/com/graphmesh/messaging/KafkaMessageProducerTest.kt`         | NEU - Producer-Unit-Tests                  |
| `messaging/src/test/kotlin/com/graphmesh/messaging/KafkaMessageConsumerTest.kt`         | NEU - Consumer-Unit-Tests                  |
| `messaging/src/test/kotlin/com/graphmesh/messaging/RequestReplyProducerTest.kt`         | NEU - Request/Reply-Tests                  |
| `messaging/src/test/kotlin/com/graphmesh/messaging/GracefulShutdownCoordinatorTest.kt`  | NEU - Shutdown-Tests                       |
| `messaging/src/test/kotlin/com/graphmesh/messaging/TopicRegistryTest.kt`                | NEU - Topic-Registry-Tests                 |
| `messaging/src/test/kotlin/com/graphmesh/messaging/integration/KafkaIntegrationTest.kt` | NEU - Integrationstests mit Testcontainers |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                     |
|-------------------|-------------|-------------------------------------------|
| Spring Boot (JVM) | Ja          | Spring Kafka bietet volle Unterstuetzung  |
| KMP Library       | Nein        | Kafka-Client ist JVM-only                 |
| Ktor/Wasm         | Nein        | Kein Kafka-Client fuer Wasm/JS verfuegbar |

## Akzeptanzkriterien

- [ ] `MessageProducer<T>` sendet typsichere Nachrichten an konfigurierte Kafka-Topics
- [ ] `MessageConsumer<T>` empfaengt und deserialisiert Nachrichten korrekt
- [ ] JSON-Serialisierung/Deserialisierung funktioniert fuer alle Datentypen (inkl. verschachtelte Objekte)
- [ ] Request/Response-Korrelation ueber `X-Correlation-Id`-Header funktioniert mit konfigurierbarem Timeout
- [ ] Topic-Naming folgt der Konvention `graphmesh.<domain>.<action>`
- [ ] Topics werden automatisch erstellt, wenn `auto-create-topics=true`
- [ ] Graceful Shutdown: Alle In-Flight-Nachrichten werden innerhalb des Drain-Timeouts zugestellt
- [ ] Spring Boot Auto-Configuration funktioniert ohne manuelle Bean-Definition
- [ ] Konfiguration ueber `application.yml` moeglich (`graphmesh.messaging.kafka.*`)
- [ ] Integrationstests mit Testcontainers (Kafka) laufen erfolgreich
- [ ] Bestehende Funktionalitaet bleibt unberuehrt
