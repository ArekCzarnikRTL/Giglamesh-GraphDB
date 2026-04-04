# Feature 01: Kafka Messaging Infrastructure — Design Spec (v2)

Vereinfachte Neuimplementierung: Direkte Spring Boot Kafka Integration mit Avro + Confluent Schema Registry + CloudEvents Envelope.

## Entscheidungen

| Entscheidung | Wahl | Begruendung |
|---|---|---|
| Ansatz | Direkte Spring Boot Auto-Config | Kein Wrapper, `KafkaTemplate` + `@KafkaListener` direkt nutzen |
| Serialisierung | Avro `GenericRecord` + Confluent Schema Registry | Schema-Evolution, binaeRes Format, Registry als zentrale Schema-Verwaltung |
| Envelope | CloudEvents (Binary Content Mode) | `ce_`-prefixed Kafka Headers gemaess Envelope-Spec |
| Konfiguration | `spring.kafka.*` Properties | Standard Spring Boot, keine eigenen Property-Klassen |
| Infrastruktur | docker-compose (Kafka + Schema Registry) | Lokale Entwicklung und Tests gegen echte Instanzen |
| Tests | Integration gegen docker-compose | Kein Testcontainers — Tests laufen gegen laufende Docker-Instanzen |
| Modul-Struktur | Spring Modulith Package | Passt zum bestehenden Single-Module-Setup |

## Was wird entfernt

Die gesamte bestehende Abstraktionsschicht:

- Interfaces: `MessageProducer`, `MessageConsumer`, `MessageProducerFactory`, `MessageConsumerFactory`, `TopicRegistry`
- Data Classes: `Message`, `KafkaTopicConfig`
- Object: `MessageHeaders`
- Implementierungen: `KafkaMessageProducer`, `KafkaMessageConsumer`, `DefaultTopicRegistry`
- Config: `GraphMeshKafkaProperties`, `GracefulShutdownCoordinator`
- Tests: Alle 5 bestehenden Test-Dateien + `AbstractKafkaIntegrationTest`
- Dependencies: `kotlinx-coroutines-*`, `testcontainers:kafka`, `testcontainers:junit-jupiter`

## Infrastruktur: docker-compose.yaml

```yaml
services:
  kafka:
    image: confluentinc/cp-kafka:7.9.0
    # KRaft-Mode (kein Zookeeper)
    # Port: localhost:9092

  schema-registry:
    image: confluentinc/cp-schema-registry:7.9.0
    # Port: localhost:8081
    # Verbunden mit Kafka
```

## Dependencies (build.gradle.kts)

```kotlin
// Neu
implementation("org.springframework.boot:spring-boot-starter-kafka")
implementation("org.apache.avro:avro")
implementation("io.confluent:kafka-avro-serializer")

// Confluent Maven Repository
repositories {
    maven("https://packages.confluent.io/maven/")
}

// Entfernt
// kotlinx-coroutines-core, kotlinx-coroutines-reactor (soweit nur fuer Kafka)
// testcontainers:kafka, testcontainers:junit-jupiter
// spring-kafka-test
// kotlinx-coroutines-test
```

## Konfiguration (application.yml)

```yaml
spring:
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

Spring Boot auto-konfiguriert `KafkaTemplate<String, GenericRecord>` und `ConsumerFactory` anhand dieser Properties. Keine eigenen `@Configuration`-Klassen fuer Factories noetig.

## CloudEvents Envelope Helper

Einzige Utility-Klasse — kein Interface, kein Framework:

```kotlin
package com.agentwork.graphmesh.messaging

object CloudEventHeaders {
    // MUST Headers
    const val ID = "ce_id"                       // UUID, auto-generiert
    const val SOURCE = "ce_source"               // Business-Kontext URI
    const val SPEC_VERSION = "ce_specversion"    // immer "1.0"
    const val TYPE = "ce_type"                   // Domain-Event-Typ, versioniert
    const val TIME = "ce_time"                   // RFC 3339 Business-Timestamp
    const val TRACEPARENT = "ce_traceparent"     // W3C Trace Context

    // SHOULD Headers
    const val CORRELATION_ID = "ce_correlationid"
    const val CAUSATION_ID = "ce_causationid"

    // MAY Headers
    const val CONTENT_TYPE = "content-type"      // kein ce_ prefix per Spec
    const val SUBJECT = "ce_subject"

    fun build(
        source: String,
        type: String,
        subject: String? = null,
        correlationId: String? = null,
        causationId: String? = null,
    ): Map<String, String> {
        // Erzeugt automatisch: ce_id (UUID), ce_time (RFC 3339), 
        // ce_specversion ("1.0"), ce_traceparent (neuer Span oder aus MDC)
        // + alle uebergebenen Parameter
    }

    fun extract(headers: org.apache.kafka.common.header.Headers): Map<String, String> {
        // Liest alle ce_* und content-type Headers als Map
    }
}
```

## Topic-Konfiguration

Via Spring Boot `NewTopic`-Beans (ersetzt `TopicRegistry` + `KafkaTopicConfig`):

```kotlin
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

## Avro Schema

`src/main/avro/document-ingested.avsc`:

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

Schema wird zur Laufzeit geladen. `GenericRecord` wird manuell aus dem Schema erzeugt — kein Codegen.

## Beispiel-Producer

```kotlin
@Service
class DocumentIngestedProducer(private val kafka: KafkaTemplate<String, GenericRecord>) {

    private val schema: Schema = Schema.Parser().parse(
        javaClass.getResourceAsStream("/avro/document-ingested.avsc")
    )

    fun send(documentId: String, fileName: String, mimeType: String, sizeBytes: Long) {
        val record = GenericData.Record(schema).apply {
            put("documentId", documentId)
            put("fileName", fileName)
            put("mimeType", mimeType)
            put("sizeBytes", sizeBytes)
        }
        val headers = CloudEventHeaders.build(
            source = "graphmesh/document-service",
            type = "graphmesh.document.ingested.v1",
            subject = documentId
        )
        val producerRecord = ProducerRecord(
            "graphmesh.document.ingested", null, documentId, record,
            headers.map { (k, v) -> RecordHeader(k, v.toByteArray()) }
        )
        kafka.send(producerRecord)
    }
}
```

## Beispiel-Consumer

```kotlin
@Component
class DocumentIngestedConsumer {

    private val logger = KotlinLogging.logger {}

    @KafkaListener(topics = ["graphmesh.document.ingested"], groupId = "graphmesh")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val ceHeaders = CloudEventHeaders.extract(record.headers())
        val documentId = record.value()["documentId"].toString()

        logger.info { "Document ingested: $documentId, type=${ceHeaders[CloudEventHeaders.TYPE]}" }
    }
}
```

## Dateistruktur

```
src/main/kotlin/.../messaging/
    CloudEventHeaders.kt            # Envelope Helper (build + extract)
    KafkaTopicConfig.kt             # NewTopic Beans
    DocumentIngestedProducer.kt     # Beispiel-Producer
    DocumentIngestedConsumer.kt     # Beispiel-Consumer

src/main/resources/avro/
    document-ingested.avsc          # Avro Schema

src/test/kotlin/.../messaging/
    CloudEventHeadersTest.kt        # Unit-Test: Header build/extract
    DocumentIngestedIntegrationTest.kt  # Integration gegen docker-compose

docker-compose.yaml                 # Kafka (KRaft) + Schema Registry
```

4 Produktionsdateien + 1 Schema + 2 Tests + docker-compose.

## Testing

| Test | Typ | Was wird getestet |
|---|---|---|
| `CloudEventHeadersTest` | Unit | `build()` erzeugt alle 6 MUST-Headers, `extract()` liest sie korrekt |
| `DocumentIngestedIntegrationTest` | Integration (docker-compose) | Producer sendet, Consumer empfaengt, Avro-Serialisierung via Schema Registry, CloudEvent-Headers End-to-End |

Tests setzen voraus, dass `docker-compose up` laeuft. `application-test.yml` zeigt auf `localhost:9092` / `localhost:8081`.

## CloudEvents Envelope Compliance

```
Envelope Compliance Check:
  [x] id            — UUID auto-generiert in build()
  [x] source        — uebergeben als Parameter
  [x] specversion   — hardcoded "1.0"
  [x] type          — uebergeben als Parameter, versioniert (.v1)
  [x] time          — RFC 3339 auto-generiert in build()
  [x] traceparent   — W3C Trace Context, propagiert oder neu erzeugt
  [x] correlationid (SHOULD) — optional in build()
  [x] causationid   (SHOULD) — optional in build()
  [x] subject       (MAY) — optional in build(), genutzt fuer documentId
  [x] content-type  (MAY) — "application/avro"
```

## Scope-Ausschluesse

- **Request/Reply-Pattern**: Spaeter bei Bedarf (fruehestens Feature 05)
- **Dead Letter Queue**: Nicht in Scope
- **Graceful Shutdown Coordinator**: Spring Boot managed Kafka-Shutdown nativ
- **Coroutines**: Nicht noetig — `@KafkaListener` und `KafkaTemplate.send()` sind ausreichend
