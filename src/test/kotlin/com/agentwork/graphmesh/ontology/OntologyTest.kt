package com.agentwork.graphmesh.ontology

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OntologyTest {

    @Test
    fun `Cardinality allows valid min max`() {
        val card = Cardinality(min = 1, max = 5)
        assertEquals(1, card.min)
        assertEquals(5, card.max)
    }

    @Test
    fun `Cardinality rejects min greater than max`() {
        assertThrows<IllegalArgumentException> {
            Cardinality(min = 5, max = 1)
        }
    }

    @Test
    fun `Cardinality rejects exact with min`() {
        assertThrows<IllegalArgumentException> {
            Cardinality(exact = 3, min = 1)
        }
    }

    @Test
    fun `Cardinality rejects exact with max`() {
        assertThrows<IllegalArgumentException> {
            Cardinality(exact = 3, max = 5)
        }
    }

    @Test
    fun `Cardinality allows exact alone`() {
        val card = Cardinality(exact = 3)
        assertEquals(3, card.exact)
    }

    @Test
    fun `getSubClasses returns direct subclasses`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Animal" to OntologyClass(id = "Animal", uri = "http://test.org/Animal"),
                "Dog" to OntologyClass(id = "Dog", uri = "http://test.org/Dog", subClassOf = listOf("Animal")),
                "Cat" to OntologyClass(id = "Cat", uri = "http://test.org/Cat", subClassOf = listOf("Animal")),
                "Poodle" to OntologyClass(id = "Poodle", uri = "http://test.org/Poodle", subClassOf = listOf("Dog"))
            )
        )

        val subClasses = ontology.getSubClasses("Animal")
        assertEquals(2, subClasses.size)
        assertTrue(subClasses.contains("Dog"))
        assertTrue(subClasses.contains("Cat"))
    }

    @Test
    fun `getSubClasses returns empty for leaf class`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Animal" to OntologyClass(id = "Animal", uri = "http://test.org/Animal"),
                "Dog" to OntologyClass(id = "Dog", uri = "http://test.org/Dog", subClassOf = listOf("Animal"))
            )
        )

        assertTrue(ontology.getSubClasses("Dog").isEmpty())
    }

    @Test
    fun `getClassHierarchy returns full upward chain`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Thing" to OntologyClass(id = "Thing", uri = "http://test.org/Thing"),
                "Animal" to OntologyClass(id = "Animal", uri = "http://test.org/Animal", subClassOf = listOf("Thing")),
                "Dog" to OntologyClass(id = "Dog", uri = "http://test.org/Dog", subClassOf = listOf("Animal"))
            )
        )

        val hierarchy = ontology.getClassHierarchy("Dog")
        assertEquals(listOf("Dog", "Animal", "Thing"), hierarchy)
    }

    @Test
    fun `getClassHierarchy handles multiple inheritance`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "A" to OntologyClass(id = "A", uri = "http://test.org/A"),
                "B" to OntologyClass(id = "B", uri = "http://test.org/B"),
                "C" to OntologyClass(id = "C", uri = "http://test.org/C", subClassOf = listOf("A", "B"))
            )
        )

        val hierarchy = ontology.getClassHierarchy("C")
        assertEquals(3, hierarchy.size)
        assertEquals("C", hierarchy[0])
        assertTrue(hierarchy.contains("A"))
        assertTrue(hierarchy.contains("B"))
    }

    @Test
    fun `LangLabel defaults to english`() {
        val label = LangLabel("hello")
        assertEquals("en", label.lang)
    }
}
