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
    lateinit var store: CassandraQuadStore

    private lateinit var collection: String

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

    // --- CRUD Tests ---

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
        assertEquals(1, store.query(collection, QuadQuery(subject = alice)).size)
        assertEquals(1, store.query(collection, QuadQuery(predicate = knows)).size)
        assertEquals(1, store.query(collection, QuadQuery(objectValue = bob)).size)
        assertEquals(1, store.query(collection, QuadQuery(dataset = graph1)).size)
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
            subject = alice, predicate = name, objectValue = "Alice", dataset = graph1,
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

    @Test fun `pattern 1 - S,P,O,D all known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(alice, knows, bob, graph1))
        assertEquals(1, result.size)
        assertEquals(alice, result.first().subject)
    }

    @Test fun `pattern 2 - S,P,O known, D wildcard`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(alice, knows, bob))
        assertEquals(1, result.size)
    }

    @Test fun `pattern 3 - S,P,D known, O wildcard`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(subject = alice, predicate = knows, dataset = graph1))
        assertEquals(2, result.size)
    }

    @Test fun `pattern 4 - S,P known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(subject = alice, predicate = knows))
        assertEquals(2, result.size)
    }

    @Test fun `pattern 5 - S,O,D known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(subject = alice, objectValue = bob, dataset = graph1))
        assertEquals(1, result.size)
    }

    @Test fun `pattern 6 - S,O known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(subject = alice, objectValue = bob))
        assertEquals(1, result.size)
    }

    @Test fun `pattern 7 - S,D known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(subject = alice, dataset = graph1))
        assertEquals(3, result.size)
    }

    @Test fun `pattern 8 - S known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(subject = alice))
        assertEquals(3, result.size)
    }

    @Test fun `pattern 9 - P,O,D known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(predicate = knows, objectValue = charlie, dataset = graph1))
        assertEquals(1, result.size)
    }

    @Test fun `pattern 10 - P,O known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(predicate = knows, objectValue = charlie))
        assertEquals(2, result.size)
    }

    @Test fun `pattern 11 - P,D known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(predicate = knows, dataset = graph1))
        assertEquals(2, result.size)
    }

    @Test fun `pattern 12 - P known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(predicate = knows))
        assertEquals(3, result.size)
    }

    @Test fun `pattern 13 - O,D known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(objectValue = charlie, dataset = graph1))
        assertEquals(1, result.size)
    }

    @Test fun `pattern 14 - O known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(objectValue = charlie))
        assertEquals(2, result.size)
    }

    @Test fun `pattern 15 - D known`() {
        insertTestData()
        val result = store.query(collection, QuadQuery(dataset = graph1))
        assertEquals(3, result.size)
    }

    @Test fun `pattern 16 - all wildcards`() {
        insertTestData()
        val result = store.query(collection, QuadQuery())
        assertEquals(5, result.size)
    }
}
