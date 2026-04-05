package com.agentwork.graphmesh.extraction.ontology

import com.agentwork.graphmesh.ontology.Ontology

class OntologyValidationFilter(private val ontology: Ontology) {

    fun validateEntity(entityType: String): Boolean =
        entityType in ontology.classes

    fun validateRelationship(subjectType: String, relation: String, objectType: String): Boolean {
        val property = ontology.objectProperties[relation] ?: return false
        val domainValid = property.domain?.let { domain -> isTypeOrSubtype(subjectType, domain) } ?: true
        val rangeValid = property.range?.let { range -> isTypeOrSubtype(objectType, range) } ?: true
        return domainValid && rangeValid
    }

    fun validateAttribute(entityType: String, attribute: String): Boolean {
        val property = ontology.datatypeProperties[attribute] ?: return false
        return property.domain?.let { domain -> isTypeOrSubtype(entityType, domain) } ?: true
    }

    private fun isTypeOrSubtype(actualType: String, expectedType: String): Boolean {
        if (actualType == expectedType) return true
        return expectedType in ontology.getClassHierarchy(actualType)
    }
}
