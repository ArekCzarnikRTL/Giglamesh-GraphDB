# Feature 04: Qdrant Vector Store

## Problem

GraphMesh benoetigt einen Vector Store fuer Embedding-basierte Aehnlichkeitssuche (Semantic Search). Die
Herausforderung: Verschiedene Embedding-Modelle erzeugen Vektoren mit unterschiedlichen Dimensionen (384, 768, 1536).
Die Dimension ist erst beim ersten Schreibvorgang bekannt, nicht bei der Collection-Erstellung. Hardgecodete Dimensionen
fuehren zu Fehlern bei Modellwechseln.

## Ziel

Bereitstellung einer Qdrant-Abstraktionsschicht mit Lazy-Collection-Erstellung, dimensionsabhaengiger Benennung und
filterbasierter Aehnlichkeitssuche.

1. **VectorStore Interface** -- Provider-agnostische API fuer Upsert/Search/Delete-Operationen
2. **Lazy Collection Creation** -- Collections werden erst beim ersten Schreibvorgang mit korrekter Dimension erstellt
3. **Dimension-Aware Naming** -- Collection-Namen enthalten die Dimension als Suffix (`<collection>_<dim>`)
4. **Filtered Similarity Search** -- Aehnlichkeitssuche mit optionalen Metadaten-Filtern
5. **Spring Boot Auto-Configuration** -- Integration mit Qdrant Java Client

## Voraussetzungen

| Abhaengigkeit      | Status     | Blocker? |
|--------------------|------------|----------|
| Qdrant Server 1.x+ | Geplant    | Nein     |
| Qdrant Java Client | Verfuegbar | Nein     |
| Spring Boot 3.x    | Verfuegbar | Nein     |

## Architektur

### VectorStore Interface

```kotlin
package com.graphmesh.storage.vector

/**
 * Provider-agnostische Schnittstelle fuer Vektor-Operationen.
 * Unterstuetzt Lazy Collection Creation und dimensionsabhaengige Benennung.
 */
interface VectorStore {

    /**
     * Fuegt Vektoren ein oder aktualisiert bestehende (Upsert).
     * Erstellt die Collection bei Bedarf mit korrekter Dimension.
     */
    suspend fun upsert(
        collection: String,
        points: List<VectorPoint>
    )

    /**
     * Sucht die aehnlichsten Vektoren zu einem Query-Vektor.
     * Gibt leere Liste zurueck, wenn die Collection nicht existiert.
     */
    suspend fun search(
        collection: String,
        queryVector: FloatArray,
        limit: Int = 10,
        filter: VectorFilter? = null,
        scoreThreshold: Float? = null
    ): List<SearchResult>

    /**
     * Loescht Vektoren anhand ihrer IDs.
     */
    suspend fun delete(collection: String, ids: List<String>)

    /**
     * Loescht eine komplette Collection (alle Dimensionsvarianten).
     */
    suspend fun deleteCollection(collection: String)

    /**
     * Prueft ob eine Collection existiert (fuer eine bestimmte Dimension).
     */
    suspend fun collectionExists(collection: String, dimension: Int): Boolean
}
```

### Datenmodell

```kotlin
package com.graphmesh.storage.vector

/**
 * Ein Vektorpunkt mit ID, Vektor und optionalen Metadaten.
 */
data class VectorPoint(
    val id: String,
    val vector: FloatArray,
    val payload: Map<String, Any> = emptyMap()
) {
    val dimension: Int get() = vector.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorPoint) return false
        return id == other.id && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Ergebnis einer Aehnlichkeitssuche.
 */
data class SearchResult(
    val id: String,
    val score: Float,
    val payload: Map<String, Any> = emptyMap()
)

/**
 * Filter fuer Metadaten bei der Suche.
 */
sealed class VectorFilter {
    data class Equals(val field: String, val value: Any) : VectorFilter()
    data class In(val field: String, val values: List<Any>) : VectorFilter()
    data class And(val filters: List<VectorFilter>) : VectorFilter()
    data class Or(val filters: List<VectorFilter>) : VectorFilter()
    data class Not(val filter: VectorFilter) : VectorFilter()
}
```

### Dimension-Aware Collection Naming

```kotlin
package com.graphmesh.storage.vector

/**
 * Hilfsfunktionen fuer dimensionsabhaengige Collection-Namen.
 *
 * Beispiel: "documents" mit 384-dimensionalen Vektoren -> "documents_384"
 */
object CollectionNaming {

    /**
     * Erzeugt den physischen Collection-Namen mit Dimensionssuffix.
     */
    fun physicalName(logicalName: String, dimension: Int): String =
        "${logicalName}_${dimension}"

    /**
     * Erzeugt ein Prefix-Pattern fuer das Finden aller Dimensionsvarianten.
     */
    fun prefixPattern(logicalName: String): String =
        "${logicalName}_"

    /**
     * Extrahiert die Dimension aus einem physischen Collection-Namen.
     */
    fun extractDimension(physicalName: String): Int? =
        physicalName.substringAfterLast('_').toIntOrNull()
}
```

### Qdrant Implementierung (Lazy Creation)

```kotlin
package com.graphmesh.storage.vector.impl

import com.graphmesh.storage.vector.*
import io.qdrant.client.QdrantClient
import io.qdrant.client.grpc.Collections.Distance
import io.qdrant.client.grpc.Collections.VectorParams
import org.slf4j.LoggerFactory

class QdrantVectorStore(
    private val client: QdrantClient
) : VectorStore {

    private val log = LoggerFactory.getLogger(javaClass)
    private val existingCollections = mutableSetOf<String>()

    override suspend fun upsert(collection: String, points: List<VectorPoint>) {
        if (points.isEmpty()) return

        val dimension = points.first().dimension
        val physicalName = CollectionNaming.physicalName(collection, dimension)

        // Lazy Creation: Collection erstellen falls noetig
        ensureCollection(physicalName, dimension)

        // Upsert ausfuehren
        // ... Qdrant-Client-Aufrufe
        log.debug("Upsert: {} Punkte in {}", points.size, physicalName)
    }

    override suspend fun search(
        collection: String, queryVector: FloatArray,
        limit: Int, filter: VectorFilter?, scoreThreshold: Float?
    ): List<SearchResult> {
        val physicalName = CollectionNaming.physicalName(collection, queryVector.size)

        // Graceful Degradation: Leere Ergebnisse bei fehlender Collection
        if (!collectionExists(collection, queryVector.size)) {
            log.debug("Collection {} existiert nicht, gebe leere Ergebnisse zurueck", physicalName)
            return emptyList()
        }

        // Suche ausfuehren
        // ... Qdrant-Client-Aufrufe
        return emptyList()
    }

    override suspend fun deleteCollection(collection: String) {
        // Alle Dimensionsvarianten loeschen
        val allCollections = client.listCollectionsAsync().get()
        val prefix = CollectionNaming.prefixPattern(collection)
        val matching = allCollections.filter { it.startsWith(prefix) }

        matching.forEach { name ->
            client.deleteCollectionAsync(name).get()
            existingCollections.remove(name)
            log.info("Collection geloescht: {}", name)
        }
        log.info("{} Collection(s) fuer '{}' geloescht", matching.size, collection)
    }

    private suspend fun ensureCollection(physicalName: String, dimension: Int) {
        if (physicalName in existingCollections) return

        val exists = client.collectionExistsAsync(physicalName).get()
        if (!exists) {
            client.createCollectionAsync(
                physicalName,
                VectorParams.newBuilder()
                    .setSize(dimension.toLong())
                    .setDistance(Distance.Cosine)
                    .build()
            ).get()
            log.info("Collection lazy erstellt: {} (dimension={})", physicalName, dimension)
        }
        existingCollections.add(physicalName)
    }

    // ... weitere Implementierungen
}
```

### Spring Boot Auto-Configuration

```kotlin
package com.graphmesh.storage.vector.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "graphmesh.storage.vector")
data class GraphMeshVectorProperties(
    val host: String = "localhost",
    val port: Int = 6334,
    val grpcPort: Int = 6334,
    val apiKey: String? = null,
    val useTls: Boolean = false,
    val defaultDistance: String = "COSINE"
)
```

### application.yml Beispiel

```yaml
graphmesh:
  storage:
    vector:
      host: qdrant
      port: 6334
      grpc-port: 6334
      api-key: null
      use-tls: false
      default-distance: COSINE
```

## Betroffene Dateien

### Backend

| Datei                                                                                                                | Aenderung                             |
|----------------------------------------------------------------------------------------------------------------------|---------------------------------------|
| `storage-vector/src/main/kotlin/com/graphmesh/storage/vector/VectorStore.kt`                                         | NEU - VectorStore-Interface           |
| `storage-vector/src/main/kotlin/com/graphmesh/storage/vector/VectorPoint.kt`                                         | NEU - Vektorpunkt-Datenmodell         |
| `storage-vector/src/main/kotlin/com/graphmesh/storage/vector/SearchResult.kt`                                        | NEU - Suchergebnis-Datenmodell        |
| `storage-vector/src/main/kotlin/com/graphmesh/storage/vector/VectorFilter.kt`                                        | NEU - Filter-DSL                      |
| `storage-vector/src/main/kotlin/com/graphmesh/storage/vector/CollectionNaming.kt`                                    | NEU - Dimension-Aware Naming          |
| `storage-vector/src/main/kotlin/com/graphmesh/storage/vector/impl/QdrantVectorStore.kt`                              | NEU - Qdrant-Implementierung          |
| `storage-vector/src/main/kotlin/com/graphmesh/storage/vector/autoconfigure/GraphMeshVectorAutoConfiguration.kt`      | NEU - Auto-Configuration              |
| `storage-vector/src/main/kotlin/com/graphmesh/storage/vector/autoconfigure/GraphMeshVectorProperties.kt`             | NEU - Properties                      |
| `storage-vector/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | NEU - Auto-Configuration-Registration |
| `storage-vector/build.gradle.kts`                                                                                    | NEU - Gradle-Modul                    |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                              | Aenderung                                  |
|----------------------------------------------------------------------------------------------------|--------------------------------------------|
| `storage-vector/src/test/kotlin/com/graphmesh/storage/vector/QdrantVectorStoreTest.kt`             | NEU - VectorStore-Unit-Tests               |
| `storage-vector/src/test/kotlin/com/graphmesh/storage/vector/CollectionNamingTest.kt`              | NEU - Naming-Tests                         |
| `storage-vector/src/test/kotlin/com/graphmesh/storage/vector/VectorFilterTest.kt`                  | NEU - Filter-Tests                         |
| `storage-vector/src/test/kotlin/com/graphmesh/storage/vector/integration/QdrantIntegrationTest.kt` | NEU - Integrationstests mit Testcontainers |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                               |
|-------------------|-------------|-----------------------------------------------------|
| Spring Boot (JVM) | Ja          | Qdrant Java Client bietet volle gRPC-Unterstuetzung |
| KMP Library       | Nein        | Qdrant Java Client ist JVM-only                     |
| Ktor/Wasm         | Nein        | Kein Qdrant-Client fuer Wasm/JS verfuegbar          |

## Akzeptanzkriterien

- [ ] `VectorStore.upsert()` erstellt die Collection lazy beim ersten Schreibvorgang mit korrekter Dimension
- [ ] Collection-Namen folgen dem Schema `<collection>_<dimension>` (z.B. `documents_384`)
- [ ] `VectorStore.search()` gibt leere Liste zurueck, wenn die Collection nicht existiert (kein Fehler)
- [ ] Aehnlichkeitssuche mit Cosine Distance liefert korrekte Ergebnisse
- [ ] Metadaten-Filter (Equals, In, And, Or, Not) werden korrekt an Qdrant uebergeben
- [ ] `deleteCollection()` loescht alle Dimensionsvarianten einer logischen Collection
- [ ] Verschiedene Embedding-Dimensionen (384, 768, 1536) koexistieren fuer dieselbe logische Collection
- [ ] Concurrent First Writes erzeugen keine Race Conditions
- [ ] Spring Boot Auto-Configuration funktioniert ohne manuelle Bean-Definition
- [ ] Integrationstests mit Testcontainers (Qdrant) laufen erfolgreich
- [ ] Bestehende Funktionalitaet bleibt unberuehrt
