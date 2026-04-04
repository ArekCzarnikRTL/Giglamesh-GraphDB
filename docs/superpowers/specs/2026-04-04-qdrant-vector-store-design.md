# Qdrant Vector Store - Design Spec

## Ziel

Qdrant-basierte Vector-Store-Schicht fuer GraphMesh. Ermoeglicht Embedding-basierte Aehnlichkeitssuche mit Lazy Collection Creation und dimensionsabhaengiger Benennung. Unterstuetzt verschiedene Embedding-Dimensionen (384, 768, 1536) gleichzeitig.

## Ansatz

Qdrant Java gRPC Client direkt — konsistent mit Cassandra (CqlSession) und S3 (S3Client). Synchrone API, kein Coroutines. Spring AI bleibt fuer Embedding-Modelle, aber Vector Store Operationen laufen ueber eigenes Interface.

## Paketstruktur

```
com.agentwork.graphmesh.storage.vector/
  VectorStore.kt                    -- Interface: upsert, search, delete, deleteCollection, collectionExists
  VectorPoint.kt                    -- data class: id, vector (FloatArray), payload
  SearchResult.kt                   -- data class: id, score, payload
  VectorFilter.kt                   -- sealed class: Equals, In, And, Or, Not
  CollectionNaming.kt               -- object: physicalName(), prefixPattern(), extractDimension()
  QdrantVectorStore.kt              -- Implementierung mit QdrantClient (gRPC)
  VectorStoreProperties.kt          -- @ConfigurationProperties(prefix = "graphmesh.storage.vector")
  VectorStoreAutoConfiguration.kt   -- @Configuration: QdrantClient + QdrantVectorStore Beans
```

## Interface

```kotlin
interface VectorStore {
    fun upsert(collection: String, points: List<VectorPoint>)
    fun search(collection: String, queryVector: FloatArray, limit: Int = 10, filter: VectorFilter? = null, scoreThreshold: Float? = null): List<SearchResult>
    fun delete(collection: String, dimension: Int, ids: List<String>)
    fun deleteCollection(collection: String)
    fun collectionExists(collection: String, dimension: Int): Boolean
}
```

Alle Methoden synchron. `upsert` leitet die Dimension aus `points.first().dimension` ab. `search` leitet sie aus `queryVector.size` ab. `delete` benoetigt die Dimension explizit da keine Vektoren uebergeben werden.

## Datenmodelle

```kotlin
data class VectorPoint(
    val id: String,
    val vector: FloatArray,
    val payload: Map<String, Any> = emptyMap()
) {
    val dimension: Int get() = vector.size
    // Custom equals/hashCode wegen FloatArray
}

data class SearchResult(
    val id: String,
    val score: Float,
    val payload: Map<String, Any> = emptyMap()
)

sealed class VectorFilter {
    data class Equals(val field: String, val value: Any) : VectorFilter()
    data class In(val field: String, val values: List<Any>) : VectorFilter()
    data class And(val filters: List<VectorFilter>) : VectorFilter()
    data class Or(val filters: List<VectorFilter>) : VectorFilter()
    data class Not(val filter: VectorFilter) : VectorFilter()
}
```

## Dimension-Aware Collection Naming

```kotlin
object CollectionNaming {
    fun physicalName(logicalName: String, dimension: Int): String = "${logicalName}_${dimension}"
    fun prefixPattern(logicalName: String): String = "${logicalName}_"
    fun extractDimension(physicalName: String): Int? = physicalName.substringAfterLast('_').toIntOrNull()
}
```

Beispiel: Logische Collection `documents` mit 384-dimensionalen Vektoren -> physische Collection `documents_384`.

## QdrantVectorStore Implementierung

- Constructor: `QdrantClient`
- In-Memory `ConcurrentHashMap<String, Boolean>` als Cache fuer existierende Collections
- `upsert()`:
  1. Dimension aus `points.first().dimension`
  2. `physicalName = CollectionNaming.physicalName(collection, dimension)`
  3. `ensureCollection(physicalName, dimension)` — Thread-safe via `synchronized`
  4. Points zu Qdrant `PointStruct` mappen (UUID aus String-ID, Vektoren, Payload)
  5. `client.upsertAsync(physicalName, pointStructs).get()`
- `search()`:
  1. Dimension aus `queryVector.size`
  2. Falls Collection nicht existiert -> leere Liste (kein Fehler)
  3. `QueryPoints` mit optionalem Filter und scoreThreshold
  4. `client.queryAsync(queryPoints).get()`
  5. Ergebnisse auf `SearchResult` mappen
- `delete()`: `client.deleteAsync(physicalName, pointIds).get()`
- `deleteCollection()`: Alle Collections mit Prefix listen, jede loeschen
- `ensureCollection()`:
  1. Check In-Memory Cache
  2. `client.collectionExistsAsync(name).get()`
  3. Falls nein: `client.createCollectionAsync(CreateCollection)` mit Cosine Distance
  4. Cache aktualisieren

### Filter-Mapping auf Qdrant gRPC

- `VectorFilter.Equals` mit String-Value -> `ConditionFactory.matchKeyword(field, value)`
- `VectorFilter.Equals` mit Number-Value -> `ConditionFactory.matchValue(field, value)`
- `VectorFilter.In` mit String-Values -> `ConditionFactory.matchKeywords(field, values)`
- `VectorFilter.And` -> `Filter.newBuilder().addAllMust(conditions)`
- `VectorFilter.Or` -> `Filter.newBuilder().addAllShould(conditions)`
- `VectorFilter.Not` -> `Filter.newBuilder().addMustNot(condition)`

### Point-ID Mapping

Qdrant erwartet UUID oder Integer als Point-ID. String-IDs werden via `UUID.nameUUIDFromBytes(id.toByteArray())` in deterministische UUIDs konvertiert. Die Original-ID wird als Payload-Feld `_original_id` gespeichert fuer Rueckgabe in SearchResult.

## Auto-Configuration

```kotlin
@Configuration
@EnableConfigurationProperties(VectorStoreProperties::class)
class VectorStoreAutoConfiguration {
    @Bean
    fun qdrantClient(props: VectorStoreProperties): QdrantClient =
        QdrantClient(QdrantGrpcClient.newBuilder(props.host, props.grpcPort, props.useTls).build())

    @Bean
    fun vectorStore(client: QdrantClient): VectorStore = QdrantVectorStore(client)
}
```

## Properties

```kotlin
@ConfigurationProperties(prefix = "graphmesh.storage.vector")
data class VectorStoreProperties(
    val host: String = "localhost",
    val grpcPort: Int = 6334,
    val apiKey: String? = null,
    val useTls: Boolean = false
)
```

## application.yml

```yaml
graphmesh:
  storage:
    vector:
      host: ${QDRANT_HOST:localhost}
      grpc-port: ${QDRANT_GRPC_PORT:6334}
      use-tls: false
```

## Docker-Compose

```yaml
qdrant:
  image: qdrant/qdrant:latest
  hostname: qdrant
  ports:
    - "6333:6333"
    - "6334:6334"
```

## Dependencies (build.gradle.kts)

```kotlin
implementation("io.qdrant:client:1.14.2")
```

Die bestehende `spring-ai-starter-vector-store-qdrant` Dependency bleibt, wird aber nicht fuer den VectorStore verwendet (spaeter fuer Embedding-Modelle).

## Tests

### CollectionNamingTest (Unit)
- physicalName erzeugt korrektes Format
- prefixPattern erzeugt korrektes Prefix
- extractDimension extrahiert korrekt / gibt null fuer ungueltige Namen

### QdrantVectorStoreIntegrationTest
- `@SpringBootTest` mit `@ActiveProfiles("test")`
- Excludes fuer Kafka (bestehendes Muster)
- Tests:
  - upsert + search roundtrip mit korrektem Score
  - search mit VectorFilter.Equals
  - search auf nicht-existierende Collection -> leere Liste
  - delete entfernt Punkte
  - deleteCollection loescht alle Dimensionsvarianten
  - Verschiedene Dimensionen (z.B. 4, 8) koexistieren unter gleichem logischen Namen
  - collectionExists true/false
  - ensureCollection idempotent (kein Fehler bei doppeltem Aufruf)

### application-test.yml
```yaml
graphmesh:
  storage:
    vector:
      host: localhost
      grpc-port: 6334
```

## Akzeptanzkriterien

- upsert erstellt Collection lazy mit korrekter Dimension
- Collection-Namen folgen Schema `<collection>_<dimension>`
- search gibt leere Liste bei nicht-existierender Collection
- Cosine Similarity liefert korrekte Ergebnisse
- Filter (Equals, In, And, Or, Not) werden korrekt gemappt
- deleteCollection loescht alle Dimensionsvarianten
- Verschiedene Dimensionen koexistieren
- Thread-safe First-Write (kein Race Condition bei parallelen Writes)
- Auto-Configuration erstellt alle Beans
- Integrationstests laufen gegen echtes Qdrant
