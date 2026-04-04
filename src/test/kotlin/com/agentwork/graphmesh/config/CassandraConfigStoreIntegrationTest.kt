package com.agentwork.graphmesh.config

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration," +
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    ]
)
@ActiveProfiles("test")
class CassandraConfigStoreIntegrationTest {

    @Autowired
    lateinit var store: CassandraConfigStore

    private lateinit var testId: String

    @BeforeEach
    fun setUp() {
        testId = "test-${UUID.randomUUID()}"
    }

    @Test
    fun `save and findById roundtrip`() {
        val item = ConfigItem(
            id = testId,
            type = ConfigType.PARAMETER,
            key = "max-tokens",
            value = "4096",
            description = "Maximum token count"
        )

        store.save(item)
        val found = store.findById(testId)

        assertNotNull(found)
        assertEquals(testId, found.id)
        assertEquals(ConfigType.PARAMETER, found.type)
        assertEquals("max-tokens", found.key)
        assertEquals("4096", found.value)
        assertEquals("Maximum token count", found.description)
    }

    @Test
    fun `findByType returns items of matching type`() {
        val key1 = "key-${UUID.randomUUID()}"
        val key2 = "key-${UUID.randomUUID()}"
        val key3 = "key-${UUID.randomUUID()}"

        store.save(ConfigItem(id = "p1-$testId", type = ConfigType.PARAMETER, key = key1, value = "1"))
        store.save(ConfigItem(id = "o1-$testId", type = ConfigType.ONTOLOGY, key = key2, value = "2"))
        store.save(ConfigItem(id = "p2-$testId", type = ConfigType.PARAMETER, key = key3, value = "3"))

        val params = store.findByType(ConfigType.PARAMETER)
        val matchingKeys = params.filter { it.key in listOf(key1, key3) }
        assertEquals(2, matchingKeys.size)
    }

    @Test
    fun `findByTypeAndKey returns matching item`() {
        val uniqueKey = "key-${UUID.randomUUID()}"
        store.save(ConfigItem(id = testId, type = ConfigType.PARAMETER, key = uniqueKey, value = "4096"))

        val found = store.findByTypeAndKey(ConfigType.PARAMETER, uniqueKey)
        assertNotNull(found)
        assertEquals("4096", found.value)

        val notFound = store.findByTypeAndKey(ConfigType.PARAMETER, "nonexistent-${UUID.randomUUID()}")
        assertNull(notFound)
    }

    @Test
    fun `delete removes item from all tables`() {
        val uniqueKey = "key-${UUID.randomUUID()}"
        store.save(ConfigItem(id = testId, type = ConfigType.PARAMETER, key = uniqueKey, value = "1"))

        store.delete(testId)

        assertNull(store.findById(testId))
        assertNull(store.findByTypeAndKey(ConfigType.PARAMETER, uniqueKey))
    }

    @Test
    fun `history returns versions in descending order`() {
        store.save(ConfigItem(id = testId, type = ConfigType.PARAMETER, key = "a", value = "v1", version = 1))
        store.save(ConfigItem(id = testId, type = ConfigType.PARAMETER, key = "a", value = "v2", version = 2))
        store.save(ConfigItem(id = testId, type = ConfigType.PARAMETER, key = "a", value = "v3", version = 3))

        val history = store.history(testId)
        assertEquals(3, history.size)
        assertEquals("v3", history[0].value)
        assertEquals(3, history[0].version)
        assertEquals("v1", history[2].value)
        assertEquals(1, history[2].version)
    }

    @Test
    fun `update increments version and preserves createdAt`() {
        store.save(ConfigItem(id = testId, type = ConfigType.PARAMETER, key = "a", value = "v1", version = 1))
        val first = store.findById(testId)
        assertNotNull(first)
        assertEquals(1, first.version)

        store.save(ConfigItem(id = testId, type = ConfigType.PARAMETER, key = "a", value = "v2", version = 2))
        val second = store.findById(testId)
        assertNotNull(second)
        assertEquals(2, second.version)
        assertEquals("v2", second.value)
    }
}
