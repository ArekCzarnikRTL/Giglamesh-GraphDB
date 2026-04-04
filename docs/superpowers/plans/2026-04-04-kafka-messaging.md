# Kafka Messaging (Simplified) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the existing Kafka abstraction layer with direct Spring Boot Kafka + Avro GenericRecord + Confluent Schema Registry + CloudEvents envelope headers.

**Architecture:** Standard Spring Boot auto-configured `KafkaTemplate<String, GenericRecord>` and `@KafkaListener` for producing/consuming. Avro schemas stored as `.avsc` files, serialized via Confluent `KafkaAvroSerializer`. CloudEvents metadata transported in Kafka headers with `ce_` prefix (binary content mode). Infrastructure runs via docker-compose (Kafka KRaft + Schema Registry).

**Tech Stack:** Spring Boot 4.0.5, Spring Kafka, Apache Avro, Confluent Schema Registry, Confluent KafkaAvroSerializer/Deserializer, Docker Compose

---

## File Structure

### Created

| File | Responsibility |
|---|---|
| `docker-compose.yaml` | Kafka (KRaft) + Schema Registry infrastructure |
| `src/main/resources/avro/document-ingested.avsc` | Avro schema for example event |
| `src/main/kotlin/.../messaging/CloudEventHeaders.kt` | CloudEvents envelope helper (build + extract) |
| `src/main/kotlin/.../messaging/KafkaTopicConfig.kt` | `NewTopic` bean definitions |
| `src/main/kotlin/.../messaging/DocumentIngestedProducer.kt` | Example producer using KafkaTemplate |
| `src/main/kotlin/.../messaging/DocumentIngestedConsumer.kt` | Example consumer using @KafkaListener |
| `src/test/kotlin/.../messaging/CloudEventHeadersTest.kt` | Unit tests for header build/extract |
| `src/test/kotlin/.../messaging/DocumentIngestedIntegrationTest.kt` | Integration test against docker-compose |
| `src/test/resources/application-test.yml` | Test profile config pointing to docker-compose |

### Modified

| File | Change |
|---|---|
| `build.gradle.kts` | Add Avro + Confluent deps, remove coroutines/testcontainers deps, add Confluent repo |
| `src/main/resources/application.yml` | Replace `graphmesh.messaging.kafka.*` with `spring.kafka.*` |
| `docs/features/01-kafka-messaging.md` | Update to reflect simplified implementation |

### Deleted

| File | Reason |
|---|---|
| `src/main/kotlin/.../messaging/Message.kt` | Replaced by direct `ConsumerRecord<String, GenericRecord>` |
| `src/main/kotlin/.../messaging/MessageHeaders.kt` | Replaced by `CloudEventHeaders` |
| `src/main/kotlin/.../messaging/MessageProducer.kt` | No more abstraction layer |
| `src/main/kotlin/.../messaging/MessageConsumer.kt` | No more abstraction layer |
| `src/main/kotlin/.../messaging/MessageProducerFactory.kt` | No more abstraction layer |
| `src/main/kotlin/.../messaging/MessageConsumerFactory.kt` | No more abstraction layer |
| `src/main/kotlin/.../messaging/TopicRegistry.kt` | Replaced by `NewTopic` beans |
| `src/main/kotlin/.../messaging/internal/KafkaMessageProducer.kt` | No more abstraction layer |
| `src/main/kotlin/.../messaging/internal/KafkaMessageConsumer.kt` | No more abstraction layer |
| `src/main/kotlin/.../messaging/internal/DefaultTopicRegistry.kt` | Replaced by `NewTopic` beans |
| `src/main/kotlin/.../messaging/autoconfigure/GraphMeshKafkaProperties.kt` | Replaced by `spring.kafka.*` |
| `src/test/kotlin/.../messaging/AbstractKafkaIntegrationTest.kt` | Testcontainers removed |
| `src/test/kotlin/.../messaging/KafkaTopicConfigTest.kt` | Old KafkaTopicConfig removed |
| `src/test/kotlin/.../messaging/DefaultTopicRegistryTest.kt` | TopicRegistry removed |
| `src/test/kotlin/.../messaging/KafkaMessageProducerTest.kt` | Old producer removed |
| `src/test/kotlin/.../messaging/KafkaMessageConsumerTest.kt` | Old consumer removed |

All paths below use `...` as shorthand for `com/agentwork/graphmesh`.

---

### Task 1: docker-compose.yaml

**Files:**
- Create: `docker-compose.yaml`

- [ ] **Step 1: Create docker-compose.yaml**

```yaml
services:
  kafka:
    image: confluentinc/cp-kafka:7.9.0
    hostname: kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,HOST:PLAINTEXT
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,HOST://localhost:9092
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:29092,CONTROLLER://0.0.0.0:9093,HOST://0.0.0.0:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qk

  schema-registry:
    image: confluentinc/cp-schema-registry:7.9.0
    hostname: schema-registry
    depends_on:
      - kafka
    ports:
      - "8081:8081"
    environment:
      SCHEMA_REGISTRY_HOST_NAME: schema-registry
      SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS: kafka:29092
      SCHEMA_REGISTRY_LISTENERS: http://0.0.0.0:8081
```

- [ ] **Step 2: Verify docker-compose starts**

Run: `docker compose up -d && sleep 10 && docker compose ps`
Expected: Both `kafka` and `schema-registry` containers running and healthy.

- [ ] **Step 3: Verify Schema Registry is reachable**

Run: `curl -s http://localhost:8081/subjects | head`
Expected: `[]` (empty array — no schemas registered yet)

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yaml
git commit -m "infra: add docker-compose with Kafka KRaft and Confluent Schema Registry"
```

---

### Task 2: Delete old implementation and update build.gradle.kts

**Files:**
- Delete: All 11 source files + 5 test files listed in "Deleted" table above
- Modify: `build.gradle.kts`

- [ ] **Step 1: Delete all old messaging source files**

```bash
rm -rf src/main/kotlin/com/agentwork/graphmesh/messaging/
rm -rf src/test/kotlin/com/agentwork/graphmesh/messaging/
```

This removes all 11 source files (including `internal/` and `autoconfigure/` subdirs) and all 5 test files.

- [ ] **Step 2: Update build.gradle.kts**

Replace the `repositories` block:

```kotlin
repositories {
    mavenCentral()
    maven("https://packages.confluent.io/maven/")
}
```

Replace the `dependencies` block:

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-cassandra")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-qdrant")
    implementation("org.springframework.ai:spring-ai-tika-document-reader")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.apache.avro:avro:1.12.0")
    implementation("io.confluent:kafka-avro-serializer:7.9.0")
    testImplementation("org.springframework.boot:spring-boot-starter-cassandra-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

Changes vs. old:
- Added: `org.apache.avro:avro:1.12.0`, `io.confluent:kafka-avro-serializer:7.9.0`
- Removed: `spring-kafka-test`, `kotlinx-coroutines-core`, `kotlinx-coroutines-reactor`, `testcontainers:kafka`, `testcontainers:junit-jupiter`, `kotlinx-coroutines-test`

Remove the testcontainers BOM from `dependencyManagement`:

```kotlin
dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL (compile passes, tests skipped since we deleted them)

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(messaging): remove old Kafka abstraction layer, add Avro + Confluent deps"
```

---

### Task 3: application.yml and test profile

**Files:**
- Modify: `src/main/resources/application.yml`
- Create: `src/test/resources/application-test.yml`

- [ ] **Step 1: Replace application.yml Kafka config**

Replace the entire content of `src/main/resources/application.yml` with:

```yaml
spring:
  application:
    name: GraphMesh
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
    consumer:
      group-id: graphmesh
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: io.confluent.kafka.serializers.KafkaAvroDeserializer
    properties:
      schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8081}
```

- [ ] **Step 2: Create test profile**

Create `src/test/resources/application-test.yml`:

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    properties:
      schema.registry.url: http://localhost:8081
```

- [ ] **Step 3: Verify build still compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.yml src/test/resources/application-test.yml
git commit -m "config: replace custom Kafka properties with spring.kafka auto-config + Avro"
```

---

### Task 4: CloudEventHeaders utility

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/CloudEventHeaders.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/messaging/CloudEventHeadersTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/agentwork/graphmesh/messaging/CloudEventHeadersTest.kt`:

```kotlin
package com.agentwork.graphmesh.messaging

import org.apache.kafka.common.header.internals.RecordHeaders
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CloudEventHeadersTest {

    @Test
    fun `build produces all six MUST headers`() {
        val headers = CloudEventHeaders.build(
            source = "graphmesh/document-service",
            type = "graphmesh.document.ingested.v1"
        )

        assertNotNull(headers[CloudEventHeaders.ID])
        assertTrue(runCatching { UUID.fromString(headers[CloudEventHeaders.ID]) }.isSuccess)
        assertEquals("graphmesh/document-service", headers[CloudEventHeaders.SOURCE])
        assertEquals("1.0", headers[CloudEventHeaders.SPEC_VERSION])
        assertEquals("graphmesh.document.ingested.v1", headers[CloudEventHeaders.TYPE])
        assertNotNull(headers[CloudEventHeaders.TIME])
        assertTrue(runCatching { Instant.parse(headers[CloudEventHeaders.TIME]) }.isSuccess)
        assertNotNull(headers[CloudEventHeaders.TRACEPARENT])
    }

    @Test
    fun `build includes optional headers when provided`() {
        val headers = CloudEventHeaders.build(
            source = "graphmesh/document-service",
            type = "graphmesh.document.ingested.v1",
            subject = "doc-123",
            correlationId = "corr-456",
            causationId = "cause-789"
        )

        assertEquals("doc-123", headers[CloudEventHeaders.SUBJECT])
        assertEquals("corr-456", headers[CloudEventHeaders.CORRELATION_ID])
        assertEquals("cause-789", headers[CloudEventHeaders.CAUSATION_ID])
        assertEquals("application/avro", headers[CloudEventHeaders.CONTENT_TYPE])
    }

    @Test
    fun `build omits optional headers when not provided`() {
        val headers = CloudEventHeaders.build(
            source = "graphmesh/document-service",
            type = "graphmesh.document.ingested.v1"
        )

        assertTrue(CloudEventHeaders.SUBJECT !in headers)
        assertTrue(CloudEventHeaders.CORRELATION_ID !in headers)
        assertTrue(CloudEventHeaders.CAUSATION_ID !in headers)
    }

    @Test
    fun `extract reads ce_ headers from Kafka Headers`() {
        val kafkaHeaders = RecordHeaders()
        kafkaHeaders.add("ce_id", "test-id".toByteArray())
        kafkaHeaders.add("ce_source", "test-source".toByteArray())
        kafkaHeaders.add("ce_specversion", "1.0".toByteArray())
        kafkaHeaders.add("ce_type", "test.type.v1".toByteArray())
        kafkaHeaders.add("ce_time", "2026-04-04T12:00:00Z".toByteArray())
        kafkaHeaders.add("ce_traceparent", "00-abc-def-01".toByteArray())
        kafkaHeaders.add("content-type", "application/avro".toByteArray())

        val extracted = CloudEventHeaders.extract(kafkaHeaders)

        assertEquals("test-id", extracted[CloudEventHeaders.ID])
        assertEquals("test-source", extracted[CloudEventHeaders.SOURCE])
        assertEquals("1.0", extracted[CloudEventHeaders.SPEC_VERSION])
        assertEquals("test.type.v1", extracted[CloudEventHeaders.TYPE])
        assertEquals("2026-04-04T12:00:00Z", extracted[CloudEventHeaders.TIME])
        assertEquals("00-abc-def-01", extracted[CloudEventHeaders.TRACEPARENT])
        assertEquals("application/avro", extracted[CloudEventHeaders.CONTENT_TYPE])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.CloudEventHeadersTest" --info 2>&1 | tail -20`
Expected: FAIL — `CloudEventHeaders` does not exist yet.

- [ ] **Step 3: Implement CloudEventHeaders**

Create `src/main/kotlin/com/agentwork/graphmesh/messaging/CloudEventHeaders.kt`:

```kotlin
package com.agentwork.graphmesh.messaging

import org.apache.kafka.common.header.Headers
import java.time.Instant
import java.util.UUID

object CloudEventHeaders {
    const val ID = "ce_id"
    const val SOURCE = "ce_source"
    const val SPEC_VERSION = "ce_specversion"
    const val TYPE = "ce_type"
    const val TIME = "ce_time"
    const val TRACEPARENT = "ce_traceparent"
    const val CORRELATION_ID = "ce_correlationid"
    const val CAUSATION_ID = "ce_causationid"
    const val CONTENT_TYPE = "content-type"
    const val SUBJECT = "ce_subject"

    private val CE_HEADERS = setOf(
        ID, SOURCE, SPEC_VERSION, TYPE, TIME, TRACEPARENT,
        CORRELATION_ID, CAUSATION_ID, CONTENT_TYPE, SUBJECT
    )

    fun build(
        source: String,
        type: String,
        subject: String? = null,
        correlationId: String? = null,
        causationId: String? = null,
    ): Map<String, String> {
        val headers = mutableMapOf(
            ID to UUID.randomUUID().toString(),
            SOURCE to source,
            SPEC_VERSION to "1.0",
            TYPE to type,
            TIME to Instant.now().toString(),
            TRACEPARENT to generateTraceparent(),
            CONTENT_TYPE to "application/avro",
        )
        subject?.let { headers[SUBJECT] = it }
        correlationId?.let { headers[CORRELATION_ID] = it }
        causationId?.let { headers[CAUSATION_ID] = it }
        return headers
    }

    fun extract(headers: Headers): Map<String, String> =
        headers
            .filter { it.key() in CE_HEADERS }
            .associate { it.key() to String(it.value()) }

    private fun generateTraceparent(): String {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        return "00-$traceId-$spanId-01"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.CloudEventHeadersTest" --info 2>&1 | tail -20`
Expected: PASS — all 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/messaging/CloudEventHeaders.kt \
        src/test/kotlin/com/agentwork/graphmesh/messaging/CloudEventHeadersTest.kt
git commit -m "feat(messaging): add CloudEventHeaders utility with build and extract"
```

---

### Task 5: Avro schema + Topic config + Producer

**Files:**
- Create: `src/main/resources/avro/document-ingested.avsc`
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/KafkaTopicConfig.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedProducer.kt`

- [ ] **Step 1: Create Avro schema**

Create `src/main/resources/avro/document-ingested.avsc`:

```json
{
  "type": "record",
  "name": "DocumentIngested",
  "namespace": "com.agentwork.graphmesh.events",
  "fields": [
    {"name": "documentId", "type": "string"},
    {"name": "fileName", "type": "string"},
    {"name": "mimeType", "type": "string"},
    {"name": "sizeBytes", "type": "long"}
  ]
}
```

- [ ] **Step 2: Create KafkaTopicConfig**

Create `src/main/kotlin/com/agentwork/graphmesh/messaging/KafkaTopicConfig.kt`:

```kotlin
package com.agentwork.graphmesh.messaging

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class KafkaTopicConfig {

    @Bean
    fun documentIngestedTopic(): NewTopic =
        TopicBuilder.name("graphmesh.document.ingested")
            .partitions(3)
            .replicas(1)
            .build()
}
```

- [ ] **Step 3: Create DocumentIngestedProducer**

Create `src/main/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedProducer.kt`:

```kotlin
package com.agentwork.graphmesh.messaging

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class DocumentIngestedProducer(private val kafka: KafkaTemplate<String, GenericRecord>) {

    private val schema: Schema = Schema.Parser().parse(
        javaClass.getResourceAsStream("/avro/document-ingested.avsc")
    )

    companion object {
        const val TOPIC = "graphmesh.document.ingested"
        const val SOURCE = "graphmesh/document-service"
        const val TYPE = "graphmesh.document.ingested.v1"
    }

    fun send(documentId: String, fileName: String, mimeType: String, sizeBytes: Long) {
        val record = GenericData.Record(schema).apply {
            put("documentId", documentId)
            put("fileName", fileName)
            put("mimeType", mimeType)
            put("sizeBytes", sizeBytes)
        }
        val headers = CloudEventHeaders.build(
            source = SOURCE,
            type = TYPE,
            subject = documentId
        )
        val kafkaHeaders = headers.map { (k, v) ->
            RecordHeader(k, v.toByteArray()) as org.apache.kafka.common.header.Header
        }
        val producerRecord = ProducerRecord(TOPIC, null, documentId, record, kafkaHeaders)
        kafka.send(producerRecord)
    }
}
```

- [ ] **Step 4: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/avro/document-ingested.avsc \
        src/main/kotlin/com/agentwork/graphmesh/messaging/KafkaTopicConfig.kt \
        src/main/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedProducer.kt
git commit -m "feat(messaging): add Avro schema, topic config, and DocumentIngestedProducer"
```

---

### Task 6: Consumer

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedConsumer.kt`

- [ ] **Step 1: Create DocumentIngestedConsumer**

Create `src/main/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedConsumer.kt`:

```kotlin
package com.agentwork.graphmesh.messaging

import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class DocumentIngestedConsumer {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.document.ingested"], groupId = "graphmesh")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val ceHeaders = CloudEventHeaders.extract(record.headers())
        val documentId = record.value()["documentId"].toString()
        val fileName = record.value()["fileName"].toString()

        logger.info(
            "Document ingested: documentId={}, fileName={}, eventType={}",
            documentId, fileName, ceHeaders[CloudEventHeaders.TYPE]
        )
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedConsumer.kt
git commit -m "feat(messaging): add DocumentIngestedConsumer with @KafkaListener"
```

---

### Task 7: Integration test

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedIntegrationTest.kt`

Prerequisite: `docker compose up -d` must be running.

- [ ] **Step 1: Write integration test**

Create `src/test/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedIntegrationTest.kt`:

```kotlin
package com.agentwork.graphmesh.messaging

import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
class DocumentIngestedIntegrationTest {

    @Autowired
    lateinit var producer: DocumentIngestedProducer

    @Test
    fun `producer sends message that can be consumed with correct Avro payload`() {
        val documentId = UUID.randomUUID().toString()
        val fileName = "test-document.pdf"
        val mimeType = "application/pdf"
        val sizeBytes = 1024L

        producer.send(documentId, fileName, mimeType, sizeBytes)

        val consumerProps = mapOf(
            "bootstrap.servers" to "localhost:9092",
            "group.id" to "test-${UUID.randomUUID()}",
            "auto.offset.reset" to "earliest",
            "schema.registry.url" to "http://localhost:8081",
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "io.confluent.kafka.serializers.KafkaAvroDeserializer"
        )
        val consumerFactory = org.springframework.kafka.core.DefaultKafkaConsumerFactory<String, GenericRecord>(consumerProps)
        val consumer = consumerFactory.createConsumer()
        consumer.subscribe(listOf(DocumentIngestedProducer.TOPIC))

        val records = mutableListOf<ConsumerRecord<String, GenericRecord>>()
        val deadline = Instant.now().plusSeconds(30)
        while (records.isEmpty() && Instant.now().isBefore(deadline)) {
            val polled = consumer.poll(Duration.ofSeconds(1))
            polled.forEach { records.add(it) }
        }
        consumer.close()

        assertTrue(records.isNotEmpty(), "Expected at least one message")
        val record = records.first()

        assertEquals(documentId, record.value()["documentId"].toString())
        assertEquals(fileName, record.value()["fileName"].toString())
        assertEquals(mimeType, record.value()["mimeType"].toString())
        assertEquals(sizeBytes, record.value()["sizeBytes"] as Long)
        assertEquals(documentId, record.key())
    }

    @Test
    fun `producer sends correct CloudEvent headers`() {
        val documentId = UUID.randomUUID().toString()

        producer.send(documentId, "test.pdf", "application/pdf", 512L)

        val consumerProps = mapOf(
            "bootstrap.servers" to "localhost:9092",
            "group.id" to "test-headers-${UUID.randomUUID()}",
            "auto.offset.reset" to "earliest",
            "schema.registry.url" to "http://localhost:8081",
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "io.confluent.kafka.serializers.KafkaAvroDeserializer"
        )
        val consumerFactory = org.springframework.kafka.core.DefaultKafkaConsumerFactory<String, GenericRecord>(consumerProps)
        val consumer = consumerFactory.createConsumer()
        consumer.subscribe(listOf(DocumentIngestedProducer.TOPIC))

        val records = mutableListOf<ConsumerRecord<String, GenericRecord>>()
        val deadline = Instant.now().plusSeconds(30)
        while (records.isEmpty() && Instant.now().isBefore(deadline)) {
            val polled = consumer.poll(Duration.ofSeconds(1))
            polled.forEach { records.add(it) }
        }
        consumer.close()

        assertTrue(records.isNotEmpty(), "Expected at least one message")
        val ceHeaders = CloudEventHeaders.extract(records.first().headers())

        assertNotNull(ceHeaders[CloudEventHeaders.ID])
        assertEquals(DocumentIngestedProducer.SOURCE, ceHeaders[CloudEventHeaders.SOURCE])
        assertEquals("1.0", ceHeaders[CloudEventHeaders.SPEC_VERSION])
        assertEquals(DocumentIngestedProducer.TYPE, ceHeaders[CloudEventHeaders.TYPE])
        assertNotNull(ceHeaders[CloudEventHeaders.TIME])
        assertNotNull(ceHeaders[CloudEventHeaders.TRACEPARENT])
        assertEquals(documentId, ceHeaders[CloudEventHeaders.SUBJECT])
        assertEquals("application/avro", ceHeaders[CloudEventHeaders.CONTENT_TYPE])
    }
}
```

- [ ] **Step 2: Ensure docker-compose is running**

Run: `docker compose up -d && sleep 5 && docker compose ps`
Expected: Both kafka and schema-registry running.

- [ ] **Step 3: Run integration tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.DocumentIngestedIntegrationTest" --info 2>&1 | tail -30`
Expected: PASS — both tests green.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedIntegrationTest.kt
git commit -m "test(messaging): add integration tests for producer, Avro payload, and CloudEvent headers"
```

---

### Task 8: Update feature documentation

**Files:**
- Modify: `docs/features/01-kafka-messaging.md`

- [ ] **Step 1: Read current feature doc**

Read `docs/features/01-kafka-messaging.md` to understand current structure.

- [ ] **Step 2: Update feature doc**

Update the document to reflect the simplified implementation. Key changes:
- Replace "type-safe Producer/Consumer API with generics" with "direct Spring Boot KafkaTemplate + @KafkaListener"
- Replace JSON serialization references with Avro GenericRecord + Confluent Schema Registry
- Replace custom headers (`X-Correlation-Id` etc.) with CloudEvents envelope (`ce_` headers)
- Remove Factories, TopicRegistry, coroutines references
- Remove Graceful Shutdown Coordinator (Spring Boot handles natively)
- Update acceptance criteria to match new implementation
- Keep the topic naming convention `graphmesh.<domain>.<action>`
- Keep the general architecture/pipeline description

Preserve the document's existing structure and language (German). Update content, don't rewrite from scratch.

- [ ] **Step 3: Commit**

```bash
git add docs/features/01-kafka-messaging.md
git commit -m "docs(messaging): update feature spec for simplified Kafka + Avro implementation"
```

---

### Task 9: Final verification

- [ ] **Step 1: Ensure docker-compose is running**

Run: `docker compose ps`
Expected: Both containers running.

- [ ] **Step 2: Run full build with tests**

Run: `./gradlew build 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Verify file count**

Run: `find src/main/kotlin/com/agentwork/graphmesh/messaging -name "*.kt" | sort && find src/test/kotlin/com/agentwork/graphmesh/messaging -name "*.kt" | sort`

Expected:
```
src/main/kotlin/.../messaging/CloudEventHeaders.kt
src/main/kotlin/.../messaging/DocumentIngestedConsumer.kt
src/main/kotlin/.../messaging/DocumentIngestedProducer.kt
src/main/kotlin/.../messaging/KafkaTopicConfig.kt
src/test/kotlin/.../messaging/CloudEventHeadersTest.kt
src/test/kotlin/.../messaging/DocumentIngestedIntegrationTest.kt
```

4 production files + 2 test files. Done.
