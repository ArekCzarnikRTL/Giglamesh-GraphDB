package com.agentwork.graphmesh.structured

import com.agentwork.graphmesh.config.ConfigItem
import com.agentwork.graphmesh.config.ConfigService
import com.agentwork.graphmesh.config.ConfigType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaStoreTest {

    private val objectMapper = jacksonObjectMapper()
    private val configService = mockk<ConfigService>()
    private val store = SchemaStore(configService, objectMapper)

    private val sampleSchema = TableSchema(
        name = "users",
        description = "User accounts",
        columns = listOf(
            ColumnDescriptor(name = "id", type = ColumnType.STRING, primaryKey = true),
            ColumnDescriptor(name = "name", type = ColumnType.STRING, nullable = false),
            ColumnDescriptor(name = "email", type = ColumnType.STRING, indexed = true),
            ColumnDescriptor(name = "age", type = ColumnType.INTEGER)
        )
    )

    @Test
    fun `save serializes schema and stores as ConfigItem`() {
        val itemSlot = slot<ConfigItem>()
        every { configService.findByTypeAndKey(ConfigType.SCHEMA, "users") } returns null
        every { configService.save(capture(itemSlot)) } answers { itemSlot.captured }

        store.save(sampleSchema)

        val saved = itemSlot.captured
        assertEquals(ConfigType.SCHEMA, saved.type)
        assertEquals("users", saved.key)
        assertEquals("User accounts", saved.description)

        val deserialized = objectMapper.readValue(saved.value, TableSchema::class.java)
        assertEquals("users", deserialized.name)
        assertEquals(4, deserialized.columns.size)
    }

    @Test
    fun `save reuses existing id on update`() {
        val existing = ConfigItem(id = "existing-id", type = ConfigType.SCHEMA, key = "users", value = "{}")
        val itemSlot = slot<ConfigItem>()
        every { configService.findByTypeAndKey(ConfigType.SCHEMA, "users") } returns existing
        every { configService.save(capture(itemSlot)) } answers { itemSlot.captured }

        store.save(sampleSchema)

        assertEquals("existing-id", itemSlot.captured.id)
    }

    @Test
    fun `load returns schema when exists`() {
        val json = objectMapper.writeValueAsString(sampleSchema)
        val item = ConfigItem(id = "id-1", type = ConfigType.SCHEMA, key = "users", value = json)
        every { configService.findByTypeAndKey(ConfigType.SCHEMA, "users") } returns item

        val loaded = store.load("users")

        assertNotNull(loaded)
        assertEquals("users", loaded.name)
        assertEquals(4, loaded.columns.size)
        assertTrue(loaded.columns[0].primaryKey)
    }

    @Test
    fun `load returns null when not found`() {
        every { configService.findByTypeAndKey(ConfigType.SCHEMA, "nonexistent") } returns null
        assertNull(store.load("nonexistent"))
    }

    @Test
    fun `listNames returns all schema keys`() {
        every { configService.findByType(ConfigType.SCHEMA) } returns listOf(
            ConfigItem(id = "1", type = ConfigType.SCHEMA, key = "users", value = "{}"),
            ConfigItem(id = "2", type = ConfigType.SCHEMA, key = "orders", value = "{}")
        )
        assertEquals(listOf("users", "orders"), store.listNames())
    }

    @Test
    fun `delete finds and removes by key`() {
        val item = ConfigItem(id = "id-1", type = ConfigType.SCHEMA, key = "users", value = "{}")
        every { configService.findByTypeAndKey(ConfigType.SCHEMA, "users") } returns item
        every { configService.delete("id-1") } returns Unit

        store.delete("users")

        verify { configService.delete("id-1") }
    }

    @Test
    fun `delete does nothing when not found`() {
        every { configService.findByTypeAndKey(ConfigType.SCHEMA, "nonexistent") } returns null
        store.delete("nonexistent")
        verify(exactly = 0) { configService.delete(any()) }
    }

    @Test
    fun `JSON round-trip preserves all fields`() {
        val schema = TableSchema(
            name = "products",
            description = "Product catalog",
            version = "2.0.0",
            columns = listOf(
                ColumnDescriptor(name = "sku", type = ColumnType.STRING, primaryKey = true, description = "Stock keeping unit"),
                ColumnDescriptor(name = "price", type = ColumnType.DOUBLE, nullable = false),
                ColumnDescriptor(name = "in_stock", type = ColumnType.BOOLEAN),
                ColumnDescriptor(name = "created_at", type = ColumnType.TIMESTAMP)
            ),
            indexes = listOf(IndexDefinition(fields = listOf("price", "in_stock")))
        )

        val json = objectMapper.writeValueAsString(schema)
        val deserialized = objectMapper.readValue(json, TableSchema::class.java)

        assertEquals(schema, deserialized)
    }
}
