# Feature 01: Kafka Messaging Infrastructure — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide a type-safe, coroutine-based Kafka messaging abstraction for GraphMesh with auto-configuration, topic management, and graceful shutdown.

**Architecture:** Thin wrapper over Spring Kafka's `KafkaTemplate` and `ConcurrentMessageListenerContainer`. Public interfaces live in `com.agentwork.graphmesh.messaging`, implementations in `internal/` (Spring Modulith hidden). Factories let other modules create typed producers/consumers without touching Kafka APIs directly.

**Tech Stack:** Kotlin, Spring Boot 4.0.5, Spring Kafka, kotlinx-coroutines, Jackson, Testcontainers

---

## File Map

### Public API (`src/main/kotlin/com/agentwork/graphmesh/messaging/`)

| File | Responsibility |
|---|---|
| `Message.kt` | Data class: payload + metadata (key, headers, topic, partition, offset, timestamp) |
| `MessageHeaders.kt` | Header key constants (correlation-id, source-service, timestamp, message-type) |
| `KafkaTopicConfig.kt` | Topic config data class with `graphmesh.` prefix validation and `toNewTopic()` |
| `TopicRegistry.kt` | Interface for topic listing and auto-creation |
| `MessageProducer.kt` | Generic producer interface with `suspend send()` / `suspend sendWithKey()` |
| `MessageConsumer.kt` | Generic consumer interface with `subscribe(handler)` |
| `MessageProducerFactory.kt` | Factory interface to create typed producers |
| `MessageConsumerFactory.kt` | Factory interface to create typed consumers |
| `GracefulShutdownCoordinator.kt` | Coordinates orderly shutdown of all producers/consumers |

### Internal Implementations (`src/main/kotlin/com/agentwork/graphmesh/messaging/internal/`)

| File | Responsibility |
|---|---|
| `KafkaMessageProducer.kt` | `KafkaTemplate`-based producer with coroutine bridge |
| `KafkaMessageConsumer.kt` | `ConcurrentMessageListenerContainer`-based consumer with `CoroutineScope` |
| `DefaultTopicRegistry.kt` | `AdminClient`-based topic creation/listing |
| `KafkaMessageProducerFactory.kt` | Creates `KafkaMessageProducer` instances |
| `KafkaMessageConsumerFactory.kt` | Creates `KafkaMessageConsumer` instances |

### Auto-Configuration (`src/main/kotlin/com/agentwork/graphmesh/messaging/autoconfigure/`)

| File | Responsibility |
|---|---|
| `GraphMeshKafkaProperties.kt` | `@ConfigurationProperties` for `graphmesh.messaging.kafka.*` |
| `GraphMeshKafkaAutoConfiguration.kt` | `@Configuration` registering all beans |

### Configuration

| File | Responsibility |
|---|---|
| `src/main/resources/application.yml` | Replace `application.properties` with YAML config including Kafka defaults |

### Tests (`src/test/kotlin/com/agentwork/graphmesh/messaging/`)

| File | Responsibility |
|---|---|
| `AbstractKafkaIntegrationTest.kt` | Shared Testcontainers base class with `KafkaContainer` singleton |
| `KafkaTopicConfigTest.kt` | Unit test for topic config validation |
| `DefaultTopicRegistryTest.kt` | Integration test: topic auto-creation |
| `KafkaMessageProducerTest.kt` | Integration test: send, sendWithKey, headers |
| `KafkaMessageConsumerTest.kt` | Integration test: subscribe, deserialize |
| `ProducerConsumerRoundtripTest.kt` | Integration test: end-to-end message flow |
| `GracefulShutdownCoordinatorTest.kt` | Unit test: shutdown order, timeouts |

---

## Task 1: Add Dependencies

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add coroutines and testcontainers dependencies**

Add to `build.gradle.kts`:

```kotlin
// In the dependencies block, add after the existing entries:
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
testImplementation("org.testcontainers:kafka")
testImplementation("org.testcontainers:junit-jupiter")
```

Add Testcontainers BOM to `dependencyManagement`:

```kotlin
dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
        mavenBom("org.testcontainers:testcontainers-bom:1.21.1")
    }
}
```

- [ ] **Step 2: Verify dependencies resolve**

Run: `./gradlew dependencies --configuration compileClasspath | head -50`
Expected: All dependencies resolve without errors.

- [ ] **Step 3: Verify existing tests still pass**

Run: `./gradlew test`
Expected: `GraphMeshApplicationTests` passes.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "feat(messaging): add coroutines and testcontainers dependencies"
```

---

## Task 2: Core Data Types (Message, MessageHeaders, KafkaTopicConfig)

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/Message.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/MessageHeaders.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/KafkaTopicConfig.kt`
- Test: `src/test/kotlin/com/agentwork/graphmesh/messaging/KafkaTopicConfigTest.kt`

- [ ] **Step 1: Write KafkaTopicConfig test**

```kotlin
package com.agentwork.graphmesh.messaging

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

class KafkaTopicConfigTest {

    @Test
    fun `valid topic name is accepted`() {
        val config = KafkaTopicConfig(name = "graphmesh.document.uploaded")
        assertEquals("graphmesh.document.uploaded", config.name)
        assertEquals(3, config.partitions)
        assertEquals(1, config.replicationFactor)
    }

    @Test
    fun `topic name without graphmesh prefix is rejected`() {
        assertThrows<IllegalArgumentException> {
            KafkaTopicConfig(name = "invalid.topic.name")
        }
    }

    @Test
    fun `toNewTopic creates correct NewTopic`() {
        val config = KafkaTopicConfig(
            name = "graphmesh.config.push",
            partitions = 6,
            replicationFactor = 3,
            configs = mapOf("retention.ms" to "86400000")
        )
        val newTopic = config.toNewTopic()
        assertEquals("graphmesh.config.push", newTopic.name())
        assertEquals(6, newTopic.numPartitions())
        assertEquals(3, newTopic.replicationFactor())
        assertEquals("86400000", newTopic.configs()["retention.ms"])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.KafkaTopicConfigTest"`
Expected: FAIL — class `KafkaTopicConfig` not found.

- [ ] **Step 3: Create Message.kt**

```kotlin
package com.agentwork.graphmesh.messaging

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

- [ ] **Step 4: Create MessageHeaders.kt**

```kotlin
package com.agentwork.graphmesh.messaging

object MessageHeaders {
    const val CORRELATION_ID = "X-Correlation-Id"
    const val SOURCE_SERVICE = "X-Source-Service"
    const val TIMESTAMP = "X-Timestamp"
    const val MESSAGE_TYPE = "X-Message-Type"
}
```

- [ ] **Step 5: Create KafkaTopicConfig.kt**

```kotlin
package com.agentwork.graphmesh.messaging

import org.apache.kafka.clients.admin.NewTopic

data class KafkaTopicConfig(
    val name: String,
    val partitions: Int = 3,
    val replicationFactor: Short = 1,
    val configs: Map<String, String> = emptyMap()
) {
    init {
        require(name.startsWith("graphmesh.")) {
            "Topic name must follow 'graphmesh.<domain>.<action>' convention, got: $name"
        }
    }

    fun toNewTopic(): NewTopic =
        NewTopic(name, partitions, replicationFactor).configs(configs)
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.KafkaTopicConfigTest"`
Expected: PASS — all 3 tests green.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/messaging/Message.kt \
       src/main/kotlin/com/agentwork/graphmesh/messaging/MessageHeaders.kt \
       src/main/kotlin/com/agentwork/graphmesh/messaging/KafkaTopicConfig.kt \
       src/test/kotlin/com/agentwork/graphmesh/messaging/KafkaTopicConfigTest.kt
git commit -m "feat(messaging): add Message, MessageHeaders, and KafkaTopicConfig"
```

---

## Task 3: Public Interfaces (Producer, Consumer, Factories, TopicRegistry)

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/MessageProducer.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/MessageConsumer.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/MessageProducerFactory.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/MessageConsumerFactory.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/TopicRegistry.kt`

- [ ] **Step 1: Create MessageProducer.kt**

```kotlin
package com.agentwork.graphmesh.messaging

interface MessageProducer<T : Any> {
    val topic: String
    suspend fun send(message: T, headers: Map<String, String> = emptyMap())
    suspend fun sendWithKey(key: String, message: T, headers: Map<String, String> = emptyMap())
    fun close()
}
```

- [ ] **Step 2: Create MessageConsumer.kt**

```kotlin
package com.agentwork.graphmesh.messaging

interface MessageConsumer<T : Any> {
    val topic: String
    val groupId: String
    fun subscribe(handler: suspend (Message<T>) -> Unit)
    fun close()
}
```

- [ ] **Step 3: Create MessageProducerFactory.kt**

```kotlin
package com.agentwork.graphmesh.messaging

import kotlin.reflect.KClass

interface MessageProducerFactory {
    fun <T : Any> create(topic: String, messageType: KClass<T>): MessageProducer<T>
}
```

- [ ] **Step 4: Create MessageConsumerFactory.kt**

```kotlin
package com.agentwork.graphmesh.messaging

import kotlin.reflect.KClass

interface MessageConsumerFactory {
    fun <T : Any> create(topic: String, groupId: String, messageType: KClass<T>): MessageConsumer<T>
}
```

- [ ] **Step 5: Create TopicRegistry.kt**

```kotlin
package com.agentwork.graphmesh.messaging

interface TopicRegistry {
    fun register(config: KafkaTopicConfig)
    fun allTopics(): List<KafkaTopicConfig>
    fun ensureTopicsExist()
}
```

- [ ] **Step 6: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/messaging/MessageProducer.kt \
       src/main/kotlin/com/agentwork/graphmesh/messaging/MessageConsumer.kt \
       src/main/kotlin/com/agentwork/graphmesh/messaging/MessageProducerFactory.kt \
       src/main/kotlin/com/agentwork/graphmesh/messaging/MessageConsumerFactory.kt \
       src/main/kotlin/com/agentwork/graphmesh/messaging/TopicRegistry.kt
git commit -m "feat(messaging): add producer, consumer, factory, and topic registry interfaces"
```

---

## Task 4: Configuration Properties

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/autoconfigure/GraphMeshKafkaProperties.kt`
- Modify: `src/main/resources/application.properties` → replace with `src/main/resources/application.yml`

- [ ] **Step 1: Create GraphMeshKafkaProperties.kt**

```kotlin
package com.agentwork.graphmesh.messaging.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "graphmesh.messaging.kafka")
data class GraphMeshKafkaProperties(
    val enabled: Boolean = true,
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
```

- [ ] **Step 2: Replace application.properties with application.yml**

Delete `src/main/resources/application.properties` and create `src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: GraphMesh

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

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git rm src/main/resources/application.properties
git add src/main/kotlin/com/agentwork/graphmesh/messaging/autoconfigure/GraphMeshKafkaProperties.kt \
       src/main/resources/application.yml
git commit -m "feat(messaging): add Kafka configuration properties and application.yml"
```

---

## Task 5: Test Infrastructure (AbstractKafkaIntegrationTest)

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/messaging/AbstractKafkaIntegrationTest.kt`

- [ ] **Step 1: Create AbstractKafkaIntegrationTest.kt**

This is a shared base class. All integration tests extend it to reuse a singleton `KafkaContainer`.

```kotlin
package com.agentwork.graphmesh.messaging

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
abstract class AbstractKafkaIntegrationTest {

    companion object {
        @JvmStatic
        val kafkaContainer: KafkaContainer = KafkaContainer("apache/kafka-native:4.0.0").apply {
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun kafkaProperties(registry: DynamicPropertyRegistry) {
            registry.add("graphmesh.messaging.kafka.bootstrap-servers") { kafkaContainer.bootstrapServers }
            registry.add("spring.kafka.bootstrap-servers") { kafkaContainer.bootstrapServers }
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/messaging/AbstractKafkaIntegrationTest.kt
git commit -m "feat(messaging): add Testcontainers base class for Kafka integration tests"
```

---

## Task 6: DefaultTopicRegistry Implementation + Test

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/internal/DefaultTopicRegistry.kt`
- Test: `src/test/kotlin/com/agentwork/graphmesh/messaging/DefaultTopicRegistryTest.kt`

- [ ] **Step 1: Write DefaultTopicRegistry test**

```kotlin
package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.messaging.internal.DefaultTopicRegistry
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultTopicRegistryTest : AbstractKafkaIntegrationTest() {

    private lateinit var adminClient: AdminClient
    private lateinit var registry: DefaultTopicRegistry

    @BeforeEach
    fun setUp() {
        adminClient = AdminClient.create(
            mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers)
        )
        registry = DefaultTopicRegistry(adminClient)
    }

    @Test
    fun `register adds topic to registry`() {
        val config = KafkaTopicConfig(name = "graphmesh.test.register")
        registry.register(config)
        assertTrue(registry.allTopics().contains(config))
    }

    @Test
    fun `ensureTopicsExist creates topics on broker`() {
        val topicName = "graphmesh.test.ensure-${System.currentTimeMillis()}"
        val config = KafkaTopicConfig(name = topicName, partitions = 2, replicationFactor = 1)
        registry.register(config)

        registry.ensureTopicsExist()

        val existingTopics = adminClient.listTopics().names().get()
        assertTrue(existingTopics.contains(topicName))
    }

    @Test
    fun `ensureTopicsExist is idempotent`() {
        val topicName = "graphmesh.test.idempotent-${System.currentTimeMillis()}"
        val config = KafkaTopicConfig(name = topicName, partitions = 1, replicationFactor = 1)
        registry.register(config)

        registry.ensureTopicsExist()
        registry.ensureTopicsExist() // second call should not throw

        val existingTopics = adminClient.listTopics().names().get()
        assertTrue(existingTopics.contains(topicName))
    }

    @Test
    fun `allTopics returns all registered topics`() {
        val config1 = KafkaTopicConfig(name = "graphmesh.test.all1-${System.currentTimeMillis()}")
        val config2 = KafkaTopicConfig(name = "graphmesh.test.all2-${System.currentTimeMillis()}")
        registry.register(config1)
        registry.register(config2)
        assertEquals(2, registry.allTopics().size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.DefaultTopicRegistryTest"`
Expected: FAIL — class `DefaultTopicRegistry` not found.

- [ ] **Step 3: Implement DefaultTopicRegistry**

```kotlin
package com.agentwork.graphmesh.messaging.internal

import com.agentwork.graphmesh.messaging.KafkaTopicConfig
import com.agentwork.graphmesh.messaging.TopicRegistry
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.common.errors.TopicExistsException
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException

class DefaultTopicRegistry(
    private val adminClient: AdminClient
) : TopicRegistry {

    private val log = LoggerFactory.getLogger(javaClass)
    private val topics = CopyOnWriteArrayList<KafkaTopicConfig>()

    override fun register(config: KafkaTopicConfig) {
        topics.add(config)
    }

    override fun allTopics(): List<KafkaTopicConfig> = topics.toList()

    override fun ensureTopicsExist() {
        if (topics.isEmpty()) return

        val newTopics = topics.map { it.toNewTopic() }
        val results = adminClient.createTopics(newTopics)

        results.values().forEach { (topicName, future) ->
            try {
                future.get()
                log.info("Created topic: {}", topicName)
            } catch (e: ExecutionException) {
                if (e.cause is TopicExistsException) {
                    log.debug("Topic already exists: {}", topicName)
                } else {
                    throw e
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.DefaultTopicRegistryTest"`
Expected: PASS — all 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/messaging/internal/DefaultTopicRegistry.kt \
       src/test/kotlin/com/agentwork/graphmesh/messaging/DefaultTopicRegistryTest.kt
git commit -m "feat(messaging): implement DefaultTopicRegistry with AdminClient-based topic creation"
```

---

## Task 7: KafkaMessageProducer Implementation + Test

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/internal/KafkaMessageProducer.kt`
- Test: `src/test/kotlin/com/agentwork/graphmesh/messaging/KafkaMessageProducerTest.kt`

- [ ] **Step 1: Write KafkaMessageProducer test**

```kotlin
package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.messaging.internal.KafkaMessageProducer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.test.runTest
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.serializer.JsonSerializer
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

data class TestEvent(val id: String, val value: Int)

class KafkaMessageProducerTest : AbstractKafkaIntegrationTest() {

    private val objectMapper = jacksonObjectMapper()
    private lateinit var kafkaTemplate: KafkaTemplate<String, Any>
    private lateinit var producer: KafkaMessageProducer<TestEvent>
    private lateinit var verificationConsumer: KafkaConsumer<String, String>
    private val topicName = "graphmesh.test.producer-${System.currentTimeMillis()}"

    @BeforeEach
    fun setUp() {
        val producerProps = mapOf(
            org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers,
            org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to org.apache.kafka.common.serialization.StringSerializer::class.java,
            org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
        )
        val producerFactory = DefaultKafkaProducerFactory<String, Any>(producerProps)
        kafkaTemplate = KafkaTemplate(producerFactory)

        producer = KafkaMessageProducer(
            topic = topicName,
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper
        )

        val consumerProps = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "test-verification-${System.currentTimeMillis()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java
        )
        verificationConsumer = KafkaConsumer(consumerProps)
        verificationConsumer.subscribe(listOf(topicName))
    }

    @AfterEach
    fun tearDown() {
        producer.close()
        verificationConsumer.close()
    }

    @Test
    fun `send publishes message to topic`() = runTest {
        producer.send(TestEvent(id = "evt-1", value = 42))

        val records = pollUntilRecords(1)
        assertEquals(1, records.size)
        val parsed = objectMapper.readTree(records[0].value())
        assertEquals("evt-1", parsed["id"].asText())
        assertEquals(42, parsed["value"].asInt())
    }

    @Test
    fun `sendWithKey sets the record key`() = runTest {
        producer.sendWithKey("my-key", TestEvent(id = "evt-2", value = 99))

        val records = pollUntilRecords(1)
        assertEquals(1, records.size)
        assertEquals("my-key", records[0].key())
    }

    @Test
    fun `send propagates custom headers`() = runTest {
        producer.send(
            TestEvent(id = "evt-3", value = 1),
            headers = mapOf("X-Custom" to "hello")
        )

        val records = pollUntilRecords(1)
        val header = records[0].headers().lastHeader("X-Custom")
        assertNotNull(header)
        assertEquals("hello", String(header.value()))
    }

    @Test
    fun `send sets automatic headers`() = runTest {
        producer.send(TestEvent(id = "evt-4", value = 0))

        val records = pollUntilRecords(1)
        val timestampHeader = records[0].headers().lastHeader(MessageHeaders.TIMESTAMP)
        val typeHeader = records[0].headers().lastHeader(MessageHeaders.MESSAGE_TYPE)
        assertNotNull(timestampHeader)
        assertNotNull(typeHeader)
        assertTrue(String(typeHeader.value()).contains("TestEvent"))
    }

    private fun pollUntilRecords(
        expected: Int,
        timeout: Duration = Duration.ofSeconds(10)
    ): List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        val collected = mutableListOf<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>()
        while (collected.size < expected && System.currentTimeMillis() < deadline) {
            val batch = verificationConsumer.poll(Duration.ofMillis(200))
            collected.addAll(batch)
        }
        return collected
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.KafkaMessageProducerTest"`
Expected: FAIL — class `KafkaMessageProducer` not found.

- [ ] **Step 3: Implement KafkaMessageProducer**

```kotlin
package com.agentwork.graphmesh.messaging.internal

import com.agentwork.graphmesh.messaging.MessageHeaders
import com.agentwork.graphmesh.messaging.MessageProducer
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.future.await
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.kafka.core.KafkaTemplate
import java.time.Instant

class KafkaMessageProducer<T : Any>(
    override val topic: String,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val objectMapper: ObjectMapper
) : MessageProducer<T> {

    override suspend fun send(message: T, headers: Map<String, String>) {
        sendWithKey(key = "", message = message, headers = headers)
    }

    override suspend fun sendWithKey(key: String, message: T, headers: Map<String, String>) {
        val record = ProducerRecord<String, Any>(topic, null, key.ifEmpty { null }, message)
        addHeaders(record, message, headers)
        kafkaTemplate.send(record).await()
    }

    override fun close() {
        kafkaTemplate.flush()
    }

    private fun addHeaders(record: ProducerRecord<String, Any>, message: T, customHeaders: Map<String, String>) {
        record.headers().add(RecordHeader(MessageHeaders.TIMESTAMP, Instant.now().toString().toByteArray()))
        record.headers().add(RecordHeader(MessageHeaders.MESSAGE_TYPE, message::class.simpleName.orEmpty().toByteArray()))
        customHeaders.forEach { (k, v) ->
            record.headers().add(RecordHeader(k, v.toByteArray()))
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.KafkaMessageProducerTest"`
Expected: PASS — all 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/messaging/internal/KafkaMessageProducer.kt \
       src/test/kotlin/com/agentwork/graphmesh/messaging/KafkaMessageProducerTest.kt
git commit -m "feat(messaging): implement KafkaMessageProducer with coroutine-based send"
```

---

## Task 8: KafkaMessageConsumer Implementation + Test

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/internal/KafkaMessageConsumer.kt`
- Test: `src/test/kotlin/com/agentwork/graphmesh/messaging/KafkaMessageConsumerTest.kt`

- [ ] **Step 1: Write KafkaMessageConsumer test**

```kotlin
package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.messaging.internal.KafkaMessageConsumer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.test.runTest
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.kafka.support.serializer.JsonSerializer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KafkaMessageConsumerTest : AbstractKafkaIntegrationTest() {

    private val objectMapper = jacksonObjectMapper()
    private lateinit var rawProducer: KafkaProducer<String, Any>
    private lateinit var consumer: KafkaMessageConsumer<TestEvent>
    private val topicName = "graphmesh.test.consumer-${System.currentTimeMillis()}"

    @BeforeEach
    fun setUp() {
        val producerProps = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
        )
        rawProducer = KafkaProducer(producerProps)

        consumer = KafkaMessageConsumer(
            topic = topicName,
            groupId = "test-consumer-${System.currentTimeMillis()}",
            messageType = TestEvent::class,
            bootstrapServers = kafkaContainer.bootstrapServers,
            objectMapper = objectMapper
        )
    }

    @AfterEach
    fun tearDown() {
        consumer.close()
        rawProducer.close()
    }

    @Test
    fun `subscribe receives and deserializes messages`() {
        val received = CopyOnWriteArrayList<Message<TestEvent>>()
        val latch = CountDownLatch(1)

        consumer.subscribe { msg ->
            received.add(msg)
            latch.countDown()
        }

        rawProducer.send(ProducerRecord(topicName, "key-1", TestEvent("evt-1", 42) as Any)).get()

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out waiting for message")
        assertEquals(1, received.size)
        assertEquals("evt-1", received[0].payload.id)
        assertEquals(42, received[0].payload.value)
        assertEquals("key-1", received[0].key)
        assertEquals(topicName, received[0].topic)
    }

    @Test
    fun `subscribe receives multiple messages`() {
        val received = CopyOnWriteArrayList<Message<TestEvent>>()
        val latch = CountDownLatch(3)

        consumer.subscribe { msg ->
            received.add(msg)
            latch.countDown()
        }

        repeat(3) { i ->
            rawProducer.send(ProducerRecord(topicName, TestEvent("evt-$i", i) as Any)).get()
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out waiting for messages")
        assertEquals(3, received.size)
    }

    @Test
    fun `subscribe propagates headers`() {
        val received = CopyOnWriteArrayList<Message<TestEvent>>()
        val latch = CountDownLatch(1)

        consumer.subscribe { msg ->
            received.add(msg)
            latch.countDown()
        }

        val record = ProducerRecord(topicName, null as String?, TestEvent("evt-h", 0) as Any)
        record.headers().add(RecordHeader("X-Custom", "test-value".toByteArray()))
        rawProducer.send(record).get()

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out waiting for message")
        assertEquals("test-value", received[0].headers["X-Custom"])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.KafkaMessageConsumerTest"`
Expected: FAIL — class `KafkaMessageConsumer` not found.

- [ ] **Step 3: Implement KafkaMessageConsumer**

```kotlin
package com.agentwork.graphmesh.messaging.internal

import com.agentwork.graphmesh.messaging.Message
import com.agentwork.graphmesh.messaging.MessageConsumer
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.support.serializer.JsonDeserializer
import kotlin.reflect.KClass

class KafkaMessageConsumer<T : Any>(
    override val topic: String,
    override val groupId: String,
    private val messageType: KClass<T>,
    private val bootstrapServers: String,
    private val objectMapper: ObjectMapper
) : MessageConsumer<T> {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var container: ConcurrentMessageListenerContainer<String, T>? = null

    override fun subscribe(handler: suspend (Message<T>) -> Unit) {
        val consumerProps = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest"
        )

        val jsonDeserializer = JsonDeserializer(messageType.java, objectMapper).apply {
            setUseTypeHeaders(false)
            addTrustedPackages("*")
        }

        val consumerFactory = DefaultKafkaConsumerFactory(
            consumerProps,
            StringDeserializer(),
            jsonDeserializer
        )

        val containerProps = ContainerProperties(topic).apply {
            messageListener = MessageListener<String, T> { record ->
                scope.launch {
                    handler(toMessage(record))
                }
            }
        }

        container = ConcurrentMessageListenerContainer(consumerFactory, containerProps).apply {
            start()
        }
    }

    override fun close() {
        container?.stop()
        scope.cancel()
    }

    private fun toMessage(record: ConsumerRecord<String, T>): Message<T> {
        val headers = record.headers().associate { header ->
            header.key() to String(header.value())
        }
        return Message(
            payload = record.value(),
            key = record.key(),
            headers = headers,
            topic = record.topic(),
            partition = record.partition(),
            offset = record.offset(),
            timestamp = record.timestamp()
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.KafkaMessageConsumerTest"`
Expected: PASS — all 3 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/messaging/internal/KafkaMessageConsumer.kt \
       src/test/kotlin/com/agentwork/graphmesh/messaging/KafkaMessageConsumerTest.kt
git commit -m "feat(messaging): implement KafkaMessageConsumer with coroutine-based handler"
```

---

## Task 9: Producer-Consumer Roundtrip Integration Test

**Files:**
- Test: `src/test/kotlin/com/agentwork/graphmesh/messaging/ProducerConsumerRoundtripTest.kt`

- [ ] **Step 1: Write roundtrip test**

```kotlin
package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.messaging.internal.KafkaMessageConsumer
import com.agentwork.graphmesh.messaging.internal.KafkaMessageProducer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.test.runTest
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.serializer.JsonSerializer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

data class OrderEvent(val orderId: String, val amount: Double, val items: List<String>)

class ProducerConsumerRoundtripTest : AbstractKafkaIntegrationTest() {

    private val objectMapper = jacksonObjectMapper()
    private lateinit var producer: KafkaMessageProducer<OrderEvent>
    private lateinit var consumer: KafkaMessageConsumer<OrderEvent>
    private val topicName = "graphmesh.test.roundtrip-${System.currentTimeMillis()}"

    @BeforeEach
    fun setUp() {
        val producerProps = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
        )
        val producerFactory = DefaultKafkaProducerFactory<String, Any>(producerProps)
        val kafkaTemplate = KafkaTemplate(producerFactory)

        producer = KafkaMessageProducer(
            topic = topicName,
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper
        )

        consumer = KafkaMessageConsumer(
            topic = topicName,
            groupId = "test-roundtrip-${System.currentTimeMillis()}",
            messageType = OrderEvent::class,
            bootstrapServers = kafkaContainer.bootstrapServers,
            objectMapper = objectMapper
        )
    }

    @AfterEach
    fun tearDown() {
        consumer.close()
        producer.close()
    }

    @Test
    fun `message sent by producer is received by consumer with correct payload`() = runTest {
        val received = CopyOnWriteArrayList<Message<OrderEvent>>()
        val latch = CountDownLatch(1)

        consumer.subscribe { msg ->
            received.add(msg)
            latch.countDown()
        }

        val event = OrderEvent(
            orderId = "order-123",
            amount = 59.99,
            items = listOf("widget", "gadget")
        )
        producer.sendWithKey("order-123", event, mapOf(MessageHeaders.CORRELATION_ID to "corr-abc"))

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out waiting for roundtrip message")

        val msg = received[0]
        assertEquals("order-123", msg.payload.orderId)
        assertEquals(59.99, msg.payload.amount)
        assertEquals(listOf("widget", "gadget"), msg.payload.items)
        assertEquals("order-123", msg.key)
        assertEquals("corr-abc", msg.headers[MessageHeaders.CORRELATION_ID])
        assertTrue(msg.headers.containsKey(MessageHeaders.TIMESTAMP))
        assertTrue(msg.headers.containsKey(MessageHeaders.MESSAGE_TYPE))
    }

    @Test
    fun `multiple messages maintain order per partition`() = runTest {
        val received = CopyOnWriteArrayList<Message<OrderEvent>>()
        val latch = CountDownLatch(5)

        consumer.subscribe { msg ->
            received.add(msg)
            latch.countDown()
        }

        repeat(5) { i ->
            producer.sendWithKey("same-key", OrderEvent("order-$i", i.toDouble(), emptyList()))
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out waiting for messages")
        assertEquals(5, received.size)
        // Same key → same partition → order preserved
        val orderIds = received.map { it.payload.orderId }
        assertEquals(listOf("order-0", "order-1", "order-2", "order-3", "order-4"), orderIds)
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.ProducerConsumerRoundtripTest"`
Expected: PASS — both tests green.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/messaging/ProducerConsumerRoundtripTest.kt
git commit -m "test(messaging): add producer-consumer roundtrip integration tests"
```

---

## Task 10: GracefulShutdownCoordinator + Test

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/GracefulShutdownCoordinator.kt`
- Test: `src/test/kotlin/com/agentwork/graphmesh/messaging/GracefulShutdownCoordinatorTest.kt`

- [ ] **Step 1: Write GracefulShutdownCoordinator test**

```kotlin
package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.messaging.autoconfigure.GracefulShutdownProperties
import org.junit.jupiter.api.BeforeEach
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.support.GenericApplicationContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class GracefulShutdownCoordinatorTest {

    private lateinit var coordinator: GracefulShutdownCoordinator
    private val shutdownOrder = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun setUp() {
        shutdownOrder.clear()
        coordinator = GracefulShutdownCoordinator(
            GracefulShutdownProperties(enabled = true, drainTimeoutMs = 100, awaitTerminationMs = 100)
        )
    }

    @Test
    fun `shutdown closes consumers before producers`() {
        val consumer = StubConsumer("consumer-1") { shutdownOrder.add("consumer-1") }
        val producer = StubProducer("producer-1") { shutdownOrder.add("producer-1") }

        coordinator.register(producer)
        coordinator.register(consumer)

        coordinator.onShutdown()

        assertEquals(listOf("consumer-1", "producer-1"), shutdownOrder)
    }

    @Test
    fun `shutdown closes multiple consumers then multiple producers`() {
        val consumer1 = StubConsumer("c1") { shutdownOrder.add("c1") }
        val consumer2 = StubConsumer("c2") { shutdownOrder.add("c2") }
        val producer1 = StubProducer("p1") { shutdownOrder.add("p1") }
        val producer2 = StubProducer("p2") { shutdownOrder.add("p2") }

        coordinator.register(producer1)
        coordinator.register(consumer1)
        coordinator.register(producer2)
        coordinator.register(consumer2)

        coordinator.onShutdown()

        // All consumers first, then all producers
        assertEquals(listOf("c1", "c2", "p1", "p2"), shutdownOrder)
    }

    @Test
    fun `shutdown with no registrations does not throw`() {
        coordinator.onShutdown() // should complete without error
    }

    private class StubProducer(
        override val topic: String,
        private val onClose: () -> Unit
    ) : MessageProducer<Any> {
        override suspend fun send(message: Any, headers: Map<String, String>) {}
        override suspend fun sendWithKey(key: String, message: Any, headers: Map<String, String>) {}
        override fun close() { onClose() }
    }

    private class StubConsumer(
        override val topic: String,
        private val onClose: () -> Unit
    ) : MessageConsumer<Any> {
        override val groupId = "stub"
        override fun subscribe(handler: suspend (Message<Any>) -> Unit) {}
        override fun close() { onClose() }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.GracefulShutdownCoordinatorTest"`
Expected: FAIL — class `GracefulShutdownCoordinator` not found.

- [ ] **Step 3: Implement GracefulShutdownCoordinator**

```kotlin
package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.messaging.autoconfigure.GracefulShutdownProperties
import org.slf4j.LoggerFactory
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import java.util.concurrent.CopyOnWriteArrayList

class GracefulShutdownCoordinator(
    private val properties: GracefulShutdownProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val producers = CopyOnWriteArrayList<MessageProducer<*>>()
    private val consumers = CopyOnWriteArrayList<MessageConsumer<*>>()

    fun register(producer: MessageProducer<*>) {
        producers.add(producer)
    }

    fun register(consumer: MessageConsumer<*>) {
        consumers.add(consumer)
    }

    @EventListener(ContextClosedEvent::class)
    fun onShutdown() {
        if (!properties.enabled) return

        log.info("Graceful shutdown started, drainTimeout={}ms", properties.drainTimeoutMs)

        consumers.forEach { it.close() }
        log.info("{} consumer(s) closed", consumers.size)

        if (properties.drainTimeoutMs > 0) {
            Thread.sleep(properties.drainTimeoutMs)
        }

        producers.forEach { it.close() }
        log.info("{} producer(s) closed", producers.size)

        log.info("Graceful shutdown completed")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.GracefulShutdownCoordinatorTest"`
Expected: PASS — all 3 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/messaging/GracefulShutdownCoordinator.kt \
       src/test/kotlin/com/agentwork/graphmesh/messaging/GracefulShutdownCoordinatorTest.kt
git commit -m "feat(messaging): implement GracefulShutdownCoordinator"
```

---

## Task 11: Factories + Auto-Configuration

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/internal/KafkaMessageProducerFactory.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/internal/KafkaMessageConsumerFactory.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/autoconfigure/GraphMeshKafkaAutoConfiguration.kt`

- [ ] **Step 1: Implement KafkaMessageProducerFactory**

```kotlin
package com.agentwork.graphmesh.messaging.internal

import com.agentwork.graphmesh.messaging.GracefulShutdownCoordinator
import com.agentwork.graphmesh.messaging.MessageProducer
import com.agentwork.graphmesh.messaging.MessageProducerFactory
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.core.KafkaTemplate
import kotlin.reflect.KClass

class KafkaMessageProducerFactory(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val objectMapper: ObjectMapper,
    private val shutdownCoordinator: GracefulShutdownCoordinator
) : MessageProducerFactory {

    override fun <T : Any> create(topic: String, messageType: KClass<T>): MessageProducer<T> {
        val producer = KafkaMessageProducer<T>(
            topic = topic,
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper
        )
        shutdownCoordinator.register(producer)
        return producer
    }
}
```

- [ ] **Step 2: Implement KafkaMessageConsumerFactory**

```kotlin
package com.agentwork.graphmesh.messaging.internal

import com.agentwork.graphmesh.messaging.GracefulShutdownCoordinator
import com.agentwork.graphmesh.messaging.MessageConsumer
import com.agentwork.graphmesh.messaging.MessageConsumerFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.agentwork.graphmesh.messaging.autoconfigure.GraphMeshKafkaProperties
import kotlin.reflect.KClass

class KafkaMessageConsumerFactory(
    private val properties: GraphMeshKafkaProperties,
    private val objectMapper: ObjectMapper,
    private val shutdownCoordinator: GracefulShutdownCoordinator
) : MessageConsumerFactory {

    override fun <T : Any> create(topic: String, groupId: String, messageType: KClass<T>): MessageConsumer<T> {
        val consumer = KafkaMessageConsumer(
            topic = topic,
            groupId = "${properties.groupIdPrefix}.$groupId",
            messageType = messageType,
            bootstrapServers = properties.bootstrapServers,
            objectMapper = objectMapper
        )
        shutdownCoordinator.register(consumer)
        return consumer
    }
}
```

- [ ] **Step 3: Implement GraphMeshKafkaAutoConfiguration**

```kotlin
package com.agentwork.graphmesh.messaging.autoconfigure

import com.agentwork.graphmesh.messaging.GracefulShutdownCoordinator
import com.agentwork.graphmesh.messaging.MessageConsumerFactory
import com.agentwork.graphmesh.messaging.MessageProducerFactory
import com.agentwork.graphmesh.messaging.TopicRegistry
import com.agentwork.graphmesh.messaging.internal.DefaultTopicRegistry
import com.agentwork.graphmesh.messaging.internal.KafkaMessageConsumerFactory
import com.agentwork.graphmesh.messaging.internal.KafkaMessageProducerFactory
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.serializer.JsonSerializer

@Configuration
@ConditionalOnProperty(prefix = "graphmesh.messaging.kafka", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(GraphMeshKafkaProperties::class)
class GraphMeshKafkaAutoConfiguration(
    private val properties: GraphMeshKafkaProperties
) {

    @Bean
    fun graphMeshKafkaTemplate(objectMapper: ObjectMapper): KafkaTemplate<String, Any> {
        val producerProps = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to properties.bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java
        )
        val factory = DefaultKafkaProducerFactory<String, Any>(producerProps)
        return KafkaTemplate(factory)
    }

    @Bean
    fun graphMeshAdminClient(): AdminClient =
        AdminClient.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to properties.bootstrapServers))

    @Bean
    fun topicRegistry(adminClient: AdminClient): TopicRegistry =
        DefaultTopicRegistry(adminClient)

    @Bean
    fun gracefulShutdownCoordinator(): GracefulShutdownCoordinator =
        GracefulShutdownCoordinator(properties.gracefulShutdown)

    @Bean
    fun messageProducerFactory(
        kafkaTemplate: KafkaTemplate<String, Any>,
        objectMapper: ObjectMapper,
        shutdownCoordinator: GracefulShutdownCoordinator
    ): MessageProducerFactory =
        KafkaMessageProducerFactory(kafkaTemplate, objectMapper, shutdownCoordinator)

    @Bean
    fun messageConsumerFactory(
        objectMapper: ObjectMapper,
        shutdownCoordinator: GracefulShutdownCoordinator
    ): MessageConsumerFactory =
        KafkaMessageConsumerFactory(properties, objectMapper, shutdownCoordinator)

    @EventListener(ContextRefreshedEvent::class)
    fun onApplicationReady(topicRegistry: TopicRegistry) {
        if (properties.autoCreateTopics) {
            topicRegistry.ensureTopicsExist()
        }
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/messaging/internal/KafkaMessageProducerFactory.kt \
       src/main/kotlin/com/agentwork/graphmesh/messaging/internal/KafkaMessageConsumerFactory.kt \
       src/main/kotlin/com/agentwork/graphmesh/messaging/autoconfigure/GraphMeshKafkaAutoConfiguration.kt
git commit -m "feat(messaging): add factories and auto-configuration"
```

---

## Task 12: Full Test Suite Run + Update Feature Overview

**Files:**
- Modify: `docs/features/00-feature-set-overview.md`

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: All tests pass (KafkaTopicConfigTest, DefaultTopicRegistryTest, KafkaMessageProducerTest, KafkaMessageConsumerTest, ProducerConsumerRoundtripTest, GracefulShutdownCoordinatorTest, GraphMeshApplicationTests).

- [ ] **Step 2: Update feature overview with completion status**

In `docs/features/00-feature-set-overview.md`, update the Phase 1 table to mark Feature 01 as done. Change line:

```markdown
| 01 | Kafka Messaging Infrastructure | [01-kafka-messaging.md](01-kafka-messaging.md)                   | —             | L       |
```

to:

```markdown
| 01 | Kafka Messaging Infrastructure | [01-kafka-messaging.md](01-kafka-messaging.md)                   | —             | L       | ✅ Done |
```

Add a `Status` column header to the Phase 1 table:

```markdown
| #  | Feature                        | Datei                                                            | Abhaengig von | Aufwand | Status  |
|----|--------------------------------|------------------------------------------------------------------|---------------|---------|---------|
| 01 | Kafka Messaging Infrastructure | [01-kafka-messaging.md](01-kafka-messaging.md)                   | —             | L       | ✅ Done |
| 02 | Cassandra Storage Layer        | [02-cassandra-storage.md](02-cassandra-storage.md)               | —             | L       |         |
| 03 | S3/MinIO Blob Storage          | [03-s3-blob-storage.md](03-s3-blob-storage.md)                   | —             | M       |         |
| 04 | Qdrant Vector Store            | [04-qdrant-vector-store.md](04-qdrant-vector-store.md)           | —             | M       |         |
| 05 | LLM Provider Abstraction       | [05-llm-provider-abstraction.md](05-llm-provider-abstraction.md) | —             | L       |         |
| 06 | Configuration Service          | [06-configuration-service.md](06-configuration-service.md)       | 01, 02        | M       |         |
| 07 | RDF Graph Model                | [07-rdf-graph-model.md](07-rdf-graph-model.md)                   | 02            | L       |         |
```

Also add the `Status` column to Phase 2–5 tables (leave all empty).

- [ ] **Step 3: Commit**

```bash
git add docs/features/00-feature-set-overview.md
git commit -m "docs: mark Feature 01 (Kafka Messaging) as done in feature overview"
```
