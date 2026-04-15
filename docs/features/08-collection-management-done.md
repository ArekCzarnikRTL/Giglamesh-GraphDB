# Feature 08: Collection Management — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/collection/Collection.kt`** — `Collection` mit `id: String` (UUID als String), `name`, `description`, `tags`, `metadata` sowie zusaetzlich `tenantId`/`ownerId` (Multi-Tenancy). `CollectionEventType` (CREATED/UPDATED/DELETED) und `CollectionEvent`.
- **`src/main/kotlin/com/agentwork/graphmesh/collection/CollectionStore.kt`** — Interface: `save`, `findById`, `findByName`, `findAll`, `delete`, `exists` (synchron, keine Coroutines).
- **`src/main/kotlin/com/agentwork/graphmesh/collection/CassandraCollectionStore.kt`** — Prepared-Statement-basiert, schreibt in `collections` und `collections_by_name`. Persistiert zusaetzlich `tenant_id`, `owner_id`.
- **`src/main/kotlin/com/agentwork/graphmesh/collection/CollectionSchemaInitializer.kt`** — `@PostConstruct`, legt `collections`, `collections_by_name`, `collection_ontologies` an. Altert bestehende Tabellen per `ALTER TABLE ADD tenant_id/owner_id` (mit try/catch-Ignorieren, wenn Spalten schon existieren).
- **`src/main/kotlin/com/agentwork/graphmesh/collection/CollectionService.kt`** — Zentraler `@Service`. `create` / `delete` / `update` / `findById` / `findByName` / `findAll(tags)` / `requireExists`. Cascade-Delete ruft direkt `quadStore.deleteCollection(id)`, `vectorStore.deleteCollection(id)` und loescht Blobs unter Prefix `collections/{id}/` via `blobStore.list` + `deleteBatch` (siehe Commit d8a0869 — Delete by ID, nicht by Name). Tenant-Check via `TenantContext` auf allen Leseoperationen (`AccessDeniedException`).
- **`src/main/kotlin/com/agentwork/graphmesh/collection/CollectionEventProducer.kt`** — Kafka-Producer auf `graphmesh.collection.lifecycle` mit Avro-Schema `/avro/collection-lifecycle.avsc` und CloudEvent-Headern. Zusaetzlich zum Kafka-Send publiziert der `CollectionService` `ApplicationEvent`s.
- **`src/main/kotlin/com/agentwork/graphmesh/collection/CollectionOntologyService.kt`** — Zusatz: `assign`/`unassign`/`listForCollection` gegen `collection_ontologies`-Tabelle (nicht im Spec).
- **`src/main/kotlin/com/agentwork/graphmesh/collection/CollectionNotFoundException.kt`** — `RuntimeException`.

### Tests

- **`CollectionServiceTest`** — Lifecycle (create, findByName, update, delete) mit Fake-Stores.
- **`CollectionServiceTenantTest`** — Tenant-Isolation: Filterung in `findAll`, AccessDenied bei fremdem Tenant.
- **`CollectionOntologyServiceTest`** — Ontologie-Zuweisungen.
- **`CassandraCollectionStoreIntegrationTest`** — Integrationstest gegen Cassandra (docker-compose).

## Abweichungen vom Feature-Dokument

- **Package/Modul**: `com.agentwork.graphmesh.collection` im Monolithen, kein eigenes `collection/`-Gradle-Modul.
- **`Collection.id` ist String, nicht `UUID`**: UUID-Wert wird als String gespeichert; vereinheitlicht mit anderen Stores.
- **Keine Storage-Backend-Anbindung ueber `ConfigService`**: Spec sah `configService.pushCollectionConfig(...)` und `CollectionConfigHandler` vor. Tatsaechlich ruft `CollectionService.delete` die Backends direkt (`quadStore.deleteCollection`, `vectorStore.deleteCollection`, Blob-Prefix-Delete). Kein `ConfigHandler`-Interface, kein Config-Push fuer Backend-Sync.
- **`create` statt `createCollection`, `delete` statt `deleteCollection`**: Methodennamen anders als im Spec.
- **Keine explizite Qdrant-Collection-Erstellung in `create`**: Qdrant-Collections werden erst bei der Nutzung (Vector-Insert) automatisch angelegt. `create` erzeugt nur den Metadaten-Eintrag und Events.
- **Keine S3-Prefix-Registrierung in `create`**: Blobs werden beim Upload unter dem Prefix abgelegt, keine explizite Registrierung noetig.
- **Tenant-Context (`tenantId`, `ownerId`)**: Nicht im Spec — Pflicht-Erweiterung aus Feature "Multi-Tenancy".
- **Keine Testcontainers**: Integrationstests laufen gegen docker-compose.
- **`CollectionOntologyService`**: Bonus-Implementierung (Zuweisung Ontologie <-> Collection), im Spec nicht vorhanden.
- **Event-Versand via Kafka + Spring-`ApplicationEventPublisher`**: Doppelt publiziert (lokal und remote), Avro-Payload.

## Akzeptanzkriterien

- [x] Collection mit Name, Description, Tags, Metadata erstellbar — `CollectionService.create`.
- [ ] Create synchronisiert Cassandra/Qdrant/S3 — **teilweise**: nur Cassandra-Metadaten; Qdrant-Collection wird lazy, S3 ohne Prefix-Registrierung.
- [x] Doppelte Namen abgelehnt — `require(collectionStore.findByName(name) == null)`.
- [x] Cascade Deletion loescht alle drei Backends — `CollectionService.delete` ruft `quadStore.deleteCollection(id)`, `vectorStore.deleteCollection(id)`, Blob-Prefix-Delete.
- [x] Metadaten-Loeschung erst nach Backend-Cleanup — Reihenfolge in `delete`: Backends zuerst, dann `collectionStore.delete`.
- [x] Kafka-Events CREATED/UPDATED/DELETED — `CollectionEventProducer` auf `graphmesh.collection.lifecycle`.
- [x] `requireExists` — verhindert Datenoperationen auf nicht-existente Collections.
- [x] Filter nach Tags — `CollectionService.findAll(tags)` mit `containsAll`.
- [x] Metadaten-Update — `CollectionService.update`.
- [ ] Config-Push informiert Backends — **nicht implementiert**; Backends werden direkt synchron aufgerufen.

## Offene Punkte

- Keine aktive Sync-Schleife fuer Storage-Backends ueber `ConfigService`; falls zukuenftig lose Kopplung gewuenscht, `CollectionConfigHandler`-Pattern nachruesten.
- Qdrant-Collection wird nicht eager angelegt — bei streng validierenden Setups ggf. `vectorStore.createCollection(id)` in `create` ergaenzen.
