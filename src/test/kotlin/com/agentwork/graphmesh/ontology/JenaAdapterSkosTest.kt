package com.agentwork.graphmesh.ontology

import com.agentwork.graphmesh.skos.SkosConcept
import com.agentwork.graphmesh.skos.SkosConceptScheme
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JenaAdapterSkosTest {

    private val adapter = JenaAdapter()

    private val skosTurtle = """
        @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
        @prefix ex: <http://example.org/> .

        ex:animals a skos:ConceptScheme ;
            skos:prefLabel "Animals"@en ;
            skos:prefLabel "Tiere"@de ;
            skos:hasTopConcept ex:animal .

        ex:animal a skos:Concept ;
            skos:prefLabel "Animal"@en ;
            skos:inScheme ex:animals ;
            skos:topConceptOf ex:animals ;
            skos:narrower ex:mammal .

        ex:mammal a skos:Concept ;
            skos:prefLabel "Mammal"@en ;
            skos:altLabel "Saeugetier"@de ;
            skos:inScheme ex:animals ;
            skos:broader ex:animal ;
            skos:narrower ex:cat ;
            skos:scopeNote "Warm-blooded vertebrates"@en .

        ex:cat a skos:Concept ;
            skos:prefLabel "Cat"@en ;
            skos:inScheme ex:animals ;
            skos:broader ex:mammal ;
            skos:definition "A small domesticated feline"@en ;
            skos:related ex:dog .

        ex:dog a skos:Concept ;
            skos:prefLabel "Dog"@en ;
            skos:inScheme ex:animals ;
            skos:broader ex:mammal ;
            skos:related ex:cat .
    """.trimIndent()

    @Test
    fun `extractSkosSchemes parses ConceptScheme with labels and topConcepts`() {
        val model = adapter.parseTurtle(skosTurtle)
        val schemes = adapter.extractSkosSchemes(model)
        assertEquals(1, schemes.size)
        val scheme = schemes[0]
        assertEquals("http://example.org/animals", scheme.uri)
        assertEquals(2, scheme.prefLabels.size)
        assertTrue(scheme.prefLabels.any { it.value == "Animals" && it.lang == "en" })
        assertTrue(scheme.prefLabels.any { it.value == "Tiere" && it.lang == "de" })
        assertEquals(listOf("http://example.org/animal"), scheme.topConcepts)
    }

    @Test
    fun `extractSkosConcepts parses all concepts with properties`() {
        val model = adapter.parseTurtle(skosTurtle)
        val concepts = adapter.extractSkosConcepts(model)
        assertEquals(4, concepts.size)
        val byUri = concepts.associateBy { it.uri }

        val animal = byUri["http://example.org/animal"]!!
        assertEquals("Animal", animal.prefLabels[0].value)
        assertEquals(listOf("http://example.org/mammal"), animal.narrower)
        assertEquals("http://example.org/animals", animal.inScheme)

        val mammal = byUri["http://example.org/mammal"]!!
        assertEquals("Mammal", mammal.prefLabels[0].value)
        assertEquals(1, mammal.altLabels.size)
        assertEquals("Saeugetier", mammal.altLabels[0].value)
        assertEquals(listOf("http://example.org/animal"), mammal.broader)
        assertEquals("Warm-blooded vertebrates", mammal.scopeNote)

        val cat = byUri["http://example.org/cat"]!!
        assertEquals(listOf("http://example.org/mammal"), cat.broader)
        assertEquals(listOf("http://example.org/dog"), cat.related)
        assertEquals("A small domesticated feline", cat.definition)
    }

    @Test
    fun `extractSkosSchemes returns empty for model without SKOS`() {
        val turtle = """
            @prefix ex: <http://example.org/> .
            ex:Alice ex:knows ex:Bob .
        """.trimIndent()
        val model = adapter.parseTurtle(turtle)
        val schemes = adapter.extractSkosSchemes(model)
        assertTrue(schemes.isEmpty())
    }

    @Test
    fun `extractSkosConcepts returns empty for model without SKOS`() {
        val turtle = """
            @prefix ex: <http://example.org/> .
            ex:Alice ex:knows ex:Bob .
        """.trimIndent()
        val model = adapter.parseTurtle(turtle)
        val concepts = adapter.extractSkosConcepts(model)
        assertTrue(concepts.isEmpty())
    }
}
