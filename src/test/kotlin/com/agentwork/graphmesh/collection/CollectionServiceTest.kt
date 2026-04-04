package com.agentwork.graphmesh.collection

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CollectionServiceTest {

    private lateinit var store: InMemoryCollectionStore
    private lateinit var publishedEvents: MutableList<Any>

    @BeforeEach
    fun setup() {
        store = InMemoryCollectionStore()
        publishedEvents = mutableListOf()
    }

    @Test
    fun `create stores collection and returns it`() {
        val collection = createTestCollection("test-collection")
        assertNotNull(collection)
        assertEquals("test-collection", collection.name)
        assertEquals("test-collection", store.findByName("test-collection")?.name)
    }

    @Test
    fun `create rejects duplicate name`() {
        store.save(Collection(name = "existing"))
        assertThrows<IllegalArgumentException> {
            createTestCollection("existing")
        }
    }

    @Test
    fun `create with tags and metadata`() {
        val collection = createTestCollection("tagged", tags = setOf("a", "b"), metadata = mapOf("key" to "val"))
        assertEquals(setOf("a", "b"), collection.tags)
        assertEquals(mapOf("key" to "val"), collection.metadata)
    }

    @Test
    fun `findAll filters by tags`() {
        store.save(Collection(name = "a", tags = setOf("x", "y")))
        store.save(Collection(name = "b", tags = setOf("y", "z")))
        store.save(Collection(name = "c", tags = setOf("x")))

        val withX = store.findAll().filter { it.tags.contains("x") }
        assertEquals(2, withX.size)
    }

    @Test
    fun `delete removes collection`() {
        val collection = Collection(name = "to-delete")
        store.save(collection)
        store.delete(collection.id)
        assertNull(store.findById(collection.id))
    }

    @Test
    fun `exists returns false for unknown id`() {
        assertEquals(false, store.exists("nonexistent"))
    }

    private fun createTestCollection(
        name: String,
        tags: Set<String> = emptySet(),
        metadata: Map<String, String> = emptyMap()
    ): Collection {
        require(store.findByName(name) == null) { "Collection with name '$name' already exists" }
        val collection = Collection(name = name, tags = tags, metadata = metadata)
        store.save(collection)
        return collection
    }
}

class InMemoryCollectionStore : CollectionStore {
    private val byId = mutableMapOf<String, Collection>()
    private val byName = mutableMapOf<String, Collection>()

    override fun save(collection: Collection) {
        byId[collection.id] = collection
        byName[collection.name] = collection
    }

    override fun findById(id: String): Collection? = byId[id]
    override fun findByName(name: String): Collection? = byName[name]
    override fun findAll(): List<Collection> = byId.values.toList()

    override fun delete(id: String) {
        val collection = byId.remove(id) ?: return
        byName.remove(collection.name)
    }

    override fun exists(id: String): Boolean = byId.containsKey(id)
}
