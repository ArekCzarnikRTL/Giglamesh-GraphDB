package com.agentwork.graphmesh.ontology

import org.springframework.stereotype.Component

data class ValidationError(
    val element: String,
    val rule: ValidationRule,
    val message: String
)

enum class ValidationRule {
    CIRCULAR_INHERITANCE,
    MISSING_DOMAIN_CLASS,
    MISSING_RANGE_CLASS,
    INVALID_CARDINALITY,
    DISJOINT_SUBCLASS_CONFLICT,
    FUNCTIONAL_CARDINALITY_CONFLICT
}

@Component
class DefaultOntologyValidator {

    fun validate(ontology: Ontology): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        errors.addAll(checkCircularInheritance(ontology))
        errors.addAll(checkDomainRangeReferences(ontology))
        errors.addAll(checkDisjointSubclassConflicts(ontology))
        errors.addAll(checkFunctionalCardinality(ontology))
        return errors
    }

    private fun checkCircularInheritance(ontology: Ontology): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        for ((classId, cls) in ontology.classes) {
            val visited = mutableSetOf<String>()
            var current = cls.subClassOf
            while (current.isNotEmpty()) {
                val next = mutableListOf<String>()
                for (parentId in current) {
                    if (parentId == classId) {
                        errors.add(ValidationError(
                            element = classId,
                            rule = ValidationRule.CIRCULAR_INHERITANCE,
                            message = "Zirkuläre Vererbung erkannt: $classId -> ... -> $classId"
                        ))
                        break
                    }
                    if (parentId !in visited) {
                        visited.add(parentId)
                        ontology.classes[parentId]?.subClassOf?.let { next.addAll(it) }
                    }
                }
                current = next
            }
        }
        return errors
    }

    private fun checkDomainRangeReferences(ontology: Ontology): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val classIds = ontology.classes.keys
        for ((propId, prop) in ontology.objectProperties) {
            prop.domain?.let { domain ->
                if (domain !in classIds) {
                    errors.add(ValidationError(propId, ValidationRule.MISSING_DOMAIN_CLASS, "Domain-Klasse '$domain' existiert nicht"))
                }
            }
            prop.range?.let { range ->
                if (range !in classIds) {
                    errors.add(ValidationError(propId, ValidationRule.MISSING_RANGE_CLASS, "Range-Klasse '$range' existiert nicht"))
                }
            }
        }
        for ((propId, prop) in ontology.datatypeProperties) {
            prop.domain?.let { domain ->
                if (domain !in classIds) {
                    errors.add(ValidationError(propId, ValidationRule.MISSING_DOMAIN_CLASS, "Domain-Klasse '$domain' existiert nicht"))
                }
            }
        }
        return errors
    }

    private fun checkDisjointSubclassConflicts(ontology: Ontology): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        for ((classId, cls) in ontology.classes) {
            for (disjoint in cls.disjointWith) {
                if (disjoint in cls.subClassOf) {
                    errors.add(ValidationError(classId, ValidationRule.DISJOINT_SUBCLASS_CONFLICT,
                        "Klasse '$classId' ist disjunkt mit '$disjoint' und gleichzeitig Subklasse"))
                }
            }
        }
        return errors
    }

    private fun checkFunctionalCardinality(ontology: Ontology): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        for ((propId, prop) in ontology.datatypeProperties) {
            if (prop.functional && prop.cardinality?.max != null && prop.cardinality.max > 1) {
                errors.add(ValidationError(propId, ValidationRule.FUNCTIONAL_CARDINALITY_CONFLICT,
                    "Functional Property '$propId' darf maxCardinality > 1 nicht haben"))
            }
        }
        return errors
    }
}
