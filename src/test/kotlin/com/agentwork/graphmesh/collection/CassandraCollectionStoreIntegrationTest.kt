package com.agentwork.graphmesh.collection

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration," +
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    ]
)
@ActiveProfiles("test")
class CassandraCollectionStoreIntegrationTest {

    @Autowired
    lateinit var store: CassandraCollectionStore

    @Test
    fun `save and findById roundtrip`() {
        val collection = Collection(
            name = "test-${UUID.randomUUID()}",
            description = "A test collection",
            tags = setOf("test", "integration"),
            metadata = mapOf("env" to "test")
        )
        store.save(collection)

        val found = store.findById(collection.id)
        assertNotNull(found)
        assertEquals(collection.name, found.name)
        assertEquals(collection.description, found.description)
        assertEquals(collection.tags, found.tags)
        assertEquals(collection.metadata, found.metadata)
    }

    @Test
    fun `findByName returns matching collection`() {
        val name = "named-${UUID.randomUUID()}"
        val collection = Collection(name = name)
        store.save(collection)

        val found = store.findByName(name)
        assertNotNull(found)
        assertEquals(collection.id, found.id)
    }

    @Test
    fun `findByName returns null for unknown name`() {
        assertNull(store.findByName("nonexistent-${UUID.randomUUID()}"))
    }

    @Test
    fun `findAll returns all collections`() {
        val c1 = Collection(name = "all-test-1-${UUID.randomUUID()}")
        val c2 = Collection(name = "all-test-2-${UUID.randomUUID()}")
        store.save(c1)
        store.save(c2)

        val all = store.findAll()
        assertTrue(all.any { it.id == c1.id })
        assertTrue(all.any { it.id == c2.id })
    }

    @Test
    fun `delete removes collection from both tables`() {
        val collection = Collection(name = "delete-${UUID.randomUUID()}")
        store.save(collection)

        store.delete(collection.id)

        assertNull(store.findById(collection.id))
        assertNull(store.findByName(collection.name))
    }

    @Test
    fun `exists returns true for existing and false for missing`() {
        val collection = Collection(name = "exists-${UUID.randomUUID()}")
        store.save(collection)

        assertTrue(store.exists(collection.id))
        assertFalse(store.exists("nonexistent"))
    }

    @Test
    fun `update overwrites collection`() {
        val collection = Collection(name = "update-${UUID.randomUUID()}", description = "original")
        store.save(collection)

        val updated = collection.copy(description = "updated")
        store.save(updated)

        val found = store.findById(collection.id)
        assertEquals("updated", found?.description)
    }
}
