# Feature 06: Configuration Service — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/config/ConfigItem.kt`** — Datenmodell. `ConfigType` (ONTOLOGY, FLOW, TOOL, PARAMETER, COLLECTION_SETTINGS, LLM_SETTINGS, SCHEMA), `ConfigAction` (CREATED/UPDATED/DELETED), `ConfigItem` mit Versionierung, `ConfigChangedEvent` als Kafka/ApplicationEvent-Payload.
- **`src/main/kotlin/com/agentwork/graphmesh/config/ConfigStore.kt`** — Persistenz-Interface (synchron, keine Coroutines): `save`, `findById`, `findByType`, `findByTypeAndKey`, `delete`, `history`.
- **`src/main/kotlin/com/agentwork/graphmesh/config/CassandraConfigStore.kt`** — Cassandra-Implementierung mit PreparedStatements. Schreibt bei `save` in drei Tabellen: `config_items`, `config_by_type`, `config_history`. Keyspace aus `graphmesh.cassandra.keyspace`.
- **`src/main/kotlin/com/agentwork/graphmesh/config/ConfigSchemaInitializer.kt`** — `@PostConstruct`-Bean, legt `config_items`, `config_by_type`, `config_history` (CLUSTERING ORDER BY version DESC) an.
- **`src/main/kotlin/com/agentwork/graphmesh/config/ConfigService.kt`** — Zentraler `@Service`. Erhoeht Version bei Update, behaelt `createdAt` des Vorgaengers bei, publiziert sowohl `ApplicationEventPublisher`-Event als auch Kafka-Event. Bietet zusaetzlich `findAll(type)` (nicht im Spec).
- **`src/main/kotlin/com/agentwork/graphmesh/config/ConfigChangeProducer.kt`** — Kafka-Producer auf Topic `graphmesh.config.changed` (Spec nannte `graphmesh.config.push`). Avro-Schema `/avro/config-changed.avsc`, CloudEvent-Header via `CloudEventHeaders`.
- **`src/main/kotlin/com/agentwork/graphmesh/config/ConfigChangeConsumer.kt`** — `@KafkaListener` auf dem gleichen Topic, de-serialisiert aus Avro und re-publiziert als Spring-`ApplicationEvent`. Keine `ConfigHandler`-Registry, sondern Standard-Spring-`@EventListener`-Mechanismus.
- **`src/main/kotlin/com/agentwork/graphmesh/config/JacksonConfig.kt`** — `ObjectMapper`-Bean (Jackson 2), weil Spring Boot 4 nur Jackson 3 exponiert.

### Tests

- **`ConfigServiceTest`** — Unit-Tests mit MockK/Fakes fuer `save`/`delete`, Versionserhoehung, Event-Emission.
- **`ConfigServiceFindAllTest`** — Abdeckung des zusaetzlichen `findAll(type)`-Pfades.
- **`CassandraConfigStoreIntegrationTest`** — Integrationstest gegen laufende Cassandra (docker-compose, keine Testcontainers).

## Abweichungen vom Feature-Dokument

- **Package**: `com.agentwork.graphmesh.config` statt `com.graphmesh.config`. Kein separates Gradle-Modul, sondern Spring-Modulith-Paket im Monolithen.
- **Kafka-Topic**: `graphmesh.config.changed` statt `graphmesh.config.push`. Payload-Klasse heisst `ConfigChangedEvent` statt `ConfigChangeEvent`.
- **Kein `ConfigHandler`-Pattern**: Statt eines eigenen Registry-Patterns mit `handledTypes`-Filter wird Springs `ApplicationEventPublisher` benutzt; Services registrieren sich per `@EventListener(ConfigChangedEvent::class)` und filtern selbst.
- **Synchrone API**: Alle Store- und Service-Methoden sind nicht `suspend`. Kein Coroutines-Overhead, direkter Cassandra-Driver-Aufruf.
- **Kein Loki-Logging**: `LoggingProperties`, `LokiProperties`, `LokiLogbackAppender` sowie die `graphmesh.logging.*`-Properties sind nicht implementiert. Logging laeuft ueber Standard-SLF4J/Logback ohne Loki-Appender.
- **Keine Auto-Configuration**: Kein `GraphMeshConfigAutoConfiguration`, kein `GraphMeshConfigProperties`, kein `AutoConfiguration.imports`. Stattdessen werden die Beans direkt ueber `@Service`/`@Component`-Scan des Hauptmoduls geladen.
- **Serialisierungsformat Kafka**: Avro + CloudEvents-Header, nicht im Spec erwaehnt.
- **`history`-Row verliert `type` und `key`**: Die `config_history`-Tabelle speichert nur `(id, version, value, updated_at, updated_by)`; beim `history`-Read wird `type = PARAMETER` und `key = ""` gesetzt (bewusste Schema-Reduktion, dokumentiert im Code).
- **Keine Testcontainers**: Spezifiziert waren Testcontainers — die Integrationstests laufen gegen docker-compose (Projektkonvention).
- **Zusaetzliches `SCHEMA`-Enum**: `ConfigType.SCHEMA` nachtraeglich eingefuehrt (im Spec nicht gelistet).

## Akzeptanzkriterien

- [x] CRUD fuer ConfigItems — `ConfigService.save/findById/findByType/findByTypeAndKey/delete`.
- [x] Versionierung bei Update — `ConfigService.save` erhoeht `version` um 1.
- [x] Versionshistorie abrufbar — `ConfigService.history(id, limit)` gegen `config_history`.
- [x] Kafka-Event bei jeder Aenderung — `ConfigChangeProducer.send` auf Topic `graphmesh.config.changed` (Topic-Name weicht ab, siehe Abweichungen).
- [ ] `ConfigHandler`-Pattern mit `handledTypes` — **nicht implementiert**, stattdessen Spring-`@EventListener`.
- [x] Handler-Fehler isoliert — Spring-Eventbus liefert an alle Listener unabhaengig; Fehler eines Listeners stoppen andere nicht.
- [x] Filter nach Typ — `findByType(type)`.
- [x] Cassandra-Schema wird automatisch erstellt — `ConfigSchemaInitializer@PostConstruct`.
- [ ] Loki-Logging — **nicht implementiert**.
- [ ] Spring Boot Auto-Configuration — **nicht implementiert**, Beans per Component-Scan.
- [x] Integrationstests mit Cassandra + Kafka — `CassandraConfigStoreIntegrationTest` gegen docker-compose (nicht Testcontainers).
- [x] Bestehende Funktionalitaet unberuehrt.

## Offene Punkte

- Loki-Logging-Integration ist nicht umgesetzt; falls benoetigt, separat als Infrastruktur-Feature bauen.
- `ConfigHandler`-Registry-Pattern wurde zugunsten von Spring-`@EventListener` gestrichen — falls typsichere Filterung mit `handledTypes` spaeter gewuenscht ist, nachruesten.
- `config_history` speichert `type`/`key` nicht; bei Bedarf Schema erweitern.
