# Cassandra Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement an entity-centric Cassandra storage layer with a 2-table design that serves all 16 RDF quad query patterns via single-partition reads.

**Architecture:** `CqlTemplate` with prepared statements for all operations. Each quad `(D, S, P, O)` writes 5 rows: 4 in `quads_by_entity` (one per entity role) + 1 in `quads_by_collection`. Query routing selects the optimal entity/role based on which fields are known (S > O > P > D priority). Schema initialization via `@Configuration` bean at startup.

**Tech Stack:** Spring Boot 4.0.5, Spring Data Cassandra (`CqlTemplate`), Cassandra 5.0 (docker-compose), Kotlin

---

## File Structure

### Created

| File | Responsibility |
|---|---|
| `src/main/kotlin/.../storage/StoredQuad.kt` | `StoredQuad` data class, `ObjectType` enum, `QuadQuery` data class |
| `src/main/kotlin/.../storage/QuadStoreService.kt` | `@Service` with CqlTemplate: insert, delete, query (16 patterns) |
| `src/main/kotlin/.../storage/CassandraSchemaInitializer.kt` | `@Configuration`: keyspace + table creation at startup |
| `src/test/kotlin/.../storage/StoredQuadTest.kt` | Unit tests for data classes |
| `src/test/kotlin/.../storage/QuadStoreIntegrationTest.kt` | Integration tests: CRUD + all 16 query patterns |

### Modified

| File | Change |
|---|---|
| `docker-compose.yaml` | Add Cassandra service |
| `src/main/resources/application.yml` | Add `spring.cassandra.*` properties |
| `src/test/resources/application-test.yml` | Add Cassandra test config |
| `docs/features/02-cassandra-storage.md` | Update to reflect simplified implementation |

All paths use `...` as shorthand for `com/agentwork/graphmesh`.

---

### Task 1: Add Cassandra to docker-compose and configuration

**Files:**
- Modify: `docker-compose.yaml`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`

- [ ] **Step 1: Add Cassandra service to docker-compose.yaml**

Append after the `schema-registry` service:

```yaml
  cassandra:
    image: cassandra:5.0
    hostname: cassandra
    ports:
      - "9042:9042"
    environment:
      CASSANDRA_CLUSTER_NAME: graphmesh
      CASSANDRA_DC: datacenter1
      HEAP_NEWSIZE: 128M
      MAX_HEAP_SIZE: 512M
```

- [ ] **Step 2: Add Cassandra config to application.yml**

Append to `src/main/resources/application.yml` after the kafka section:

```yaml
  cassandra:
    contact-points: ${CASSANDRA_CONTACT_POINTS:localhost}
    port: ${CASSANDRA_PORT:9042}
    local-datacenter: datacenter1
    keyspace-name: graphmesh
    schema-action: none
```

Note: `schema-action: none` because we manage schema ourselves via `CassandraSchemaInitializer`.

- [ ] **Step 3: Add Cassandra config to application-test.yml**

Append to `src/test/resources/application-test.yml`:

```yaml
  cassandra:
    contact-points: localhost
    port: 9042
    local-datacenter: datacenter1
    keyspace-name: graphmesh_test
    schema-action: none
```

Uses `graphmesh_test` keyspace to isolate test data.

- [ ] **Step 4: Start Cassandra and verify**

Run: `docker compose up -d && sleep 30 && docker compose ps`
Expected: All three services (kafka, schema-registry, cassandra) running. Cassandra needs ~30s to start.

Run: `docker compose exec cassandra cqlsh -e "DESCRIBE KEYSPACES;"`
Expected: Output showing system keyspaces (system, system_auth, etc.).

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yaml src/main/resources/application.yml src/test/resources/application-test.yml
git commit -m "infra: add Cassandra to docker-compose and Spring Boot configuration"
```

---

### Task 2: Data model — StoredQuad, ObjectType, QuadQuery

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/StoredQuad.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/storage/StoredQuadTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/agentwork/graphmesh/storage/StoredQuadTest.kt`:

```kotlin
package com.agentwork.graphmesh.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoredQuadTest {

    @Test
    fun `StoredQuad default values`() {
        val quad = StoredQuad(
            subject = "http://example.org/alice",
            predicate = "http://xmlns.com/foaf/0.1/knows",
            objectValue = "http://example.org/bob",
            dataset = "http://example.org/graph1"
        )

        assertEquals(ObjectType.URI, quad.objectType)
        assertEquals("", quad.datatype)
        assertEquals("", quad.language)
    }

    @Test
    fun `StoredQuad with literal object`() {
        val quad = StoredQuad(
            subject = "http://example.org/alice",
            predicate = "http://xmlns.com/foaf/0.1/name",
            objectValue = "Alice",
            dataset = "http://example.org/graph1",
            objectType = ObjectType.LITERAL,
            datatype = "http://www.w3.org/2001/XMLSchema#string",
            language = "en"
        )

        assertEquals(ObjectType.LITERAL, quad.objectType)
        assertEquals("http://www.w3.org/2001/XMLSchema#string", quad.datatype)
        assertEquals("en", quad.language)
    }

    @Test
    fun `ObjectType enum codes`() {
        assertEquals("U", ObjectType.URI.code)
        assertEquals("L", ObjectType.LITERAL.code)
        assertEquals("T", ObjectType.QUOTED_TRIPLE.code)
    }

    @Test
    fun `ObjectType fromCode`() {
        assertEquals(ObjectType.URI, ObjectType.fromCode("U"))
        assertEquals(ObjectType.LITERAL, ObjectType.fromCode("L"))
        assertEquals(ObjectType.QUOTED_TRIPLE, ObjectType.fromCode("T"))
    }

    @Test
    fun `QuadQuery all wildcards`() {
        val query = QuadQuery()

        assertNull(query.subject)
        assertNull(query.predicate)
        assertNull(query.objectValue)
        assertNull(query.dataset)
    }

    @Test
    fun `QuadQuery with specific fields`() {
        val query = QuadQuery(
            subject = "http://example.org/alice",
            predicate = "http://xmlns.com/foaf/0.1/knows"
        )

        assertEquals("http://example.org/alice", query.subject)
        assertEquals("http://xmlns.com/foaf/0.1/knows", query.predicate)
        assertNull(query.objectValue)
        assertNull(query.dataset)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.StoredQuadTest" --info 2>&1 | tail -20`
Expected: FAIL — `StoredQuad` does not exist yet.

- [ ] **Step 3: Implement data model**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/StoredQuad.kt`:

```kotlin
package com.agentwork.graphmesh.storage

data class StoredQuad(
    val subject: String,
    val predicate: String,
    val objectValue: String,
    val dataset: String,
    val objectType: ObjectType = ObjectType.URI,
    val datatype: String = "",
    val language: String = ""
)

enum class ObjectType(val code: String) {
    URI("U"),
    LITERAL("L"),
    QUOTED_TRIPLE("T");

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: String): ObjectType = byCode.getValue(code)
    }
}

data class QuadQuery(
    val subject: String? = null,
    val predicate: String? = null,
    val objectValue: String? = null,
    val dataset: String? = null,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.StoredQuadTest" --info 2>&1 | tail -20`
Expected: PASS — all 6 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/StoredQuad.kt \
        src/test/kotlin/com/agentwork/graphmesh/storage/StoredQuadTest.kt
git commit -m "feat(storage): add StoredQuad, ObjectType, and QuadQuery data model"
```

---

### Task 3: CassandraSchemaInitializer

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/CassandraSchemaInitializer.kt`

- [ ] **Step 1: Create CassandraSchemaInitializer**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/CassandraSchemaInitializer.kt`:

```kotlin
package com.agentwork.graphmesh.storage

import com.datastax.oss.driver.api.core.CqlSession
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener

@Configuration
class CassandraSchemaInitializer(
    private val session: CqlSession,
    @Value("\${spring.cassandra.keyspace-name:graphmesh}") private val keyspace: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun initializeSchema() {
        createKeyspace()
        createTables()
        logger.info("Cassandra schema initialized for keyspace '{}'", keyspace)
    }

    private fun createKeyspace() {
        session.execute("""
            CREATE KEYSPACE IF NOT EXISTS $keyspace
            WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
        """.trimIndent())
    }

    private fun createTables() {
        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.quads_by_entity (
                collection text,
                entity     text,
                role       text,
                p          text,
                otype      text,
                s          text,
                o          text,
                d          text,
                dtype      text,
                lang       text,
                PRIMARY KEY ((collection, entity), role, p, otype, s, o, d, dtype, lang)
            )
        """.trimIndent())

        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.quads_by_collection (
                collection text,
                d          text,
                s          text,
                p          text,
                o          text,
                otype      text,
                dtype      text,
                lang       text,
                PRIMARY KEY (collection, d, s, p, o, otype, dtype, lang)
            )
        """.trimIndent())
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/CassandraSchemaInitializer.kt
git commit -m "feat(storage): add CassandraSchemaInitializer for keyspace and table creation"
```

---

### Task 4: QuadStoreService — insert and delete

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt`

- [ ] **Step 1: Create QuadStoreService with insert and delete**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt`:

```kotlin
package com.agentwork.graphmesh.storage

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BatchType
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.cassandra.core.cql.CqlTemplate
import org.springframework.stereotype.Service

@Service
class QuadStoreService(
    private val cql: CqlTemplate,
    private val session: CqlSession,
    @Value("\${spring.cassandra.keyspace-name:graphmesh}") private val keyspace: String
) {

    private lateinit var insertEntity: PreparedStatement
    private lateinit var insertCollection: PreparedStatement
    private lateinit var deleteEntity: PreparedStatement
    private lateinit var deleteCollection: PreparedStatement
    private lateinit var selectCollection: PreparedStatement
    private lateinit var deleteCollectionPartition: PreparedStatement

    // Query prepared statements: indexed by pattern number (1-16)
    private lateinit var queryStatements: Map<Int, PreparedStatement>

    @jakarta.annotation.PostConstruct
    fun prepareStatements() {
        insertEntity = session.prepare("""
            INSERT INTO $keyspace.quads_by_entity
                (collection, entity, role, p, otype, s, o, d, dtype, lang)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())

        insertCollection = session.prepare("""
            INSERT INTO $keyspace.quads_by_collection
                (collection, d, s, p, o, otype, dtype, lang)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())

        deleteEntity = session.prepare("""
            DELETE FROM $keyspace.quads_by_entity
            WHERE collection = ? AND entity = ? AND role = ?
                AND p = ? AND otype = ? AND s = ? AND o = ? AND d = ? AND dtype = ? AND lang = ?
        """.trimIndent())

        deleteCollection = session.prepare("""
            DELETE FROM $keyspace.quads_by_collection
            WHERE collection = ? AND d = ? AND s = ? AND p = ? AND o = ? AND otype = ? AND dtype = ? AND lang = ?
        """.trimIndent())

        selectCollection = session.prepare("""
            SELECT d, s, p, o, otype, dtype, lang FROM $keyspace.quads_by_collection
            WHERE collection = ?
        """.trimIndent())

        deleteCollectionPartition = session.prepare("""
            DELETE FROM $keyspace.quads_by_entity
            WHERE collection = ? AND entity = ?
        """.trimIndent())

        prepareQueryStatements()
    }

    fun insert(collection: String, quad: StoredQuad) {
        val batch = BatchStatement.builder(BatchType.LOGGED)
        addInsertToBatch(batch, collection, quad)
        session.execute(batch.build())
    }

    fun insertBatch(collection: String, quads: List<StoredQuad>) {
        val batch = BatchStatement.builder(BatchType.LOGGED)
        quads.forEach { addInsertToBatch(batch, collection, it) }
        session.execute(batch.build())
    }

    fun delete(collection: String, quad: StoredQuad) {
        val batch = BatchStatement.builder(BatchType.LOGGED)
        addDeleteToBatch(batch, collection, quad)
        session.execute(batch.build())
    }

    fun deleteCollection(collection: String) {
        // 1. Read all quads from collection manifest
        val rows = session.execute(selectCollection.bind(collection))
        val entities = mutableSetOf<String>()
        val deleteBatch = BatchStatement.builder(BatchType.LOGGED)

        for (row in rows) {
            val s = row.getString("s")!!
            val p = row.getString("p")!!
            val o = row.getString("o")!!
            val d = row.getString("d")!!
            entities.addAll(listOf(s, p, o, d))
        }

        // 2. Delete all entity partitions
        for (entity in entities) {
            session.execute(deleteCollectionPartition.bind(collection, entity))
        }

        // 3. Delete collection partition
        session.execute("DELETE FROM $keyspace.quads_by_collection WHERE collection = ?", collection)
    }

    private fun addInsertToBatch(
        batch: BatchStatement.BatchStatementBuilder,
        collection: String,
        quad: StoredQuad
    ) {
        val (s, p, o, d) = quad
        val otype = quad.objectType.code
        val dtype = quad.datatype
        val lang = quad.language

        // 4 rows in quads_by_entity (one per entity role)
        batch.addStatement(insertEntity.bind(collection, s, "S", p, otype, s, o, d, dtype, lang))
        batch.addStatement(insertEntity.bind(collection, p, "P", p, otype, s, o, d, dtype, lang))
        batch.addStatement(insertEntity.bind(collection, o, "O", p, otype, s, o, d, dtype, lang))
        batch.addStatement(insertEntity.bind(collection, d, "G", p, otype, s, o, d, dtype, lang))

        // 1 row in quads_by_collection
        batch.addStatement(insertCollection.bind(collection, d, s, p, o, otype, dtype, lang))
    }

    private fun addDeleteToBatch(
        batch: BatchStatement.BatchStatementBuilder,
        collection: String,
        quad: StoredQuad
    ) {
        val (s, p, o, d) = quad
        val otype = quad.objectType.code
        val dtype = quad.datatype
        val lang = quad.language

        batch.addStatement(deleteEntity.bind(collection, s, "S", p, otype, s, o, d, dtype, lang))
        batch.addStatement(deleteEntity.bind(collection, p, "P", p, otype, s, o, d, dtype, lang))
        batch.addStatement(deleteEntity.bind(collection, o, "O", p, otype, s, o, d, dtype, lang))
        batch.addStatement(deleteEntity.bind(collection, d, "G", p, otype, s, o, d, dtype, lang))

        batch.addStatement(deleteCollection.bind(collection, d, s, p, o, otype, dtype, lang))
    }

    // Query methods added in Task 5
    fun query(collection: String, query: QuadQuery): List<StoredQuad> {
        return emptyList() // Placeholder — implemented in Task 5
    }

    private fun prepareQueryStatements() {
        // Implemented in Task 5
        queryStatements = emptyMap()
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt
git commit -m "feat(storage): add QuadStoreService with insert, delete, and batch operations"
```

---

### Task 5: QuadStoreService — query (16 patterns)

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt`

- [ ] **Step 1: Replace `prepareQueryStatements()` and `query()` with full implementation**

In `QuadStoreService.kt`, replace the placeholder `prepareQueryStatements()` method with:

```kotlin
    private fun prepareQueryStatements() {
        val base = "SELECT s, p, o, d, otype, dtype, lang FROM $keyspace.quads_by_entity WHERE collection = ? AND entity = ? AND role = ?"

        queryStatements = mapOf(
            // Patterns 1-8: S known → entity=S, role='S'
            1  to session.prepare("$base AND p = ? AND o = ? AND d = ?"),   // S,P,O,D
            2  to session.prepare("$base AND p = ? AND o = ?"),             // S,P,O,?
            3  to session.prepare("$base AND p = ?"),                       // S,P,?,D (filter d in-memory)
            4  to session.prepare("$base AND p = ?"),                       // S,P,?,?
            5  to session.prepare("$base"),                                 // S,?,O,D (filter o,d in-memory)
            6  to session.prepare("$base"),                                 // S,?,O,? (filter o in-memory)
            7  to session.prepare("$base"),                                 // S,?,?,D (filter d in-memory)
            8  to session.prepare("$base"),                                 // S,?,?,?

            // Patterns 9-10: O known (S unknown) → entity=O, role='O'
            9  to session.prepare("$base AND p = ?"),                       // ?,P,O,D (filter d in-memory)
            10 to session.prepare("$base AND p = ?"),                       // ?,P,O,?

            // Patterns 11-12: P known (S,O unknown) → entity=P, role='P'
            11 to session.prepare("$base"),                                 // ?,P,?,D (filter d in-memory)
            12 to session.prepare("$base"),                                 // ?,P,?,?

            // Patterns 13-14: O known (S,P unknown) → entity=O, role='O'
            13 to session.prepare("$base"),                                 // ?,?,O,D (filter d in-memory)
            14 to session.prepare("$base"),                                 // ?,?,O,?

            // Pattern 15: D known → entity=D, role='G'
            15 to session.prepare("$base"),                                 // ?,?,?,D

            // Pattern 16: all wildcard → quads_by_collection
            16 to session.prepare("SELECT s, p, o, d, otype, dtype, lang FROM $keyspace.quads_by_collection WHERE collection = ?")
        )
    }
```

Replace the placeholder `query()` method with:

```kotlin
    fun query(collection: String, query: QuadQuery): List<StoredQuad> {
        val (s, p, o, d) = query
        val pattern = resolvePattern(s, p, o, d)
        val stmt = queryStatements.getValue(pattern)

        val bound = when (pattern) {
            1  -> stmt.bind(collection, s, "S", p, o, d)
            2  -> stmt.bind(collection, s, "S", p, o)
            3  -> stmt.bind(collection, s, "S", p)
            4  -> stmt.bind(collection, s, "S", p)
            5  -> stmt.bind(collection, s, "S")
            6  -> stmt.bind(collection, s, "S")
            7  -> stmt.bind(collection, s, "S")
            8  -> stmt.bind(collection, s, "S")
            9  -> stmt.bind(collection, o, "O", p)
            10 -> stmt.bind(collection, o, "O", p)
            11 -> stmt.bind(collection, p, "P")
            12 -> stmt.bind(collection, p, "P")
            13 -> stmt.bind(collection, o, "O")
            14 -> stmt.bind(collection, o, "O")
            15 -> stmt.bind(collection, d, "G")
            16 -> stmt.bind(collection)
            else -> error("Unknown pattern: $pattern")
        }

        val rows = session.execute(bound)
        return rows.mapNotNull { row ->
            val quad = StoredQuad(
                subject = row.getString("s")!!,
                predicate = row.getString("p")!!,
                objectValue = row.getString("o")!!,
                dataset = row.getString("d")!!,
                objectType = ObjectType.fromCode(row.getString("otype")!!),
                datatype = row.getString("dtype") ?: "",
                language = row.getString("lang") ?: ""
            )
            // In-memory filter for fields not covered by CQL WHERE clause
            if (matchesFilter(quad, s, p, o, d)) quad else null
        }
    }

    private fun resolvePattern(s: String?, p: String?, o: String?, d: String?): Int {
        val known = listOf(s != null, p != null, o != null, d != null)
        return when (known) {
            listOf(true, true, true, true)    -> 1
            listOf(true, true, true, false)   -> 2
            listOf(true, true, false, true)   -> 3
            listOf(true, true, false, false)  -> 4
            listOf(true, false, true, true)   -> 5
            listOf(true, false, true, false)  -> 6
            listOf(true, false, false, true)  -> 7
            listOf(true, false, false, false) -> 8
            listOf(false, true, true, true)   -> 9
            listOf(false, true, true, false)  -> 10
            listOf(false, true, false, true)  -> 11
            listOf(false, true, false, false) -> 12
            listOf(false, false, true, true)  -> 13
            listOf(false, false, true, false) -> 14
            listOf(false, false, false, true) -> 15
            listOf(false, false, false, false) -> 16
            else -> error("Invalid pattern")
        }
    }

    private fun matchesFilter(quad: StoredQuad, s: String?, p: String?, o: String?, d: String?): Boolean {
        if (s != null && quad.subject != s) return false
        if (p != null && quad.predicate != p) return false
        if (o != null && quad.objectValue != o) return false
        if (d != null && quad.dataset != d) return false
        return true
    }
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt
git commit -m "feat(storage): implement all 16 query patterns with prepared statements"
```

---

### Task 6: Integration tests

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreIntegrationTest.kt`

Prerequisite: `docker compose up -d` must be running with Cassandra available on `localhost:9042`.

- [ ] **Step 1: Write integration test**

Create `src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreIntegrationTest.kt`:

```kotlin
package com.agentwork.graphmesh.storage

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration," +
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    ]
)
@ActiveProfiles("test")
class QuadStoreIntegrationTest {

    @Autowired
    lateinit var store: QuadStoreService

    private lateinit var collection: String

    // Test entities
    private val alice = "http://example.org/alice"
    private val bob = "http://example.org/bob"
    private val charlie = "http://example.org/charlie"
    private val knows = "http://xmlns.com/foaf/0.1/knows"
    private val name = "http://xmlns.com/foaf/0.1/name"
    private val age = "http://xmlns.com/foaf/0.1/age"
    private val graph1 = "http://example.org/graph1"
    private val graph2 = "http://example.org/graph2"

    @BeforeEach
    fun setUp() {
        collection = "test-${UUID.randomUUID()}"
    }

    // --- Insert / Delete ---

    @Test
    fun `insert and query back a single quad`() {
        val quad = StoredQuad(alice, knows, bob, graph1)
        store.insert(collection, quad)

        val result = store.query(collection, QuadQuery(subject = alice))
        assertEquals(1, result.size)
        assertEquals(quad, result.first())
    }

    @Test
    fun `insert writes 4+1 rows verifiable via different query patterns`() {
        val quad = StoredQuad(alice, knows, bob, graph1)
        store.insert(collection, quad)

        // Query via subject (entity=alice, role=S)
        assertEquals(1, store.query(collection, QuadQuery(subject = alice)).size)
        // Query via predicate (entity=knows, role=P)
        assertEquals(1, store.query(collection, QuadQuery(predicate = knows)).size)
        // Query via object (entity=bob, role=O)
        assertEquals(1, store.query(collection, QuadQuery(objectValue = bob)).size)
        // Query via dataset (entity=graph1, role=G)
        assertEquals(1, store.query(collection, QuadQuery(dataset = graph1)).size)
        // Query via collection manifest (all wildcards)
        assertEquals(1, store.query(collection, QuadQuery()).size)
    }

    @Test
    fun `delete removes quad from all patterns`() {
        val quad = StoredQuad(alice, knows, bob, graph1)
        store.insert(collection, quad)
        store.delete(collection, quad)

        assertEquals(0, store.query(collection, QuadQuery(subject = alice)).size)
        assertEquals(0, store.query(collection, QuadQuery(predicate = knows)).size)
        assertEquals(0, store.query(collection, QuadQuery(objectValue = bob)).size)
        assertEquals(0, store.query(collection, QuadQuery(dataset = graph1)).size)
        assertEquals(0, store.query(collection, QuadQuery()).size)
    }

    @Test
    fun `insertBatch writes multiple quads atomically`() {
        val quads = listOf(
            StoredQuad(alice, knows, bob, graph1),
            StoredQuad(alice, knows, charlie, graph1),
            StoredQuad(bob, knows, charlie, graph2)
        )
        store.insertBatch(collection, quads)

        assertEquals(2, store.query(collection, QuadQuery(subject = alice)).size)
        assertEquals(3, store.query(collection, QuadQuery(predicate = knows)).size)
        assertEquals(3, store.query(collection, QuadQuery()).size)
    }

    @Test
    fun `deleteCollection removes all data`() {
        val quads = listOf(
            StoredQuad(alice, knows, bob, graph1),
            StoredQuad(bob, knows, charlie, graph1)
        )
        store.insertBatch(collection, quads)

        store.deleteCollection(collection)

        assertEquals(0, store.query(collection, QuadQuery()).size)
        assertEquals(0, store.query(collection, QuadQuery(subject = alice)).size)
        assertEquals(0, store.query(collection, QuadQuery(subject = bob)).size)
    }

    @Test
    fun `literal object with datatype and language`() {
        val quad = StoredQuad(
            subject = alice,
            predicate = name,
            objectValue = "Alice",
            dataset = graph1,
            objectType = ObjectType.LITERAL,
            datatype = "http://www.w3.org/2001/XMLSchema#string",
            language = "en"
        )
        store.insert(collection, quad)

        val result = store.query(collection, QuadQuery(subject = alice, predicate = name))
        assertEquals(1, result.size)
        assertEquals(ObjectType.LITERAL, result.first().objectType)
        assertEquals("http://www.w3.org/2001/XMLSchema#string", result.first().datatype)
        assertEquals("en", result.first().language)
    }

    // --- 16 Query Patterns ---

    private fun insertTestData() {
        store.insertBatch(collection, listOf(
            StoredQuad(alice, knows, bob, graph1),
            StoredQuad(alice, knows, charlie, graph1),
            StoredQuad(alice, name, "Alice", graph1, ObjectType.LITERAL, "http://www.w3.org/2001/XMLSchema#string", "en"),
            StoredQuad(bob, knows, charlie, graph2),
            StoredQuad(bob, age, "30", graph2, ObjectType.LITERAL, "http://www.w3.org/2001/XMLSchema#int", ""),
        ))
    }

    @Test
    fun `pattern 1 - S,P,O,D all known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(alice, knows, bob, graph1))
        assertEquals(1, result.size)
        assertEquals(alice, result.first().subject)
    }

    @Test
    fun `pattern 2 - S,P,O known, D wildcard`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(alice, knows, bob))
        assertEquals(1, result.size)
    }

    @Test
    fun `pattern 3 - S,P,D known, O wildcard`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(subject = alice, predicate = knows, dataset = graph1))
        assertEquals(2, result.size)
    }

    @Test
    fun `pattern 4 - S,P known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(subject = alice, predicate = knows))
        assertEquals(2, result.size)
    }

    @Test
    fun `pattern 5 - S,O,D known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(subject = alice, objectValue = bob, dataset = graph1))
        assertEquals(1, result.size)
    }

    @Test
    fun `pattern 6 - S,O known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(subject = alice, objectValue = bob))
        assertEquals(1, result.size)
    }

    @Test
    fun `pattern 7 - S,D known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(subject = alice, dataset = graph1))
        assertEquals(3, result.size)
    }

    @Test
    fun `pattern 8 - S known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(subject = alice))
        assertEquals(3, result.size)
    }

    @Test
    fun `pattern 9 - P,O,D known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(predicate = knows, objectValue = charlie, dataset = graph1))
        assertEquals(1, result.size)
    }

    @Test
    fun `pattern 10 - P,O known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(predicate = knows, objectValue = charlie))
        assertEquals(2, result.size)
    }

    @Test
    fun `pattern 11 - P,D known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(predicate = knows, dataset = graph1))
        assertEquals(2, result.size)
    }

    @Test
    fun `pattern 12 - P known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(predicate = knows))
        assertEquals(3, result.size)
    }

    @Test
    fun `pattern 13 - O,D known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(objectValue = charlie, dataset = graph1))
        assertEquals(1, result.size)
    }

    @Test
    fun `pattern 14 - O known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(objectValue = charlie))
        assertEquals(2, result.size)
    }

    @Test
    fun `pattern 15 - D known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(dataset = graph1))
        assertEquals(3, result.size)
    }

    @Test
    fun `pattern 16 - all wildcards`() {
        insertTestData()
        val result = store.query(collection, QuadQuery())
        assertEquals(5, result.size)
    }
}
```

- [ ] **Step 2: Ensure docker-compose is running**

Run: `docker compose up -d && sleep 5 && docker compose ps`
Expected: All services running including cassandra.

- [ ] **Step 3: Run integration tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.*" --info 2>&1 | tail -30`
Expected: PASS — all tests green (6 unit + 22 integration).

Note: If Cassandra needs more startup time, wait and retry. If Spring context fails due to missing keyspace, the `CassandraSchemaInitializer` should handle it — but Spring Data Cassandra may try to connect to the keyspace before the initializer runs. In that case, the test may need `spring.cassandra.keyspace-name` set to the system keyspace initially, or the keyspace must be pre-created. The implementer should handle this if it arises.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreIntegrationTest.kt
git commit -m "test(storage): add integration tests for CRUD and all 16 query patterns"
```

---

### Task 7: Update feature documentation

**Files:**
- Modify: `docs/features/02-cassandra-storage.md`

- [ ] **Step 1: Read current feature doc**

Read `docs/features/02-cassandra-storage.md` to understand current structure.

- [ ] **Step 2: Update feature doc**

Update the document to reflect the simplified implementation. Key changes:
- Replace `QuadStore` interface with direct `QuadStoreService` `@Service`
- Replace `CassandraClient` wrapper with direct `CqlTemplate` + `CqlSession`
- Remove `suspend fun` (coroutines) — all methods are synchronous
- Remove separate Gradle submodule — Spring Modulith package under `com.agentwork.graphmesh.storage`
- Remove custom `GraphMeshCassandraProperties` — standard `spring.cassandra.*`
- Remove `AutoConfiguration.imports` file — not needed with package scanning
- Replace Testcontainers with docker-compose
- Update acceptance criteria to match new implementation
- Keep the entity-centric 2-table design, 16 query patterns, and data model unchanged

Preserve the document's existing structure and language (German). Update content, don't rewrite from scratch.

- [ ] **Step 3: Commit**

```bash
git add docs/features/02-cassandra-storage.md
git commit -m "docs(storage): update feature spec for simplified CqlTemplate implementation"
```

---

### Task 8: Final verification

- [ ] **Step 1: Ensure docker-compose is running**

Run: `docker compose ps`
Expected: All services running.

- [ ] **Step 2: Run full build with tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.*" 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Verify file count**

Run: `find src/main/kotlin/com/agentwork/graphmesh/storage -name "*.kt" | sort && find src/test/kotlin/com/agentwork/graphmesh/storage -name "*.kt" | sort`

Expected:
```
src/main/kotlin/.../storage/CassandraSchemaInitializer.kt
src/main/kotlin/.../storage/QuadStoreService.kt
src/main/kotlin/.../storage/StoredQuad.kt
src/test/kotlin/.../storage/QuadStoreIntegrationTest.kt
src/test/kotlin/.../storage/StoredQuadTest.kt
```

3 production files + 2 test files. Done.
