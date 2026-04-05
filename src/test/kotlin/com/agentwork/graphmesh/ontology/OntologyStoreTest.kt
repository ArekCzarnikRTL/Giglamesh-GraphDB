package com.agentwork.graphmesh.ontology

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

class OntologyStoreTest {

    private val objectMapper = jacksonObjectMapper()
    private val configService = mockk<ConfigService>()
    private val store = OntologyStore(configService, objectMapper)

    private val sampleOntology = Ontology(
        metadata = OntologyMetadata(name = "Animals", namespace = "http://example.org/animals/"),
        classes = mapOf(
            "Animal" to OntologyClass(
                id = "Animal",
                uri = "http://example.org/animals/Animal",
                labels = listOf(LangLabel("Animal", "en"), LangLabel("Tier", "de"))
            ),
            "Dog" to OntologyClass(
                id = "Dog",
                uri = "http://example.org/animals/Dog",
                subClassOf = listOf("Animal")
            )
        ),
        objectProperties = mapOf(
            "eats" to ObjectProperty(
                id = "eats",
                uri = "http://example.org/animals/eats",
                domain = "Animal",
                range = "Animal"
            )
        )
    )

    @Test
    fun `save serializes ontology to JSON and stores as ConfigItem`() {
        val itemSlot = slot<ConfigItem>()
        every { configService.findByTypeAndKey(ConfigType.ONTOLOGY, "animals") } returns null
        every { configService.save(capture(itemSlot)) } answers { itemSlot.captured }

        store.save("animals", sampleOntology)

        val saved = itemSlot.captured
        assertEquals(ConfigType.ONTOLOGY, saved.type)
        assertEquals("animals", saved.key)

        val deserialized = objectMapper.readValue(saved.value, Ontology::class.java)
        assertEquals("Animals", deserialized.metadata.name)
        assertEquals(2, deserialized.classes.size)
        assertEquals(1, deserialized.objectProperties.size)
    }

    @Test
    fun `save reuses existing ConfigItem id on update`() {
        val existingItem = ConfigItem(
            id = "existing-id",
            type = ConfigType.ONTOLOGY,
            key = "animals",
            value = "{}"
        )
        val itemSlot = slot<ConfigItem>()
        every { configService.findByTypeAndKey(ConfigType.ONTOLOGY, "animals") } returns existingItem
        every { configService.save(capture(itemSlot)) } answers { itemSlot.captured }

        store.save("animals", sampleOntology)

        assertEquals("existing-id", itemSlot.captured.id)
    }

    @Test
    fun `load returns ontology when ConfigItem exists`() {
        val json = objectMapper.writeValueAsString(sampleOntology)
        val item = ConfigItem(id = "id-1", type = ConfigType.ONTOLOGY, key = "animals", value = json)
        every { configService.findByTypeAndKey(ConfigType.ONTOLOGY, "animals") } returns item

        val loaded = store.load("animals")

        assertNotNull(loaded)
        assertEquals("Animals", loaded.metadata.name)
        assertEquals(2, loaded.classes.size)
        assertTrue(loaded.classes.containsKey("Dog"))
    }

    @Test
    fun `load returns null when ConfigItem does not exist`() {
        every { configService.findByTypeAndKey(ConfigType.ONTOLOGY, "nonexistent") } returns null
        assertNull(store.load("nonexistent"))
    }

    @Test
    fun `listKeys returns all ontology keys`() {
        every { configService.findByType(ConfigType.ONTOLOGY) } returns listOf(
            ConfigItem(id = "1", type = ConfigType.ONTOLOGY, key = "animals", value = "{}"),
            ConfigItem(id = "2", type = ConfigType.ONTOLOGY, key = "geography", value = "{}")
        )
        val keys = store.listKeys()
        assertEquals(listOf("animals", "geography"), keys)
    }

    @Test
    fun `delete finds item by type and key then deletes by id`() {
        val item = ConfigItem(id = "id-1", type = ConfigType.ONTOLOGY, key = "animals", value = "{}")
        every { configService.findByTypeAndKey(ConfigType.ONTOLOGY, "animals") } returns item
        every { configService.delete("id-1") } returns Unit

        store.delete("animals")

        verify { configService.delete("id-1") }
    }

    @Test
    fun `delete does nothing when key does not exist`() {
        every { configService.findByTypeAndKey(ConfigType.ONTOLOGY, "nonexistent") } returns null
        store.delete("nonexistent")
        verify(exactly = 0) { configService.delete(any()) }
    }

    @Test
    fun `JSON round-trip preserves all fields including labels and cardinality`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(
                name = "Test", description = "A test ontology", version = "2.0.0",
                namespace = "http://test.org/", imports = listOf("http://other.org/")
            ),
            classes = mapOf(
                "Person" to OntologyClass(
                    id = "Person", uri = "http://test.org/Person",
                    labels = listOf(LangLabel("Person", "en"), LangLabel("Personne", "fr")),
                    comment = "A human being",
                    equivalentClasses = listOf("Human"),
                    disjointWith = listOf("Robot")
                )
            ),
            datatypeProperties = mapOf(
                "age" to DatatypeProperty(
                    id = "age", uri = "http://test.org/age", domain = "Person",
                    range = "http://www.w3.org/2001/XMLSchema#integer",
                    functional = true, cardinality = Cardinality(exact = 1)
                )
            )
        )
        val json = objectMapper.writeValueAsString(ontology)
        val deserialized = objectMapper.readValue(json, Ontology::class.java)
        assertEquals(ontology, deserialized)
    }
}
