package com.agentwork.graphmesh.skos

import com.agentwork.graphmesh.ontology.LangLabel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkosControllerTest {

    private val service = mockk<SkosService>()
    private val controller = SkosController(service)

    private val sampleScheme = SkosConceptScheme(
        uri = "http://ex.org/scheme",
        prefLabels = listOf(LangLabel("Test Scheme", "en")),
        topConcepts = listOf("http://ex.org/A"),
        collectionId = "col-1"
    )

    private val sampleConcept = SkosConcept(
        uri = "http://ex.org/A",
        prefLabels = listOf(LangLabel("Concept A", "en")),
        broader = listOf("http://ex.org/B"),
        narrower = listOf("http://ex.org/C"),
        related = listOf("http://ex.org/D"),
        inScheme = "http://ex.org/scheme",
        collectionId = "col-1"
    )

    @Test
    fun `skosConceptSchemes delegates to service`() {
        every { service.getConceptSchemes("col-1") } returns listOf(sampleScheme)
        val result = controller.skosConceptSchemes("col-1")
        assertEquals(1, result.size)
        assertEquals("http://ex.org/scheme", result[0].uri)
        verify { service.getConceptSchemes("col-1") }
    }

    @Test
    fun `skosConcepts delegates to service`() {
        every { service.getConcepts("col-1", "http://ex.org/scheme") } returns listOf(sampleConcept)
        val result = controller.skosConcepts("col-1", "http://ex.org/scheme")
        assertEquals(1, result.size)
        assertEquals("http://ex.org/A", result[0].uri)
    }

    @Test
    fun `skosConcept returns concept for known URI`() {
        every { service.getConcept("col-1", "http://ex.org/A") } returns sampleConcept
        val result = controller.skosConcept("col-1", "http://ex.org/A")
        assertEquals("http://ex.org/A", result?.uri)
    }

    @Test
    fun `skosConcept returns null for unknown URI`() {
        every { service.getConcept("col-1", "http://ex.org/unknown") } returns null
        val result = controller.skosConcept("col-1", "http://ex.org/unknown")
        assertNull(result)
    }

    @Test
    fun `skosSearch delegates to service`() {
        every { service.findByLabel("col-1", "concept") } returns listOf(sampleConcept)
        val result = controller.skosSearch("col-1", "concept")
        assertEquals(1, result.size)
        verify { service.findByLabel("col-1", "concept") }
    }

    @Test
    fun `topConcepts SchemaMapping uses collectionId from parent`() {
        every { service.getTopConcepts("col-1", "http://ex.org/scheme") } returns listOf(sampleConcept)
        val result = controller.topConcepts(sampleScheme)
        assertEquals(1, result.size)
        assertEquals("http://ex.org/A", result[0].uri)
    }

    @Test
    fun `conceptCount SchemaMapping uses collectionId from parent`() {
        every { service.countConcepts("col-1", "http://ex.org/scheme") } returns 5
        val result = controller.conceptCount(sampleScheme)
        assertEquals(5, result)
    }

    @Test
    fun `broader SchemaMapping uses collectionId from parent`() {
        val broaderConcept = sampleConcept.copy(uri = "http://ex.org/B")
        every { service.getBroader("col-1", "http://ex.org/A") } returns listOf(broaderConcept)
        val result = controller.broader(sampleConcept)
        assertEquals(1, result.size)
        assertEquals("http://ex.org/B", result[0].uri)
    }

    @Test
    fun `narrower SchemaMapping uses collectionId from parent`() {
        val narrowerConcept = sampleConcept.copy(uri = "http://ex.org/C")
        every { service.getNarrower("col-1", "http://ex.org/A") } returns listOf(narrowerConcept)
        val result = controller.narrower(sampleConcept)
        assertEquals(1, result.size)
        assertEquals("http://ex.org/C", result[0].uri)
    }

    @Test
    fun `related SchemaMapping uses collectionId from parent`() {
        val relatedConcept = sampleConcept.copy(uri = "http://ex.org/D")
        every { service.getRelated("col-1", "http://ex.org/A") } returns listOf(relatedConcept)
        val result = controller.related(sampleConcept)
        assertEquals(1, result.size)
        assertEquals("http://ex.org/D", result[0].uri)
    }
}
