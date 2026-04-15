# Feature 01: Kafka Messaging Infrastructure — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/messaging/CloudEventHeaders.kt`** — Utility-Objekt mit Konstanten fuer alle CloudEvents-v1.0-Header (`ce_id`, `ce_source`, `ce_specversion`, `ce_type`, `ce_time`, `ce_traceparent`, `ce_correlationid`, `ce_causationid`, `ce_subject`, `content-type`). `build(...)` erzeugt die Pflicht-Header inkl. zufaelliger `ce_id` (UUID), aktuellem `ce_time` (`Instant.now()`) und synthetisiertem `ce_traceparent` im W3C-Format `00-<traceId>-<spanId>-01`. `extract(Headers)` filtert die `ce_`-Header aus den Kafka-Headern zurueck in eine `Map<String, String>`.
- **`src/main/kotlin/com/agentwork/graphmesh/messaging/KafkaTopicConfig.kt`** — `@Configuration` mit zwei `NewTopic`-Beans ueber `TopicBuilder`: `graphmesh.document.ingested` und `graphmesh.query.explained` (jeweils 3 Partitionen, Replikationsfaktor 1). Weitere Topics (chunk/page/config/collection) werden in anderen Modulen registriert, folgen aber demselben Schema.
- **`src/main/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedProducer.kt`** — `@Service` mit `KafkaTemplate<String, GenericRecord>`. Laedt Avro-Schema aus `/avro/document-ingested.avsc`, baut `GenericData.Record`, erzeugt CloudEvents-Header via `CloudEventHeaders.build(...)` mit `ce_source=graphmesh/document-service` und `ce_type=graphmesh.document.ingested.v1`, Dokument-ID wird sowohl als Kafka-Key als auch als `ce_subject` gesetzt. Failure-Logging via `whenComplete`.
- **`src/main/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedConsumer.kt`** — `@Component` mit `@KafkaListener(topics = ["graphmesh.document.ingested"], groupId = "graphmesh")`. Liest `ConsumerRecord<String, GenericRecord>`, extrahiert CloudEvents-Header und loggt `documentId`, `fileName`, `eventType`.
- **`src/main/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventProducer.kt` / `ExplainabilityEventConsumer.kt`** — analoger Producer/Consumer fuer `graphmesh.query.explained` (als zusaetzliches Beispiel fuer die Konvention).
- **`src/main/resources/avro/document-ingested.avsc`** — Avro-Schema mit Feldern `documentId`, `fileName`, `mimeType`, `sizeBytes`. Weitere Schemas (`chunk-created.avsc`, `page-extracted.avsc`, `config-changed.avsc`, `collection-lifecycle.avsc`, `query-explained.avsc`) liegen daneben und folgen derselben Konvention.
- **`src/main/resources/application.yml`** — Kafka-Auto-Config: `bootstrap-servers`, `io.confluent.kafka.serializers.KafkaAvroSerializer`/`-Deserializer`, `schema.registry.url`, `specific.avro.reader: false`, Consumer-Group `graphmesh`.

### Tests

- **`CloudEventHeadersTest`** — 4 Unit-Tests: Pflicht-Header vorhanden/korrekt, optionale Header, Weglassen bei Abwesenheit, `extract` liest `ce_`-Header korrekt zurueck.
- **`DocumentIngestedIntegrationTest`** — 2 Integrationstests gegen laufendes Kafka + Schema Registry (via docker-compose): Avro-Roundtrip (Key=documentId, Felder im `GenericRecord`) und CloudEvents-Header-Roundtrip.
- **`ExplainabilityEventProducerTest`, `ExplainabilityEventConsumerTest`** — analoge Tests fuer den Query-Explained-Pfad.

## Abweichungen vom Feature-Dokument

- **Content-Type-Header**: Spec nennt `content-type` nur als Konstante; `CloudEventHeaders.build()` setzt ihn fest auf `application/avro` (kein Parameter). Das ist der einzige unterstuetzte Wert und vereinfacht die Produzenten.
- **`ce_traceparent` synthetisiert**: Der Header wird intern mit zufaelliger Trace-/Span-ID erzeugt, nicht aus einem OpenTelemetry-Span uebernommen. Keine echte Trace-Propagation.
- **Keine `DocumentIngestedProducerTest`-Datei**: Spec listet einen expliziten Producer-Unit-Test; in der Praxis deckt `DocumentIngestedIntegrationTest` Producer und Consumer im Zusammenspiel ab.
- **Zusaetzliche Topics/Producer ausserhalb `KafkaTopicConfig`**: Topics wie `graphmesh.chunk.created`, `graphmesh.page.extracted`, `graphmesh.config.push`, `graphmesh.collection.*` werden in den jeweiligen Modulen (extraction, config, collection) deklariert — nicht zentral in `KafkaTopicConfig`. Konvention bleibt aber einheitlich.
- **Memory-Hinweis**: Feature-Spec ist an dieser Stelle genau; die realen Package-Pfade (`com.agentwork.graphmesh.messaging`) stimmen mit der Spec ueberein — ausnahmsweise keine Package-Abweichung.

## Akzeptanzkriterien

- [x] `KafkaTemplate<String, GenericRecord>` sendet Avro-Nachrichten — siehe `DocumentIngestedProducer.send(...)`.
- [x] `@KafkaListener` empfaengt und deserialisiert Avro-GenericRecords — siehe `DocumentIngestedConsumer`.
- [x] Avro-Schemas in Confluent Schema Registry — `schema.registry.url` in `application.yml`, Serializer `KafkaAvroSerializer`.
- [x] CloudEvents-Header korrekt gesetzt und extrahiert — `CloudEventHeaders.build/extract`, verifiziert in `CloudEventHeadersTest` und `DocumentIngestedIntegrationTest`.
- [x] Topic-Naming `graphmesh.<domain>.<action>` — alle Topic-Konstanten folgen dem Schema.
- [x] Topics deklarativ via `NewTopic`-Beans — `KafkaTopicConfig` und weitere Module.
- [x] Spring-Boot-Auto-Configuration ueber `application.yml` — Producer/Consumer/Serializer-Props dort gesetzt.
- [x] Infrastruktur via docker-compose — Kafka + Schema Registry werden von Integrationstests genutzt.
- [x] Bestehende Funktionalitaet unberuehrt — Feature ist Basisinfrastruktur, alle nachfolgenden Features bauen darauf auf.

## Offene Punkte

- Keine.
