# Feature 01: Kafka Messaging Infrastructure

## Problem

GraphMesh benoetigt eine einheitliche Messaging-Infrastruktur fuer die asynchrone Kommunikation zwischen Modulen.
Ohne standardisierte Konventionen fuer Serialisierung, Header und Topic-Benennung entsteht inkonsistenter Code
ueber die verschiedenen Producer und Consumer hinweg.

## Ziel

Bereitstellung einer Spring-Boot-nativen Kafka-Integration mit Avro-Serialisierung ueber Confluent Schema Registry
und CloudEvents-konformen Headern.

1. **KafkaTemplate + @KafkaListener** -- Direkter Einsatz der Spring-Boot-Kafka-Abstraktion ohne eigene Wrapper-Interfaces
2. **Avro GenericRecord + Schema Registry** -- Schemaversionierte Serialisierung ueber Confluent Schema Registry
3. **CloudEvents-Header** -- Standardisierte Metadaten nach CloudEvents Kafka Protocol Binding (Binary Content Mode, `ce_`-Prefix)
4. **Topic-Naming-Konvention** -- Standardisiertes Benennungsschema `graphmesh.<domain>.<action>` fuer alle Topics
5. **NewTopic-Beans** -- Deklarative Topic-Erstellung ueber Spring-Beans

## Voraussetzungen

| Abhaengigkeit                    | Status     | Blocker? |
|----------------------------------|------------|----------|
| Apache Kafka (Broker)            | Verfuegbar | Nein     |
| Confluent Schema Registry        | Verfuegbar | Nein     |
| Spring Boot 4.x                  | Verfuegbar | Nein     |
| Spring Kafka                     | Verfuegbar | Nein     |
| Apache Avro                      | Verfuegbar | Nein     |
| docker-compose (Infrastruktur)   | Verfuegbar | Nein     |

## Architektur

### Topic-Naming-Konvention

Alle Kafka-Topics folgen dem Schema `graphmesh.<domain>.<action>`:

| Topic                            | Beschreibung                          |
|----------------------------------|---------------------------------------|
| `graphmesh.config.push`          | Konfigurationsaenderungen broadcasten |
| `graphmesh.document.uploaded`    | Neues Dokument hochgeladen            |
| `graphmesh.document.ingested`    | Dokument aufgenommen                  |
| `graphmesh.document.chunked`     | Dokument in Chunks zerlegt            |
| `graphmesh.extraction.completed` | Extraktion abgeschlossen              |
| `graphmesh.embedding.requested`  | Embedding-Generierung angefordert     |

### Topic-Konfiguration

Topics werden als Spring-Beans deklariert. Spring Boot erstellt diese beim Start automatisch via `KafkaAdmin`:

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

### CloudEvents-Header (Binary Content Mode)

Alle Nachrichten tragen CloudEvents-Metadaten als Kafka-Header (`ce_`-Prefix) gemaess der
[CloudEvents Kafka Protocol Binding](https://github.com/cloudevents/spec/blob/main/cloudevents/bindings/kafka-protocol-binding.md).
Ein `CloudEventHeaders`-Utility-Objekt kapselt Erzeugung und Extraktion:

```kotlin
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

    fun build(
        source: String,
        type: String,
        subject: String? = null,
        correlationId: String? = null,
        causationId: String? = null,
    ): Map<String, String> { /* ... */ }

    fun extract(headers: Headers): Map<String, String> { /* ... */ }
}
```

### Producer-Beispiel

Jeder Producer ist ein `@Service`, der `KafkaTemplate<String, GenericRecord>` injiziert bekommt.
Das Avro-Schema wird aus einer `.avsc`-Datei geladen:

```kotlin
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
        val headers = CloudEventHeaders.build(source = SOURCE, type = TYPE, subject = documentId)
        val kafkaHeaders = headers.map { (k, v) ->
            RecordHeader(k, v.toByteArray()) as Header
        }
        kafka.send(ProducerRecord(TOPIC, null, documentId, record, kafkaHeaders))
    }
}
```

### Consumer-Beispiel

Consumer nutzen `@KafkaListener` mit direktem Zugriff auf `ConsumerRecord<String, GenericRecord>`:

```kotlin
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

### application.yml Beispiel

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: graphmesh
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: io.confluent.kafka.serializers.KafkaAvroDeserializer
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
    properties:
      schema.registry.url: http://localhost:8081
      specific.avro.reader: false
```

## Betroffene Dateien

### Backend

| Datei                                                                                     | Aenderung                                      |
|-------------------------------------------------------------------------------------------|-------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/messaging/CloudEventHeaders.kt`                 | NEU - CloudEvents-Header-Utility                |
| `src/main/kotlin/com/agentwork/graphmesh/messaging/KafkaTopicConfig.kt`                  | NEU - Topic-Beans (@Configuration)              |
| `src/main/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedProducer.kt`          | NEU - Producer mit KafkaTemplate + Avro         |
| `src/main/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedConsumer.kt`          | NEU - Consumer mit @KafkaListener + Avro        |
| `src/main/resources/avro/document-ingested.avsc`                                         | NEU - Avro-Schema fuer document.ingested        |
| `docker-compose.yml`                                                                     | GEAENDERT - Kafka + Schema Registry hinzugefuegt |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                     | Aenderung                                        |
|-------------------------------------------------------------------------------------------|--------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedProducerTest.kt`      | NEU - Producer-Tests                             |
| `src/test/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedConsumerTest.kt`      | NEU - Consumer-Tests                             |
| `src/test/kotlin/com/agentwork/graphmesh/messaging/CloudEventHeadersTest.kt`             | NEU - Header-Utility-Tests                       |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                     |
|-------------------|-------------|-------------------------------------------|
| Spring Boot (JVM) | Ja          | Spring Kafka bietet volle Unterstuetzung  |
| KMP Library       | Nein        | Kafka-Client ist JVM-only                 |
| Ktor/Wasm         | Nein        | Kein Kafka-Client fuer Wasm/JS verfuegbar |

## Akzeptanzkriterien

- [ ] `KafkaTemplate<String, GenericRecord>` sendet Avro-Nachrichten an konfigurierte Kafka-Topics
- [ ] `@KafkaListener` empfaengt und deserialisiert Avro-GenericRecords korrekt
- [ ] Avro-Schemas werden in der Confluent Schema Registry registriert und versioniert
- [ ] CloudEvents-Header (`ce_`-Prefix) werden korrekt gesetzt und beim Empfang extrahiert
- [ ] Topic-Naming folgt der Konvention `graphmesh.<domain>.<action>`
- [ ] Topics werden deklarativ ueber `NewTopic`-Beans erstellt
- [ ] Spring Boot Auto-Configuration fuer Kafka funktioniert ueber `application.yml`
- [ ] Infrastruktur (Kafka, Schema Registry) laeuft via docker-compose
- [ ] Bestehende Funktionalitaet bleibt unberuehrt
