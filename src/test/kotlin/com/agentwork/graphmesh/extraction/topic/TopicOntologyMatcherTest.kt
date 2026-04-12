package com.agentwork.graphmesh.extraction.topic

import com.agentwork.graphmesh.ontology.LangLabel
import com.agentwork.graphmesh.ontology.Ontology
import com.agentwork.graphmesh.ontology.OntologyClass
import com.agentwork.graphmesh.ontology.OntologyMetadata
import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.skos.SkosConcept
import com.agentwork.graphmesh.skos.SkosConceptScheme
import com.agentwork.graphmesh.skos.SkosService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopicOntologyMatcherTest {

    private val ontologyService = mockk<OntologyService>()
    private val skosService = mockk<SkosService>()
    private val matcher = TopicOntologyMatcher(ontologyService, skosService)

    @Test
    fun `getHints returns empty list when no ontology and no SKOS schemes`() {
        every { ontologyService.get("col-1") } returns null
        every { skosService.getConceptSchemes("col-1") } returns emptyList()

        val hints = matcher.getHints("col-1")
        assertTrue(hints.isEmpty())
    }

    @Test
    fun `getHints returns OWL class labels`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Insolvenzrecht" to OntologyClass(
                    id = "Insolvenzrecht",
                    uri = "http://test.org/Insolvenzrecht",
                    labels = listOf(LangLabel("Insolvenzrecht", "de"))
                ),
                "Datenschutz" to OntologyClass(
                    id = "Datenschutz",
                    uri = "http://test.org/Datenschutz",
                    labels = listOf(LangLabel("Datenschutz", "de"))
                )
            )
        )
        every { ontologyService.get("col-1") } returns ontology
        every { skosService.getConceptSchemes("col-1") } returns emptyList()

        val hints = matcher.getHints("col-1")
        assertEquals(2, hints.size)
        assertTrue(hints.contains("Insolvenzrecht"))
        assertTrue(hints.contains("Datenschutz"))
    }

    @Test
    fun `getHints returns SKOS concept prefLabels`() {
        every { ontologyService.get("col-1") } returns null
        every { skosService.getConceptSchemes("col-1") } returns listOf(
            SkosConceptScheme(uri = "urn:scheme:1", prefLabels = listOf(LangLabel("Test Scheme")), collectionId = "col-1")
        )
        every { skosService.getConcepts("col-1", "urn:scheme:1") } returns listOf(
            SkosConcept(uri = "urn:concept:photo", prefLabels = listOf(LangLabel("Photosynthese", "de")), collectionId = "col-1"),
            SkosConcept(uri = "urn:concept:bio", prefLabels = listOf(LangLabel("Biologie", "de")), collectionId = "col-1")
        )

        val hints = matcher.getHints("col-1")
        assertEquals(2, hints.size)
        assertTrue(hints.contains("Photosynthese"))
        assertTrue(hints.contains("Biologie"))
    }

    @Test
    fun `getHints deduplicates across OWL and SKOS`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Photosynthese" to OntologyClass(
                    id = "Photosynthese",
                    uri = "http://test.org/Photosynthese",
                    labels = listOf(LangLabel("Photosynthese", "de"))
                )
            )
        )
        every { ontologyService.get("col-1") } returns ontology
        every { skosService.getConceptSchemes("col-1") } returns listOf(
            SkosConceptScheme(uri = "urn:scheme:1", prefLabels = listOf(LangLabel("S")), collectionId = "col-1")
        )
        every { skosService.getConcepts("col-1", "urn:scheme:1") } returns listOf(
            SkosConcept(uri = "urn:concept:photo", prefLabels = listOf(LangLabel("Photosynthese", "de")), collectionId = "col-1")
        )

        val hints = matcher.getHints("col-1")
        assertEquals(1, hints.size)
    }

    @Test
    fun `getHints limits to 50 labels`() {
        val classes = (1..60).associate { i ->
            "Class$i" to OntologyClass(
                id = "Class$i",
                uri = "http://test.org/Class$i",
                labels = listOf(LangLabel("Label$i"))
            )
        }
        every { ontologyService.get("col-1") } returns Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = classes
        )
        every { skosService.getConceptSchemes("col-1") } returns emptyList()

        val hints = matcher.getHints("col-1")
        assertEquals(50, hints.size)
    }

    @Test
    fun `resolveOrCreate returns existing SKOS concept URI on match`() {
        every { skosService.findByLabel("col-1", "insolvenzrecht") } returns listOf(
            SkosConcept(
                uri = "urn:concept:existing",
                prefLabels = listOf(LangLabel("insolvenzrecht", "de")),
                collectionId = "col-1"
            )
        )

        val uri = matcher.resolveOrCreate("Insolvenzrecht", "col-1")
        assertEquals("urn:concept:existing", uri.value)
    }

    @Test
    fun `resolveOrCreate falls back to EntityIdGenerator when no match`() {
        every { skosService.findByLabel("col-1", "neues thema") } returns emptyList()

        val uri = matcher.resolveOrCreate("Neues Thema", "col-1")
        assertEquals(EntityIdGenerator.generate("neues thema"), uri)
    }
}
