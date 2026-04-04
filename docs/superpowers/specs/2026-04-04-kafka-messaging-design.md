# Feature 01: Kafka Messaging Infrastructure — Design Spec

## Entscheidungen

| Entscheidung | Wahl | Begruendung |
|---|---|---|
| Modul-Struktur | Spring Modulith Package (kein separates Gradle-Modul) | Passt zum bestehenden Single-Module-Setup mit Spring Modulith |
| Ansatz | Thin Wrapper ueber Spring Kafka | KafkaTemplate/Container sind bereits da, wenig Boilerplate |
| API-Stil | Kotlin Coroutines (`suspend fun`) | Non-blocking, passt zu async Kafka-Operationen |
| Tests | Testcontainers (echtes Kafka) | Naeher an Produktion als EmbeddedKafkaBroker |
| Request/Reply | Ausgeschlossen (spaeter bei Bedarf) | YAGNI — wird erst ab Feature 05 gebraucht |

## Package-Struktur

```
com.agentwork.graphmesh.messaging/
├── Message.kt                          # Data class mit Payload + Metadaten
├── MessageHeaders.kt                   # Header-Konstanten
├── MessageProducer.kt                  # Interface<T : Any>
├── MessageConsumer.kt                  # Interface<T : Any>
├── MessageProducerFactory.kt           # Factory fuer typsichere Producer
├── MessageConsumerFactory.kt           # Factory fuer typsichere Consumer
├── TopicRegistry.kt                    # Interface: allTopics(), ensureTopicsExist()
├── KafkaTopicConfig.kt                 # Data class: name, partitions, replicationFactor
├── GracefulShutdownCoordinator.kt      # ContextClosedEvent-basierter Shutdown
├── internal/                           # Spring Modulith: modul-intern
│   ├── KafkaMessageProducer.kt         # KafkaTemplate-basiert, Coroutine-Bridge
│   ├── KafkaMessageConsumer.kt         # ConcurrentMessageListenerContainer + CoroutineScope
│   └── DefaultTopicRegistry.kt         # AdminClient-basiert, Topic-Erstellung
└── autoconfigure/
    ├── GraphMeshKafkaProperties.kt     # @ConfigurationProperties
    └── GraphMeshKafkaAutoConfiguration.kt  # Bean-Definitionen
```

`internal/` ist durch Spring Modulith vor Zugriff aus anderen Packages geschuetzt.

## Core Interfaces

### MessageProducer

```kotlin
interface MessageProducer<T : Any> {
    val topic: String
    suspend fun send(message: T, headers: Map<String, String> = emptyMap())
    suspend fun sendWithKey(key: String, message: T, headers: Map<String, String> = emptyMap())
    fun close()
}
```

### MessageConsumer

```kotlin
interface MessageConsumer<T : Any> {
    val topic: String
    val groupId: String
    fun subscribe(handler: suspend (Message<T>) -> Unit)
    fun close()
}
```

### Message

```kotlin
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

### Factories

```kotlin
interface MessageProducerFactory {
    fun <T : Any> create(topic: String, messageType: KClass<T>): MessageProducer<T>
}

interface MessageConsumerFactory {
    fun <T : Any> create(topic: String, groupId: String, messageType: KClass<T>): MessageConsumer<T>
}
```

Andere Module erstellen Producer/Consumer ueber die Factories:

```kotlin
val producer = producerFactory.create("graphmesh.document.uploaded", DocumentEvent::class)
producer.send(DocumentEvent(...))
```

### TopicRegistry & KafkaTopicConfig

```kotlin
data class KafkaTopicConfig(
    val name: String,
    val partitions: Int = 3,
    val replicationFactor: Short = 1,
    val configs: Map<String, String> = emptyMap()
) {
    init { require(name.startsWith("graphmesh.")) { "Topic must follow graphmesh.<domain>.<action> convention" } }
    fun toNewTopic(): NewTopic = NewTopic(name, partitions, replicationFactor).configs(configs)
}

interface TopicRegistry {
    fun allTopics(): List<KafkaTopicConfig>
    fun ensureTopicsExist()
}
```

### MessageHeaders

```kotlin
object MessageHeaders {
    const val CORRELATION_ID = "X-Correlation-Id"
    const val SOURCE_SERVICE = "X-Source-Service"
    const val TIMESTAMP = "X-Timestamp"
    const val MESSAGE_TYPE = "X-Message-Type"
}
```

## Implementierungsdetails

### KafkaMessageProducer

- Nutzt `KafkaTemplate<String, T>`
- Coroutine-Bridge: `template.send(...).asDeferred().await()` (via `kotlinx-coroutines-reactor`)
- Setzt automatisch `X-Timestamp` und `X-Message-Type` Header
- Registriert sich beim `GracefulShutdownCoordinator`

### KafkaMessageConsumer

- Erstellt `ConcurrentMessageListenerContainer` mit `ContainerProperties`
- Message-Handler laeuft in einem `CoroutineScope` (Dispatchers.Default)
- JSON-Deserialisierung ueber Jackson `JsonDeserializer` mit konfiguriertem `ObjectMapper`
- Registriert sich beim `GracefulShutdownCoordinator`

### DefaultTopicRegistry

- Nutzt Kafka `AdminClient` fuer Topic-Erstellung
- Topics werden aus einer registrierbaren Liste verwaltet
- `ensureTopicsExist()` wird via `@EventListener(ApplicationReadyEvent)` getriggert wenn `auto-create-topics=true`

## Konfiguration

```yaml
graphmesh:
  messaging:
    kafka:
      enabled: true
      bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
      group-id-prefix: graphmesh
      auto-create-topics: true
      default-partitions: 3
      default-replication-factor: 1
      graceful-shutdown:
        enabled: true
        drain-timeout-ms: 5000
        await-termination-ms: 10000
```

### Auto-Configuration

- `@ConditionalOnClass(KafkaProducer::class)`
- `@ConditionalOnProperty(prefix = "graphmesh.messaging.kafka", name = ["enabled"], matchIfMissing = true)`
- Registriert: `TopicRegistry`, `GracefulShutdownCoordinator`, `MessageProducerFactory`, `MessageConsumerFactory`
- JSON-Serialisierung nutzt den Spring-Boot-konfigurierten `ObjectMapper`

## Graceful Shutdown

1. `ContextClosedEvent` wird empfangen
2. Alle Consumer stoppen (keine neuen Messages)
3. Drain-Timeout abwarten (`drain-timeout-ms`)
4. Alle Producer flushen und schliessen
5. Await-Termination-Timeout (`await-termination-ms`)

Producer/Consumer registrieren sich automatisch beim Coordinator wenn sie ueber die Factories erstellt werden.

## Testing

| Test | Typ | Was wird getestet |
|---|---|---|
| `KafkaMessageProducerTest` | Integration (Testcontainers) | Send, SendWithKey, Header-Propagation |
| `KafkaMessageConsumerTest` | Integration (Testcontainers) | Subscribe, Deserialisierung, mehrere Messages |
| `ProducerConsumerRoundtripTest` | Integration (Testcontainers) | Producer → Consumer End-to-End |
| `DefaultTopicRegistryTest` | Integration (Testcontainers) | Auto-Create Topics, Topic-Listing |
| `GracefulShutdownCoordinatorTest` | Unit (Mocks) | Shutdown-Reihenfolge, Timeout-Verhalten |
| `GraphMeshKafkaAutoConfigurationTest` | Unit | Conditional Beans, Property-Binding |

### Test-Infrastruktur

- `AbstractKafkaIntegrationTest`: Basisklasse mit `@Testcontainers`, `KafkaContainer`
- `@DynamicPropertySource` setzt `graphmesh.messaging.kafka.bootstrap-servers`
- Shared Container (Singleton) fuer schnellere Test-Ausfuehrung

### Neue Dependencies

```kotlin
// build.gradle.kts
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
testImplementation("org.testcontainers:kafka")
testImplementation("org.testcontainers:junit-jupiter")
```

## Scope-Ausschluesse

- **Request/Reply-Pattern** (`RequestReplyProducer`): Wird spaeter bei Bedarf implementiert (fruehestens Feature 05)
- **Dead Letter Queue**: Nicht in Scope, kann spaeter ergaenzt werden
- **Schema Registry / Avro**: JSON-Serialisierung ist ausreichend fuer den Start
