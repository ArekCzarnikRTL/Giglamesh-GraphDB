# Qdrant Vector Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Qdrant-based vector store to GraphMesh with lazy collection creation and dimension-aware naming.

**Architecture:** Direct Qdrant Java gRPC client wrapped in a `VectorStore` interface with `CollectionNaming` for dimension-based collection names (`<name>_<dim>`). Lazy creation on first write, thread-safe via synchronized. Spring auto-configuration wires beans.

**Tech Stack:** Qdrant Java Client (1.13.0 via Spring AI transitive), Qdrant gRPC, Spring Boot `@ConfigurationProperties`

**Important:** The `io.qdrant:client` dependency is already available transitively via `spring-ai-starter-vector-store-qdrant` in `build.gradle.kts`. No new dependency needed.

---

## File Structure

| File | Responsibility |
|------|---------------|
| `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStore.kt` | Interface: upsert, search, delete, deleteCollection, collectionExists |
| `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorPoint.kt` | Data class: id, vector (FloatArray), payload |
| `src/main/kotlin/com/agentwork/graphmesh/storage/vector/SearchResult.kt` | Data class: id, score, payload |
| `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorFilter.kt` | Sealed class: Equals, In, And, Or, Not |
| `src/main/kotlin/com/agentwork/graphmesh/storage/vector/CollectionNaming.kt` | Object: physicalName, prefixPattern, extractDimension |
| `src/main/kotlin/com/agentwork/graphmesh/storage/vector/QdrantVectorStore.kt` | Implementation using QdrantClient gRPC |
| `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStoreProperties.kt` | @ConfigurationProperties for Qdrant connection |
| `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStoreAutoConfiguration.kt` | @Configuration creating QdrantClient + QdrantVectorStore beans |
| `src/test/kotlin/com/agentwork/graphmesh/storage/vector/CollectionNamingTest.kt` | Unit tests for naming logic |
| `src/test/kotlin/com/agentwork/graphmesh/storage/vector/QdrantVectorStoreIntegrationTest.kt` | Integration tests against real Qdrant |

---

### Task 1: Docker-Compose + Configuration

**Files:**
- Modify: `docker-compose.yaml`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`

- [ ] **Step 1: Add Qdrant service to docker-compose.yaml**

Add after the `minio` service block in `docker-compose.yaml`:

```yaml

  qdrant:
    image: qdrant/qdrant:latest
    hostname: qdrant
    ports:
      - "6333:6333"
      - "6334:6334"
```

- [ ] **Step 2: Add vector store config to application.yml**

Under the existing `graphmesh.storage` block in `application.yml`, add after the `blob:` section:

```yaml
    vector:
      host: ${QDRANT_HOST:localhost}
      grpc-port: ${QDRANT_GRPC_PORT:6334}
      use-tls: false
```

- [ ] **Step 3: Add vector store config to application-test.yml**

Under the existing `graphmesh.storage` block in `application-test.yml`, add after the `blob:` section:

```yaml
    vector:
      host: localhost
      grpc-port: 6334
```

- [ ] **Step 4: Disable Spring AI Qdrant auto-config**

Spring AI's `QdrantVectorStoreAutoConfiguration` will conflict with our custom beans. Add to `application.yml` under `spring:`:

```yaml
  autoconfigure:
    exclude:
      - org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration
```

And add to `application-test.yml` under `spring:`:

```yaml
  autoconfigure:
    exclude:
      - org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration
```

This replaces the per-test `@SpringBootTest(properties = ["spring.autoconfigure.exclude=...QdrantVectorStoreAutoConfiguration..."])` pattern for existing tests. After this change, existing tests that exclude it in their annotation can keep their exclude (it's harmless to double-exclude).

- [ ] **Step 5: Start Qdrant and verify**

Run: `docker compose up -d qdrant && sleep 3 && curl -s http://localhost:6333/healthz`

Expected: Response with status OK or `{"title":"qdrant - vectorass engine","version":"..."}`

- [ ] **Step 6: Verify build compiles**

Run: `./gradlew compileKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yaml src/main/resources/application.yml src/test/resources/application-test.yml
git commit -m "feat(storage): add Qdrant docker service and vector store configuration

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Data Models + CollectionNaming + Unit Tests

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorPoint.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/vector/SearchResult.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorFilter.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/vector/CollectionNaming.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/storage/vector/CollectionNamingTest.kt`

- [ ] **Step 1: Write CollectionNaming tests**

Create `src/test/kotlin/com/agentwork/graphmesh/storage/vector/CollectionNamingTest.kt`:

```kotlin
package com.agentwork.graphmesh.storage.vector

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionNamingTest {

    @Test
    fun `physicalName appends dimension`() {
        assertEquals("documents_384", CollectionNaming.physicalName("documents", 384))
    }

    @Test
    fun `physicalName with large dimension`() {
        assertEquals("embeddings_1536", CollectionNaming.physicalName("embeddings", 1536))
    }

    @Test
    fun `prefixPattern appends underscore`() {
        assertEquals("documents_", CollectionNaming.prefixPattern("documents"))
    }

    @Test
    fun `extractDimension returns dimension from valid name`() {
        assertEquals(384, CollectionNaming.extractDimension("documents_384"))
    }

    @Test
    fun `extractDimension returns null for invalid name`() {
        assertNull(CollectionNaming.extractDimension("documents_abc"))
    }

    @Test
    fun `extractDimension returns null for name without underscore`() {
        assertNull(CollectionNaming.extractDimension("documents"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.vector.CollectionNamingTest"`

Expected: FAIL — classes do not exist yet.

- [ ] **Step 3: Create VectorPoint.kt**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorPoint.kt`:

```kotlin
package com.agentwork.graphmesh.storage.vector

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
```

- [ ] **Step 4: Create SearchResult.kt**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/vector/SearchResult.kt`:

```kotlin
package com.agentwork.graphmesh.storage.vector

data class SearchResult(
    val id: String,
    val score: Float,
    val payload: Map<String, Any> = emptyMap()
)
```

- [ ] **Step 5: Create VectorFilter.kt**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorFilter.kt`:

```kotlin
package com.agentwork.graphmesh.storage.vector

sealed class VectorFilter {
    data class Equals(val field: String, val value: Any) : VectorFilter()
    data class In(val field: String, val values: List<Any>) : VectorFilter()
    data class And(val filters: List<VectorFilter>) : VectorFilter()
    data class Or(val filters: List<VectorFilter>) : VectorFilter()
    data class Not(val filter: VectorFilter) : VectorFilter()
}
```

- [ ] **Step 6: Create CollectionNaming.kt**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/vector/CollectionNaming.kt`:

```kotlin
package com.agentwork.graphmesh.storage.vector

object CollectionNaming {

    fun physicalName(logicalName: String, dimension: Int): String =
        "${logicalName}_${dimension}"

    fun prefixPattern(logicalName: String): String =
        "${logicalName}_"

    fun extractDimension(physicalName: String): Int? =
        physicalName.substringAfterLast('_').toIntOrNull()
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.vector.CollectionNamingTest"`

Expected: BUILD SUCCESSFUL, all 6 tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorPoint.kt \
        src/main/kotlin/com/agentwork/graphmesh/storage/vector/SearchResult.kt \
        src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorFilter.kt \
        src/main/kotlin/com/agentwork/graphmesh/storage/vector/CollectionNaming.kt \
        src/test/kotlin/com/agentwork/graphmesh/storage/vector/CollectionNamingTest.kt
git commit -m "feat(storage): add vector data models and CollectionNaming with tests

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: VectorStore Interface + Properties + AutoConfiguration

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStore.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStoreProperties.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStoreAutoConfiguration.kt`

- [ ] **Step 1: Create VectorStore.kt**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStore.kt`:

```kotlin
package com.agentwork.graphmesh.storage.vector

interface VectorStore {

    fun upsert(collection: String, points: List<VectorPoint>)

    fun search(
        collection: String,
        queryVector: FloatArray,
        limit: Int = 10,
        filter: VectorFilter? = null,
        scoreThreshold: Float? = null
    ): List<SearchResult>

    fun delete(collection: String, dimension: Int, ids: List<String>)

    fun deleteCollection(collection: String)

    fun collectionExists(collection: String, dimension: Int): Boolean
}
```

- [ ] **Step 2: Create VectorStoreProperties.kt**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStoreProperties.kt`:

```kotlin
package com.agentwork.graphmesh.storage.vector

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "graphmesh.storage.vector")
data class VectorStoreProperties(
    val host: String = "localhost",
    val grpcPort: Int = 6334,
    val apiKey: String? = null,
    val useTls: Boolean = false
)
```

- [ ] **Step 3: Create VectorStoreAutoConfiguration.kt**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStoreAutoConfiguration.kt`:

```kotlin
package com.agentwork.graphmesh.storage.vector

import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(VectorStoreProperties::class)
class VectorStoreAutoConfiguration {

    @Bean
    fun qdrantClient(props: VectorStoreProperties): QdrantClient {
        val builder = QdrantGrpcClient.newBuilder(props.host, props.grpcPort, props.useTls)
        props.apiKey?.let { builder.withApiKey(it) }
        return QdrantClient(builder.build())
    }

    @Bean
    fun vectorStore(qdrantClient: QdrantClient): VectorStore =
        QdrantVectorStore(qdrantClient)
}
```

- [ ] **Step 4: Verify — this will NOT compile yet (QdrantVectorStore missing)**

This is expected. Do not try to compile.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStore.kt \
        src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStoreProperties.kt \
        src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStoreAutoConfiguration.kt
git commit -m "feat(storage): add VectorStore interface, properties, and auto-configuration

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: QdrantVectorStore Implementation

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/vector/QdrantVectorStore.kt`

- [ ] **Step 1: Create QdrantVectorStore.kt**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/vector/QdrantVectorStore.kt`:

```kotlin
package com.agentwork.graphmesh.storage.vector

import io.qdrant.client.ConditionFactory
import io.qdrant.client.PointIdFactory
import io.qdrant.client.QdrantClient
import io.qdrant.client.ValueFactory
import io.qdrant.client.VectorsFactory
import io.qdrant.client.grpc.Collections.Distance
import io.qdrant.client.grpc.Collections.VectorParams
import io.qdrant.client.grpc.Points.Filter
import io.qdrant.client.grpc.Points.PointStruct
import io.qdrant.client.grpc.Points.SearchPoints
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class QdrantVectorStore(
    private val client: QdrantClient
) : VectorStore {

    private val log = LoggerFactory.getLogger(javaClass)
    private val knownCollections = ConcurrentHashMap.newKeySet<String>()

    override fun upsert(collection: String, points: List<VectorPoint>) {
        if (points.isEmpty()) return

        val dimension = points.first().dimension
        val physicalName = CollectionNaming.physicalName(collection, dimension)

        ensureCollection(physicalName, dimension)

        val pointStructs = points.map { point ->
            PointStruct.newBuilder()
                .setId(PointIdFactory.id(deterministicUuid(point.id)))
                .setVectors(VectorsFactory.vectors(point.vector))
                .putAllPayload(buildPayload(point.id, point.payload))
                .build()
        }

        client.upsertAsync(physicalName, pointStructs).get()
        log.debug("Upserted {} points into {}", points.size, physicalName)
    }

    override fun search(
        collection: String,
        queryVector: FloatArray,
        limit: Int,
        filter: VectorFilter?,
        scoreThreshold: Float?
    ): List<SearchResult> {
        val dimension = queryVector.size
        val physicalName = CollectionNaming.physicalName(collection, dimension)

        if (!collectionExists(collection, dimension)) {
            log.debug("Collection {} does not exist, returning empty results", physicalName)
            return emptyList()
        }

        val searchBuilder = SearchPoints.newBuilder()
            .setCollectionName(physicalName)
            .addAllVector(queryVector.toList())
            .setLimit(limit)
            .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(true))

        if (filter != null) {
            searchBuilder.setFilter(toQdrantFilter(filter))
        }

        if (scoreThreshold != null) {
            searchBuilder.setScoreThreshold(scoreThreshold)
        }

        val results = client.searchAsync(searchBuilder.build()).get()

        return results.map { scored ->
            val payload = scored.payloadMap
            val originalId = (payload["_original_id"]?.stringValue) ?: scored.id.uuid
            SearchResult(
                id = originalId,
                score = scored.score,
                payload = payload
                    .filterKeys { it != "_original_id" }
                    .mapValues { extractPayloadValue(it.value) }
            )
        }
    }

    override fun delete(collection: String, dimension: Int, ids: List<String>) {
        if (ids.isEmpty()) return
        val physicalName = CollectionNaming.physicalName(collection, dimension)
        val pointIds = ids.map { PointIdFactory.id(deterministicUuid(it)) }
        client.deleteAsync(physicalName, pointIds).get()
        log.debug("Deleted {} points from {}", ids.size, physicalName)
    }

    override fun deleteCollection(collection: String) {
        val allCollections = client.listCollectionsAsync().get()
        val prefix = CollectionNaming.prefixPattern(collection)
        val matching = allCollections.filter { it.startsWith(prefix) }

        matching.forEach { name ->
            client.deleteCollectionAsync(name).get()
            knownCollections.remove(name)
            log.info("Collection deleted: {}", name)
        }
        log.info("{} collection(s) deleted for '{}'", matching.size, collection)
    }

    override fun collectionExists(collection: String, dimension: Int): Boolean {
        val physicalName = CollectionNaming.physicalName(collection, dimension)
        if (physicalName in knownCollections) return true
        return client.collectionExistsAsync(physicalName).get()
    }

    @Synchronized
    private fun ensureCollection(physicalName: String, dimension: Int) {
        if (physicalName in knownCollections) return

        val exists = client.collectionExistsAsync(physicalName).get()
        if (!exists) {
            client.createCollectionAsync(
                physicalName,
                VectorParams.newBuilder()
                    .setSize(dimension.toLong())
                    .setDistance(Distance.Cosine)
                    .build()
            ).get()
            log.info("Collection created: {} (dimension={})", physicalName, dimension)
        }
        knownCollections.add(physicalName)
    }

    private fun deterministicUuid(id: String): UUID =
        UUID.nameUUIDFromBytes(id.toByteArray())

    private fun buildPayload(originalId: String, payload: Map<String, Any>): Map<String, io.qdrant.client.grpc.JsonWithInt.Value> {
        val result = mutableMapOf<String, io.qdrant.client.grpc.JsonWithInt.Value>()
        result["_original_id"] = ValueFactory.value(originalId)
        payload.forEach { (key, value) ->
            result[key] = when (value) {
                is String -> ValueFactory.value(value)
                is Int -> ValueFactory.value(value.toLong())
                is Long -> ValueFactory.value(value)
                is Double -> ValueFactory.value(value)
                is Float -> ValueFactory.value(value.toDouble())
                is Boolean -> ValueFactory.value(value)
                else -> ValueFactory.value(value.toString())
            }
        }
        return result
    }

    private fun extractPayloadValue(value: io.qdrant.client.grpc.JsonWithInt.Value): Any {
        return when {
            value.hasStringValue() -> value.stringValue
            value.hasIntegerValue() -> value.integerValue
            value.hasDoubleValue() -> value.doubleValue
            value.hasBoolValue() -> value.boolValue
            else -> value.stringValue
        }
    }

    private fun toQdrantFilter(filter: VectorFilter): Filter {
        return when (filter) {
            is VectorFilter.Equals -> {
                val condition = when (val v = filter.value) {
                    is String -> ConditionFactory.matchKeyword(filter.field, v)
                    is Int -> ConditionFactory.matchValue(filter.field, v.toLong())
                    is Long -> ConditionFactory.matchValue(filter.field, v)
                    is Boolean -> ConditionFactory.matchValue(filter.field, v)
                    else -> ConditionFactory.matchKeyword(filter.field, v.toString())
                }
                Filter.newBuilder().addMust(condition).build()
            }
            is VectorFilter.In -> {
                val values = filter.values
                val condition = if (values.all { it is String }) {
                    ConditionFactory.matchKeywords(filter.field, values.map { it as String })
                } else {
                    ConditionFactory.matchValues(filter.field, values.map { (it as Number).toLong() })
                }
                Filter.newBuilder().addMust(condition).build()
            }
            is VectorFilter.And -> {
                val builder = Filter.newBuilder()
                filter.filters.forEach { sub ->
                    val subFilter = toQdrantFilter(sub)
                    builder.addAllMust(subFilter.mustList)
                    builder.addAllMust(subFilter.shouldList.map { it }) // flatten nested
                }
                builder.build()
            }
            is VectorFilter.Or -> {
                val builder = Filter.newBuilder()
                filter.filters.forEach { sub ->
                    val subFilter = toQdrantFilter(sub)
                    builder.addAllShould(subFilter.mustList)
                }
                builder.build()
            }
            is VectorFilter.Not -> {
                val subFilter = toQdrantFilter(filter.filter)
                Filter.newBuilder().addAllMustNot(subFilter.mustList).build()
            }
        }
    }
}
```

- [ ] **Step 2: Verify full project compiles**

Run: `./gradlew compileKotlin`

Expected: BUILD SUCCESSFUL

If there are compile errors due to Qdrant client API differences (e.g. method names, imports), fix them. The key imports are:
- `io.qdrant.client.QdrantClient`
- `io.qdrant.client.PointIdFactory.id`
- `io.qdrant.client.ValueFactory.value`
- `io.qdrant.client.VectorsFactory.vectors`
- `io.qdrant.client.ConditionFactory.*`
- `io.qdrant.client.WithPayloadSelectorFactory`
- `io.qdrant.client.grpc.Collections.*`
- `io.qdrant.client.grpc.Points.*`
- `io.qdrant.client.grpc.JsonWithInt.Value`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/vector/QdrantVectorStore.kt
git commit -m "feat(storage): implement QdrantVectorStore with lazy collection creation

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Integration Tests

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/storage/vector/QdrantVectorStoreIntegrationTest.kt`

**Prerequisite:** Qdrant must be running via `docker compose up -d qdrant`.

- [ ] **Step 1: Write integration tests**

Create `src/test/kotlin/com/agentwork/graphmesh/storage/vector/QdrantVectorStoreIntegrationTest.kt`:

```kotlin
package com.agentwork.graphmesh.storage.vector

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    ]
)
@ActiveProfiles("test")
class QdrantVectorStoreIntegrationTest {

    @Autowired
    lateinit var vectorStore: VectorStore

    private lateinit var collection: String

    @BeforeEach
    fun setUp() {
        collection = "test-${UUID.randomUUID()}"
    }

    @AfterEach
    fun tearDown() {
        vectorStore.deleteCollection(collection)
    }

    @Test
    fun `upsert and search roundtrip`() {
        val points = listOf(
            VectorPoint("doc1", floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)),
            VectorPoint("doc2", floatArrayOf(0.9f, 0.1f, 0.0f, 0.0f)),
            VectorPoint("doc3", floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f))
        )
        vectorStore.upsert(collection, points)

        val results = vectorStore.search(
            collection,
            queryVector = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f),
            limit = 3
        )

        assertEquals(3, results.size)
        assertEquals("doc1", results[0].id)
        assertTrue(results[0].score > results[2].score)
    }

    @Test
    fun `search with equals filter`() {
        val points = listOf(
            VectorPoint("doc1", floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f), mapOf("category" to "science")),
            VectorPoint("doc2", floatArrayOf(0.9f, 0.1f, 0.0f, 0.0f), mapOf("category" to "arts")),
            VectorPoint("doc3", floatArrayOf(0.8f, 0.2f, 0.0f, 0.0f), mapOf("category" to "science"))
        )
        vectorStore.upsert(collection, points)

        val results = vectorStore.search(
            collection,
            queryVector = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f),
            limit = 10,
            filter = VectorFilter.Equals("category", "science")
        )

        assertEquals(2, results.size)
        assertTrue(results.all { it.payload["category"] == "science" })
    }

    @Test
    fun `search on non-existent collection returns empty list`() {
        val results = vectorStore.search(
            "nonexistent",
            queryVector = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f),
            limit = 10
        )
        assertEquals(0, results.size)
    }

    @Test
    fun `delete removes points`() {
        val points = listOf(
            VectorPoint("doc1", floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)),
            VectorPoint("doc2", floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f))
        )
        vectorStore.upsert(collection, points)

        vectorStore.delete(collection, 4, listOf("doc1"))

        val results = vectorStore.search(
            collection,
            queryVector = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f),
            limit = 10
        )
        assertEquals(1, results.size)
        assertEquals("doc2", results[0].id)
    }

    @Test
    fun `deleteCollection removes all dimension variants`() {
        val points4d = listOf(VectorPoint("a", floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)))
        val points8d = listOf(VectorPoint("b", floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)))

        vectorStore.upsert(collection, points4d)
        vectorStore.upsert(collection, points8d)

        assertTrue(vectorStore.collectionExists(collection, 4))
        assertTrue(vectorStore.collectionExists(collection, 8))

        vectorStore.deleteCollection(collection)

        assertFalse(vectorStore.collectionExists(collection, 4))
        assertFalse(vectorStore.collectionExists(collection, 8))
    }

    @Test
    fun `different dimensions coexist under same logical name`() {
        val points4d = listOf(VectorPoint("small", floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)))
        val points8d = listOf(VectorPoint("large", floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)))

        vectorStore.upsert(collection, points4d)
        vectorStore.upsert(collection, points8d)

        val results4d = vectorStore.search(collection, floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f), limit = 10)
        val results8d = vectorStore.search(collection, floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f), limit = 10)

        assertEquals(1, results4d.size)
        assertEquals("small", results4d[0].id)
        assertEquals(1, results8d.size)
        assertEquals("large", results8d[0].id)
    }

    @Test
    fun `collectionExists returns true after upsert`() {
        assertFalse(vectorStore.collectionExists(collection, 4))

        vectorStore.upsert(collection, listOf(
            VectorPoint("doc1", floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f))
        ))

        assertTrue(vectorStore.collectionExists(collection, 4))
    }

    @Test
    fun `ensureCollection is idempotent`() {
        vectorStore.upsert(collection, listOf(
            VectorPoint("doc1", floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f))
        ))
        // Second upsert should not fail due to collection already existing
        vectorStore.upsert(collection, listOf(
            VectorPoint("doc2", floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f))
        ))

        val results = vectorStore.search(collection, floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f), limit = 10)
        assertEquals(2, results.size)
    }

    @Test
    fun `search with score threshold filters low scores`() {
        val points = listOf(
            VectorPoint("similar", floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)),
            VectorPoint("different", floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f))
        )
        vectorStore.upsert(collection, points)

        val results = vectorStore.search(
            collection,
            queryVector = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f),
            limit = 10,
            scoreThreshold = 0.9f
        )

        assertEquals(1, results.size)
        assertEquals("similar", results[0].id)
    }

    @Test
    fun `upsert preserves payload metadata`() {
        val points = listOf(
            VectorPoint("doc1", floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f), mapOf("title" to "Hello", "count" to 42))
        )
        vectorStore.upsert(collection, points)

        val results = vectorStore.search(collection, floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f), limit = 1)

        assertEquals(1, results.size)
        assertEquals("Hello", results[0].payload["title"])
        assertEquals(42L, results[0].payload["count"])
    }
}
```

- [ ] **Step 2: Run integration tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.vector.QdrantVectorStoreIntegrationTest"`

Expected: BUILD SUCCESSFUL, all 10 tests pass.

If tests fail due to Qdrant client API issues (e.g. wrong method signatures, missing factory methods), fix the `QdrantVectorStore.kt` implementation and re-run.

- [ ] **Step 3: Run all tests**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL, all existing tests still pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/storage/vector/QdrantVectorStoreIntegrationTest.kt
git commit -m "test(storage): add Qdrant vector store integration tests

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```
