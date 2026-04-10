package com.agentwork.graphmesh.ontology

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OntologyControllerTest {

    private val service = mockk<OntologyService>()
    private val controller = OntologyController(service)

    private val sampleOntology = Ontology(
        metadata = OntologyMetadata(name = "Test", namespace = "http://test.org/", version = "1.0"),
        classes = mapOf("A" to OntologyClass(id = "A", uri = "http://test.org/A")),
        objectProperties = emptyMap(),
        datatypeProperties = emptyMap(),
    )

    @Test
    fun `listOntologies delegates to service`() {
        every { service.list() } returns listOf("ont1", "ont2")
        every { service.get("ont1") } returns sampleOntology
        every { service.get("ont2") } returns null

        val result = controller.listOntologies()

        assertEquals(1, result.size)
        assertEquals("ont1", result.single().key)
        assertEquals(1, result.single().classCount)
    }

    @Test
    fun `ontology returns payload for existing key`() {
        every { service.get("ont1") } returns sampleOntology

        val result = controller.ontology("ont1")

        assertEquals("ont1", result?.key)
        assertEquals("Test", result?.name)
        assertEquals("http://test.org/", result?.namespace)
    }

    @Test
    fun `ontology returns null for missing key`() {
        every { service.get("missing") } returns null

        assertNull(controller.ontology("missing"))
    }

    @Test
    fun `importOntology decodes base64 and delegates to importTurtle`() {
        val turtleContent = "@prefix ex: <http://example.org/> ."
        val encoded = Base64.getEncoder().encodeToString(turtleContent.toByteArray())
        every { service.importTurtle("ont1", turtleContent, any()) } returns sampleOntology

        val input = ImportOntologyInput(
            key = "ont1",
            content = encoded,
            format = OntologyFormat.TURTLE,
            name = "Test",
            namespace = "http://test.org/",
        )
        val result = controller.importOntology(input)

        assertEquals("ont1", result.key)
        verify { service.importTurtle("ont1", turtleContent, any()) }
    }

    @Test
    fun `importOntology decodes base64 and delegates to importRdfXml`() {
        val xmlContent = "<rdf:RDF/>"
        val encoded = Base64.getEncoder().encodeToString(xmlContent.toByteArray())
        every { service.importRdfXml("ont2", xmlContent, any()) } returns sampleOntology

        val input = ImportOntologyInput(
            key = "ont2",
            content = encoded,
            format = OntologyFormat.RDFXML,
            name = "Test",
            namespace = "http://test.org/",
        )
        val result = controller.importOntology(input)

        assertEquals("ont2", result.key)
        verify { service.importRdfXml("ont2", xmlContent, any()) }
    }

    @Test
    fun `deleteOntology delegates to service and returns true`() {
        every { service.delete("ont1") } returns Unit

        val result = controller.deleteOntology("ont1")

        assertTrue(result)
        verify { service.delete("ont1") }
    }
}
