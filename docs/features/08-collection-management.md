# Feature 08: Collection Management

## Problem

GraphMesh muss Collections explizit verwalten, bevor Daten gespeichert werden koennen. Ohne eine zentrale
Collection-Verwaltung koennen Collections in einzelnen Storage-Backends (Cassandra, Qdrant, S3) unabhaengig voneinander
existieren, was zu verwaisten Daten, inkonsistenten Zustaenden und fehlender Uebersicht ueber den Collection-Lifecycle
fuehrt. Ausserdem fehlt eine kaskadierte Loeschung, die alle Backends synchron bereinigt.

## Ziel

Implementierung einer zentralen Collection-Verwaltung, die Collections explizit erstellt, synchron ueber alle
Storage-Backends verteilt und bei Loeschung kaskadiert bereinigt.

1. **Explizite Collection-Erstellung** -- Collections muessen vor Datenoperationen erstellt werden, synchronisiert ueber
   Cassandra, Qdrant und S3
2. **Cascade Deletion** -- Loeschung einer Collection entfernt alle zugehoerigen Daten aus allen Backends
3. **Tags und Metadata** -- Flexible Kategorisierung und Organisation von Collections
4. **Lifecycle Events** -- Collection-Erstellung und -Loeschung werden als Kafka-Events publiziert
5. **Config-basierte Synchronisation** -- Storage-Backends synchronisieren sich ueber den ConfigService

## Voraussetzungen

| Abhaengigkeit                       | Status     | Blocker? |
|-------------------------------------|------------|----------|
| Feature 02: Cassandra Storage Layer | Geplant    | Ja       |
| Feature 03: S3 Blob Storage         | Geplant    | Ja       |
| Feature 04: Qdrant Vector Store     | Geplant    | Ja       |
| Feature 06: Configuration Service   | Geplant    | Ja       |
| Spring Boot 3.x                     | Verfuegbar | Nein     |

## Architektur

### Collection Datenmodell

```kotlin
package com.graphmesh.collection

import java.time.Instant
import java.util.UUID

/**
 * Repraesentiert eine Collection im System.
 * Collections sind der uebergeordnete Container fuer alle Daten (Dokumente, Quads, Embeddings).
 */
data class Collection(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val description: String = "",
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
```

### CollectionStore Interface

```kotlin
package com.graphmesh.collection

import java.util.UUID

/**
 * Persistenz-Interface fuer Collection-Metadaten.
 * Implementiert durch CassandraCollectionStore.
 */
interface CollectionStore {

    /**
     * Speichert eine neue Collection oder aktualisiert eine bestehende.
     */
    suspend fun save(collection: Collection)

    /**
     * Laedt eine Collection anhand ihrer ID.
     */
    suspend fun findById(id: UUID): Collection?

    /**
     * Laedt eine Collection anhand ihres Namens.
     */
    suspend fun findByName(name: String): Collection?

    /**
     * Listet alle Collections, optional gefiltert nach Tags.
     */
    suspend fun findAll(tags: Set<String> = emptySet()): List<Collection>

    /**
     * Loescht die Collection-Metadaten.
     */
    suspend fun delete(id: UUID)

    /**
     * Prueft ob eine Collection existiert.
     */
    suspend fun exists(id: UUID): Boolean
}
```

### CollectionService

```kotlin
package com.graphmesh.collection

import com.graphmesh.messaging.MessageProducer
import com.graphmesh.storage.cassandra.QuadStore
import com.graphmesh.storage.qdrant.VectorStore
import com.graphmesh.storage.s3.BlobStore
import com.graphmesh.config.ConfigService
import java.util.UUID

/**
 * Zentraler Service fuer Collection-Lifecycle-Management.
 * Koordiniert Erstellung und Loeschung ueber alle Storage-Backends.
 */
class CollectionService(
    private val collectionStore: CollectionStore,
    private val quadStore: QuadStore,
    private val vectorStore: VectorStore,
    private val blobStore: BlobStore,
    private val configService: ConfigService,
    private val eventProducer: MessageProducer<CollectionEvent>
) {

    /**
     * Erstellt eine neue Collection in allen Storage-Backends.
     *
     * Ablauf:
     * 1. Metadaten in Cassandra speichern
     * 2. Collection in Qdrant anlegen
     * 3. S3-Prefix registrieren
     * 4. Config-Push an alle Backends
     * 5. Kafka-Event publizieren
     */
    suspend fun createCollection(
        name: String,
        description: String = "",
        tags: Set<String> = emptySet(),
        metadata: Map<String, String> = emptyMap()
    ): Collection {
        require(collectionStore.findByName(name) == null) {
            "Collection mit Name '$name' existiert bereits"
        }

        val collection = Collection(
            name = name,
            description = description,
            tags = tags,
            metadata = metadata
        )

        // 1. Metadaten persistieren
        collectionStore.save(collection)

        // 2. Config-Push an alle Storage-Backends
        configService.pushCollectionConfig(collection)

        // 3. Lifecycle-Event publizieren
        eventProducer.send(
            CollectionEvent(
                type = CollectionEventType.CREATED,
                collectionId = collection.id,
                collectionName = collection.name
            )
        )

        return collection
    }

    /**
     * Loescht eine Collection und alle zugehoerigen Daten (Cascade).
     *
     * Ablauf:
     * 1. Alle Quads der Collection aus Cassandra loeschen
     * 2. Alle Vektoren der Collection aus Qdrant loeschen
     * 3. Alle Blobs der Collection aus S3 loeschen
     * 4. Collection-Metadaten loeschen
     * 5. Kafka-Event publizieren
     */
    suspend fun deleteCollection(id: UUID) {
        val collection = collectionStore.findById(id)
            ?: throw CollectionNotFoundException(id)

        // Kaskadierte Loeschung in allen Backends
        quadStore.deleteByCollection(id.toString())
        vectorStore.deleteCollection(id.toString())
        blobStore.deletePrefix("collections/${id}/")

        // Metadaten loeschen
        collectionStore.delete(id)

        // Config-Push: Collection entfernt
        configService.removeCollectionConfig(id)

        // Lifecycle-Event publizieren
        eventProducer.send(
            CollectionEvent(
                type = CollectionEventType.DELETED,
                collectionId = id,
                collectionName = collection.name
            )
        )
    }

    /**
     * Aktualisiert Collection-Metadaten (Name, Description, Tags).
     * Aenderungen betreffen nur die Metadaten, nicht die Storage-Backends.
     */
    suspend fun updateCollection(
        id: UUID,
        name: String? = null,
        description: String? = null,
        tags: Set<String>? = null,
        metadata: Map<String, String>? = null
    ): Collection {
        val existing = collectionStore.findById(id)
            ?: throw CollectionNotFoundException(id)

        val updated = existing.copy(
            name = name ?: existing.name,
            description = description ?: existing.description,
            tags = tags ?: existing.tags,
            metadata = metadata ?: existing.metadata,
            updatedAt = java.time.Instant.now()
        )

        collectionStore.save(updated)
        return updated
    }

    /**
     * Prueft ob eine Collection existiert. Wird von anderen Services
     * aufgerufen, bevor Datenoperationen durchgefuehrt werden.
     */
    suspend fun requireExists(id: UUID) {
        if (!collectionStore.exists(id)) {
            throw CollectionNotFoundException(id)
        }
    }
}
```

### Collection Events

```kotlin
package com.graphmesh.collection

import java.util.UUID
import java.time.Instant

enum class CollectionEventType {
    CREATED, UPDATED, DELETED
}

/**
 * Kafka-Event fuer Collection-Lifecycle-Aenderungen.
 * Topic: graphmesh.collection.lifecycle
 */
data class CollectionEvent(
    val type: CollectionEventType,
    val collectionId: UUID,
    val collectionName: String,
    val timestamp: Instant = Instant.now()
)
```

### Cassandra Schema

```sql
CREATE TABLE IF NOT EXISTS collections (
    id          uuid,
    name        text,
    description text,
    tags        set<text>,
    metadata    map<text, text>,
    created_at  timestamp,
    updated_at  timestamp,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS collections_name_idx ON collections (name);
CREATE INDEX IF NOT EXISTS collections_tags_idx ON collections (tags);
```

### Config-basierte Backend-Synchronisation

Storage-Backends (Cassandra, Qdrant, S3) registrieren sich beim `ConfigService` (Feature 06) als `ConfigHandler`. Bei
Collection-Erstellung oder -Loeschung erhalten sie einen Config-Push und synchronisieren ihren lokalen Zustand:

```kotlin
package com.graphmesh.collection

import com.graphmesh.config.ConfigHandler

/**
 * Jedes Storage-Backend implementiert dieses Interface,
 * um auf Collection-Lifecycle-Events zu reagieren.
 */
interface CollectionConfigHandler : ConfigHandler {

    /**
     * Wird aufgerufen, wenn eine neue Collection erstellt wird.
     * Das Backend erstellt die notwendigen Strukturen (Tabellen, Indizes, Buckets).
     */
    suspend fun onCollectionCreated(collection: Collection)

    /**
     * Wird aufgerufen, wenn eine Collection geloescht wird.
     * Das Backend entfernt alle zugehoerigen Daten.
     */
    suspend fun onCollectionDeleted(collectionId: java.util.UUID)
}
```

## Betroffene Dateien

### Backend

| Datei                                                                                | Aenderung                         |
|--------------------------------------------------------------------------------------|-----------------------------------|
| `collection/src/main/kotlin/com/graphmesh/collection/Collection.kt`                  | NEU - Collection Data Class       |
| `collection/src/main/kotlin/com/graphmesh/collection/CollectionStore.kt`             | NEU - Persistenz-Interface        |
| `collection/src/main/kotlin/com/graphmesh/collection/CollectionService.kt`           | NEU - Zentraler Lifecycle-Service |
| `collection/src/main/kotlin/com/graphmesh/collection/CollectionEvent.kt`             | NEU - Kafka-Event-Modell          |
| `collection/src/main/kotlin/com/graphmesh/collection/CollectionConfigHandler.kt`     | NEU - Config-Handler-Interface    |
| `collection/src/main/kotlin/com/graphmesh/collection/CollectionNotFoundException.kt` | NEU - Exception                   |
| `collection/src/main/kotlin/com/graphmesh/collection/CassandraCollectionStore.kt`    | NEU - Cassandra-Implementierung   |
| `collection/build.gradle.kts`                                                        | NEU - Gradle-Modul                |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                 | Aenderung                                       |
|---------------------------------------------------------------------------------------|-------------------------------------------------|
| `collection/src/test/kotlin/com/graphmesh/collection/CollectionServiceTest.kt`        | NEU - Unit-Tests fuer Lifecycle-Operationen     |
| `collection/src/test/kotlin/com/graphmesh/collection/CassandraCollectionStoreTest.kt` | NEU - Integrationstests mit Testcontainers      |
| `collection/src/test/kotlin/com/graphmesh/collection/CollectionCascadeDeleteTest.kt`  | NEU - Kaskadierte Loeschung ueber alle Backends |
| `collection/src/test/kotlin/com/graphmesh/collection/CollectionEventTest.kt`          | NEU - Kafka-Event-Verifikation                  |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                                 |
|-------------------|-------------|-------------------------------------------------------|
| Spring Boot (JVM) | Ja          | Spring Data Cassandra, Spring Kafka Integration       |
| KMP Library       | Nein        | Abhaengigkeit zu Spring-spezifischen APIs             |
| Ktor/Wasm         | Nein        | Cassandra-Driver und Kafka-Client sind JVM-spezifisch |

## Akzeptanzkriterien

- [ ] Collection kann mit Name, Description, Tags und Metadata erstellt werden
- [ ] Collection-Erstellung synchronisiert automatisch alle Storage-Backends (Cassandra, Qdrant, S3)
- [ ] Doppelte Collection-Namen werden mit Fehler abgelehnt
- [ ] Cascade Deletion loescht alle zugehoerigen Daten aus allen drei Backends
- [ ] Collection-Metadaten loescht erst, nachdem alle Backend-Daten entfernt sind
- [ ] Kafka-Events (`CREATED`, `UPDATED`, `DELETED`) werden bei jeder Lifecycle-Aenderung publiziert
- [ ] `requireExists()` verhindert Datenoperationen auf nicht-existierende Collections
- [ ] Collections koennen nach Tags gefiltert aufgelistet werden
- [ ] Collection-Metadaten (Name, Description, Tags) koennen nachtraeglich aktualisiert werden
- [ ] Config-Push-Mechanismus informiert alle Storage-Backends ueber Collection-Aenderungen
