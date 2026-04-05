package com.agentwork.graphmesh.ontology

import com.agentwork.graphmesh.config.ConfigItem
import com.agentwork.graphmesh.config.ConfigType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.jena.rdf.model.ModelFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OntologyServiceTest {

    private val store = mockk<OntologyStore>(relaxed = true)
    private val cache = mockk<OntologyCache>()
    private val validator = DefaultOntologyValidator()
    private val jenaAdapter = mockk<JenaAdapter>()
    private val service = OntologyService(store, cache, validator, jenaAdapter)

    private val validOntology = Ontology(
        metadata = OntologyMetadata(name = "Test", namespace = "http://test.org/"),
        classes = mapOf(
            "Animal" to OntologyClass(id = "Animal", uri = "http://test.org/Animal"),
            "Dog" to OntologyClass(id = "Dog", uri = "http://test.org/Dog", subClassOf = listOf("Animal"))
        )
    )

    private val invalidOntology = Ontology(
        metadata = OntologyMetadata(name = "Test", namespace = "http://test.org/"),
        classes = mapOf(
            "A" to OntologyClass(id = "A", uri = "http://test.org/A", subClassOf = listOf("B")),
            "B" to OntologyClass(id = "B", uri = "http://test.org/B", subClassOf = listOf("A"))
        )
    )

    @Test
    fun `save validates and stores when no errors`() {
        val configItem = ConfigItem(id = "id-1", type = ConfigType.ONTOLOGY, key = "test", value = "{}")
        every { store.save("test", validOntology) } returns configItem

        val errors = service.save("test", validOntology)

        assertTrue(errors.isEmpty())
        verify { store.save("test", validOntology) }
    }

    @Test
    fun `save returns errors and does not store when validation fails`() {
        val errors = service.save("test", invalidOntology)

        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.rule == ValidationRule.CIRCULAR_INHERITANCE })
        verify(exactly = 0) { store.save(any(), any()) }
    }

    @Test
    fun `get delegates to cache`() {
        every { cache.get("test") } returns validOntology

        val result = service.get("test")

        assertNotNull(result)
        assertEquals("Test", result.metadata.name)
        verify { cache.get("test") }
    }

    @Test
    fun `get returns null when not found`() {
        every { cache.get("nonexistent") } returns null
        assertNull(service.get("nonexistent"))
    }

    @Test
    fun `list delegates to store`() {
        every { store.listKeys() } returns listOf("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), service.list())
    }

    @Test
    fun `delete delegates to store`() {
        service.delete("test")
        verify { store.delete("test") }
    }

    @Test
    fun `importTurtle parses, converts, validates, and saves`() {
        val turtleContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> ."
        val model = ModelFactory.createDefaultModel()
        val metadata = OntologyMetadata(name = "Test", namespace = "http://test.org/")

        every { jenaAdapter.parseTurtle(turtleContent) } returns model
        every { jenaAdapter.fromJenaModel(model, any()) } returns validOntology
        val configItem = ConfigItem(id = "id-1", type = ConfigType.ONTOLOGY, key = "test", value = "{}")
        every { store.save("test", validOntology) } returns configItem

        val result = service.importTurtle("test", turtleContent, metadata)

        assertEquals("Test", result.metadata.name)
        verify { store.save("test", validOntology) }
    }

    @Test
    fun `exportTurtle loads, converts, and serializes`() {
        val model = ModelFactory.createDefaultModel()
        val expectedTurtle = "@prefix owl: <http://www.w3.org/2002/07/owl#> ."

        every { cache.get("test") } returns validOntology
        every { jenaAdapter.toJenaModel(validOntology) } returns model
        every { jenaAdapter.serializeTurtle(model) } returns expectedTurtle

        val result = service.exportTurtle("test")

        assertEquals(expectedTurtle, result)
    }

    @Test
    fun `exportTurtle throws when ontology not found`() {
        every { cache.get("nonexistent") } returns null

        assertThrows<IllegalArgumentException> {
            service.exportTurtle("nonexistent")
        }
    }

    @Test
    fun `importRdfXml parses, converts, validates, and saves`() {
        val rdfXmlContent = "<rdf:RDF />"
        val model = ModelFactory.createDefaultModel()
        val metadata = OntologyMetadata(name = "Test", namespace = "http://test.org/")

        every { jenaAdapter.parseRdfXml(rdfXmlContent) } returns model
        every { jenaAdapter.fromJenaModel(model, any()) } returns validOntology
        val configItem = ConfigItem(id = "id-1", type = ConfigType.ONTOLOGY, key = "test", value = "{}")
        every { store.save("test", validOntology) } returns configItem

        val result = service.importRdfXml("test", rdfXmlContent, metadata)

        assertEquals("Test", result.metadata.name)
    }

    @Test
    fun `exportRdfXml loads, converts, and serializes`() {
        val model = ModelFactory.createDefaultModel()
        val expectedXml = "<rdf:RDF />"

        every { cache.get("test") } returns validOntology
        every { jenaAdapter.toJenaModel(validOntology) } returns model
        every { jenaAdapter.serializeRdfXml(model) } returns expectedXml

        val result = service.exportRdfXml("test")

        assertEquals(expectedXml, result)
    }

    @Test
    fun `validate delegates to validator`() {
        val errors = service.validate(invalidOntology)
        assertTrue(errors.any { it.rule == ValidationRule.CIRCULAR_INHERITANCE })
    }
}
