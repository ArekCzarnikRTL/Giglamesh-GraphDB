package com.agentwork.graphmesh.ontology

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultOntologyValidatorTest {

    private val validator = DefaultOntologyValidator()

    private fun ontologyWithClasses(vararg classes: Pair<String, OntologyClass>) = Ontology(
        metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
        classes = classes.toMap()
    )

    @Test
    fun `valid ontology produces no errors`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Animal" to OntologyClass(id = "Animal", uri = "http://test.org/Animal"),
                "Dog" to OntologyClass(id = "Dog", uri = "http://test.org/Dog", subClassOf = listOf("Animal"))
            ),
            objectProperties = mapOf(
                "owns" to ObjectProperty(
                    id = "owns", uri = "http://test.org/owns",
                    domain = "Animal", range = "Animal"
                )
            )
        )
        val errors = validator.validate(ontology)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    @Test
    fun `detects circular inheritance - direct cycle`() {
        val ontology = ontologyWithClasses(
            "A" to OntologyClass(id = "A", uri = "http://test.org/A", subClassOf = listOf("B")),
            "B" to OntologyClass(id = "B", uri = "http://test.org/B", subClassOf = listOf("A"))
        )
        val errors = validator.validate(ontology)
        assertTrue(errors.any { it.rule == ValidationRule.CIRCULAR_INHERITANCE })
    }

    @Test
    fun `detects circular inheritance - transitive cycle`() {
        val ontology = ontologyWithClasses(
            "A" to OntologyClass(id = "A", uri = "http://test.org/A", subClassOf = listOf("B")),
            "B" to OntologyClass(id = "B", uri = "http://test.org/B", subClassOf = listOf("C")),
            "C" to OntologyClass(id = "C", uri = "http://test.org/C", subClassOf = listOf("A"))
        )
        val errors = validator.validate(ontology)
        assertTrue(errors.any { it.rule == ValidationRule.CIRCULAR_INHERITANCE })
    }

    @Test
    fun `detects missing domain class on object property`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf("Animal" to OntologyClass(id = "Animal", uri = "http://test.org/Animal")),
            objectProperties = mapOf(
                "owns" to ObjectProperty(id = "owns", uri = "http://test.org/owns", domain = "NonExistent", range = "Animal")
            )
        )
        val errors = validator.validate(ontology)
        assertTrue(errors.any { it.rule == ValidationRule.MISSING_DOMAIN_CLASS && it.element == "owns" })
    }

    @Test
    fun `detects missing range class on object property`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf("Animal" to OntologyClass(id = "Animal", uri = "http://test.org/Animal")),
            objectProperties = mapOf(
                "owns" to ObjectProperty(id = "owns", uri = "http://test.org/owns", domain = "Animal", range = "NonExistent")
            )
        )
        val errors = validator.validate(ontology)
        assertTrue(errors.any { it.rule == ValidationRule.MISSING_RANGE_CLASS && it.element == "owns" })
    }

    @Test
    fun `detects missing domain class on datatype property`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            datatypeProperties = mapOf(
                "name" to DatatypeProperty(id = "name", uri = "http://test.org/name", domain = "NonExistent")
            )
        )
        val errors = validator.validate(ontology)
        assertTrue(errors.any { it.rule == ValidationRule.MISSING_DOMAIN_CLASS && it.element == "name" })
    }

    @Test
    fun `detects disjoint subclass conflict`() {
        val ontology = ontologyWithClasses(
            "A" to OntologyClass(id = "A", uri = "http://test.org/A"),
            "B" to OntologyClass(id = "B", uri = "http://test.org/B", subClassOf = listOf("A"), disjointWith = listOf("A"))
        )
        val errors = validator.validate(ontology)
        assertTrue(errors.any { it.rule == ValidationRule.DISJOINT_SUBCLASS_CONFLICT && it.element == "B" })
    }

    @Test
    fun `detects functional property with max cardinality greater than 1`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf("Person" to OntologyClass(id = "Person", uri = "http://test.org/Person")),
            datatypeProperties = mapOf(
                "name" to DatatypeProperty(id = "name", uri = "http://test.org/name", domain = "Person", functional = true, cardinality = Cardinality(max = 3))
            )
        )
        val errors = validator.validate(ontology)
        assertTrue(errors.any { it.rule == ValidationRule.FUNCTIONAL_CARDINALITY_CONFLICT && it.element == "name" })
    }

    @Test
    fun `functional property with max 1 is valid`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf("Person" to OntologyClass(id = "Person", uri = "http://test.org/Person")),
            datatypeProperties = mapOf(
                "name" to DatatypeProperty(id = "name", uri = "http://test.org/name", domain = "Person", functional = true, cardinality = Cardinality(max = 1))
            )
        )
        val errors = validator.validate(ontology)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }
}
