package com.agentwork.graphmesh.ontology

import com.agentwork.graphmesh.rdf.XsdTypes
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JenaAdapterTest {

    private val adapter = JenaAdapter()

    private val sampleOntology = Ontology(
        metadata = OntologyMetadata(
            name = "Animals",
            namespace = "http://example.org/animals/"
        ),
        classes = mapOf(
            "Animal" to OntologyClass(
                id = "Animal",
                uri = "http://example.org/animals/Animal",
                labels = listOf(LangLabel("Animal", "en"), LangLabel("Tier", "de")),
                comment = "A living organism"
            ),
            "Dog" to OntologyClass(
                id = "Dog",
                uri = "http://example.org/animals/Dog",
                subClassOf = listOf("Animal"),
                labels = listOf(LangLabel("Dog", "en"))
            )
        ),
        objectProperties = mapOf(
            "eats" to ObjectProperty(
                id = "eats",
                uri = "http://example.org/animals/eats",
                domain = "Animal",
                range = "Animal",
                labels = listOf(LangLabel("eats", "en")),
                functional = true
            )
        ),
        datatypeProperties = mapOf(
            "name" to DatatypeProperty(
                id = "name",
                uri = "http://example.org/animals/name",
                domain = "Animal",
                range = XsdTypes.STRING,
                labels = listOf(LangLabel("name", "en")),
                functional = true,
                cardinality = Cardinality(exact = 1)
            )
        )
    )

    @Test
    fun `toJenaModel creates OWL classes with correct types`() {
        val model = adapter.toJenaModel(sampleOntology)

        val animalResource = model.getResource("http://example.org/animals/Animal")
        assertNotNull(animalResource)

        val owlClass = model.getResource("http://www.w3.org/2002/07/owl#Class")
        assertTrue(model.contains(animalResource, model.getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), owlClass))
    }

    @Test
    fun `toJenaModel creates subClassOf relationships`() {
        val model = adapter.toJenaModel(sampleOntology)

        val dogResource = model.getResource("http://example.org/animals/Dog")
        val animalResource = model.getResource("http://example.org/animals/Animal")
        val subClassOf = model.getProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf")

        assertTrue(model.contains(dogResource, subClassOf, animalResource))
    }

    @Test
    fun `toJenaModel creates labels with language tags`() {
        val model = adapter.toJenaModel(sampleOntology)

        val animalResource = model.getResource("http://example.org/animals/Animal")
        val label = model.getProperty("http://www.w3.org/2000/01/rdf-schema#label")

        val labels = model.listStatements(animalResource, label, null as org.apache.jena.rdf.model.RDFNode?).toList()
        assertEquals(2, labels.size)
    }

    @Test
    fun `round-trip Kotlin to Jena to Kotlin preserves classes`() {
        val model = adapter.toJenaModel(sampleOntology)
        val result = adapter.fromJenaModel(model, sampleOntology.metadata)

        assertEquals(sampleOntology.classes.size, result.classes.size)
        assertTrue(result.classes.containsKey("Animal"))
        assertTrue(result.classes.containsKey("Dog"))
        assertEquals(listOf("Animal"), result.classes["Dog"]?.subClassOf)
    }

    @Test
    fun `round-trip preserves object properties`() {
        val model = adapter.toJenaModel(sampleOntology)
        val result = adapter.fromJenaModel(model, sampleOntology.metadata)

        assertEquals(1, result.objectProperties.size)
        val eats = result.objectProperties["eats"]
        assertNotNull(eats)
        assertEquals("Animal", eats.domain)
        assertEquals("Animal", eats.range)
        assertTrue(eats.functional)
    }

    @Test
    fun `round-trip preserves datatype properties`() {
        val model = adapter.toJenaModel(sampleOntology)
        val result = adapter.fromJenaModel(model, sampleOntology.metadata)

        assertEquals(1, result.datatypeProperties.size)
        val name = result.datatypeProperties["name"]
        assertNotNull(name)
        assertEquals("Animal", name.domain)
        assertEquals(XsdTypes.STRING, name.range)
        assertTrue(name.functional)
    }

    @Test
    fun `Turtle serialization round-trip`() {
        val model = adapter.toJenaModel(sampleOntology)
        val turtle = adapter.serializeTurtle(model)

        assertTrue(turtle.contains("owl:Class"))
        assertTrue(turtle.contains("Animal"))

        val parsed = adapter.parseTurtle(turtle)
        val result = adapter.fromJenaModel(parsed, sampleOntology.metadata)

        assertEquals(sampleOntology.classes.size, result.classes.size)
        assertTrue(result.classes.containsKey("Animal"))
        assertTrue(result.classes.containsKey("Dog"))
    }

    @Test
    fun `RDF-XML serialization round-trip`() {
        val model = adapter.toJenaModel(sampleOntology)
        val rdfXml = adapter.serializeRdfXml(model)

        assertTrue(rdfXml.contains("rdf:RDF"))

        val parsed = adapter.parseRdfXml(rdfXml)
        val result = adapter.fromJenaModel(parsed, sampleOntology.metadata)

        assertEquals(sampleOntology.classes.size, result.classes.size)
        assertTrue(result.classes.containsKey("Animal"))
        assertTrue(result.classes.containsKey("Dog"))
    }

    @Test
    fun `parseTurtle parses external Turtle content`() {
        val turtle = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix ex: <http://example.org/> .

            ex:Person a owl:Class ;
                rdfs:label "Person"@en .

            ex:knows a owl:ObjectProperty ;
                rdfs:domain ex:Person ;
                rdfs:range ex:Person .
        """.trimIndent()

        val model = adapter.parseTurtle(turtle)
        val metadata = OntologyMetadata(name = "Test", namespace = "http://example.org/")
        val result = adapter.fromJenaModel(model, metadata)

        assertEquals(1, result.classes.size)
        assertTrue(result.classes.containsKey("Person"))
        assertEquals(1, result.objectProperties.size)
        assertTrue(result.objectProperties.containsKey("knows"))
    }
}
