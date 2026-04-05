package com.agentwork.graphmesh.extraction.ontology

import com.agentwork.graphmesh.ontology.DatatypeProperty
import com.agentwork.graphmesh.ontology.ObjectProperty
import com.agentwork.graphmesh.ontology.Ontology
import com.agentwork.graphmesh.ontology.OntologyClass
import com.agentwork.graphmesh.ontology.OntologyMetadata
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OntologyValidationFilterTest {

    private val ontology = Ontology(
        metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
        classes = mapOf(
            "LivingThing" to OntologyClass(id = "LivingThing", uri = "http://test.org/LivingThing"),
            "Person" to OntologyClass(id = "Person", uri = "http://test.org/Person", subClassOf = listOf("LivingThing")),
            "Student" to OntologyClass(id = "Student", uri = "http://test.org/Student", subClassOf = listOf("Person")),
            "Organization" to OntologyClass(id = "Organization", uri = "http://test.org/Organization"),
            "University" to OntologyClass(id = "University", uri = "http://test.org/University", subClassOf = listOf("Organization"))
        ),
        objectProperties = mapOf(
            "worksFor" to ObjectProperty(id = "worksFor", uri = "http://test.org/worksFor", domain = "Person", range = "Organization"),
            "knows" to ObjectProperty(id = "knows", uri = "http://test.org/knows", domain = null, range = null)
        ),
        datatypeProperties = mapOf(
            "name" to DatatypeProperty(id = "name", uri = "http://test.org/name", domain = "Person"),
            "founded" to DatatypeProperty(id = "founded", uri = "http://test.org/founded", domain = "Organization")
        )
    )

    private val filter = OntologyValidationFilter(ontology)

    // validateEntity tests

    @Test
    fun `validateEntity returns true for existing class`() {
        assertTrue(filter.validateEntity("Person"))
    }

    @Test
    fun `validateEntity returns false for non-existing class`() {
        assertFalse(filter.validateEntity("Animal"))
    }

    // validateRelationship tests

    @Test
    fun `validateRelationship returns true when domain and range match exactly`() {
        assertTrue(filter.validateRelationship("Person", "worksFor", "Organization"))
    }

    @Test
    fun `validateRelationship returns true when subject is subclass of domain`() {
        assertTrue(filter.validateRelationship("Student", "worksFor", "Organization"))
    }

    @Test
    fun `validateRelationship returns true when object is subclass of range`() {
        assertTrue(filter.validateRelationship("Person", "worksFor", "University"))
    }

    @Test
    fun `validateRelationship returns false for non-existing relation`() {
        assertFalse(filter.validateRelationship("Person", "nonExistent", "Organization"))
    }

    @Test
    fun `validateRelationship returns false when domain does not match`() {
        assertFalse(filter.validateRelationship("Organization", "worksFor", "Organization"))
    }

    @Test
    fun `validateRelationship returns false when range does not match`() {
        assertFalse(filter.validateRelationship("Person", "worksFor", "Person"))
    }

    @Test
    fun `validateRelationship returns true when domain and range are null (unconstrained)`() {
        assertTrue(filter.validateRelationship("Organization", "knows", "University"))
    }

    // validateAttribute tests

    @Test
    fun `validateAttribute returns true when domain matches`() {
        assertTrue(filter.validateAttribute("Person", "name"))
    }

    @Test
    fun `validateAttribute returns true when entity is subclass of domain`() {
        assertTrue(filter.validateAttribute("Student", "name"))
    }

    @Test
    fun `validateAttribute returns false for non-existing attribute`() {
        assertFalse(filter.validateAttribute("Person", "nonExistent"))
    }

    @Test
    fun `validateAttribute returns false when domain does not match`() {
        assertFalse(filter.validateAttribute("Organization", "name"))
    }
}
