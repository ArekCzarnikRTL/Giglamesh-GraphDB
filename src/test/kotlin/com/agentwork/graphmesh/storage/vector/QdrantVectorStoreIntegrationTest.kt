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
                "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration," +
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
            VectorPoint("doc1", floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f), VectorPayload(collection = "", extra = mapOf("category" to "science"))),
            VectorPoint("doc2", floatArrayOf(0.9f, 0.1f, 0.0f, 0.0f), VectorPayload(collection = "", extra = mapOf("category" to "arts"))),
            VectorPoint("doc3", floatArrayOf(0.8f, 0.2f, 0.0f, 0.0f), VectorPayload(collection = "", extra = mapOf("category" to "science")))
        )
        vectorStore.upsert(collection, points)

        val results = vectorStore.search(
            collection,
            queryVector = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f),
            limit = 10,
            filter = VectorFilter.Equals("category", "science")
        )

        assertEquals(2, results.size)
        assertTrue(results.all { it.payload.extra["category"] == "science" })
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
            VectorPoint("doc1", floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f), VectorPayload(collection = "", extra = mapOf("title" to "Hello", "count" to 42)))
        )
        vectorStore.upsert(collection, points)

        val results = vectorStore.search(collection, floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f), limit = 1)

        assertEquals(1, results.size)
        assertEquals("Hello", results[0].payload.extra["title"])
        assertEquals(42L, results[0].payload.extra["count"])
    }
}
