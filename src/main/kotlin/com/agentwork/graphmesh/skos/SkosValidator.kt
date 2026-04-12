package com.agentwork.graphmesh.skos

import org.springframework.stereotype.Component

enum class SkosValidationRule {
    MISSING_PREF_LABEL,
    DUPLICATE_PREF_LABEL_PER_LANG,
    CIRCULAR_BROADER,
    UNKNOWN_SCHEME,
    BROADER_NARROWER_ASYMMETRY
}

data class SkosValidationError(
    val uri: String,
    val rule: SkosValidationRule,
    val message: String
)

@Component
class SkosValidator {

    fun validate(
        schemes: List<SkosConceptScheme>,
        concepts: List<SkosConcept>
    ): List<SkosValidationError> {
        val errors = mutableListOf<SkosValidationError>()
        errors.addAll(checkMissingPrefLabel(concepts))
        errors.addAll(checkDuplicatePrefLabelPerLang(concepts))
        errors.addAll(checkCircularBroader(concepts))
        errors.addAll(checkUnknownScheme(schemes, concepts))
        errors.addAll(checkBroaderNarrowerAsymmetry(concepts))
        return errors
    }

    private fun checkMissingPrefLabel(concepts: List<SkosConcept>): List<SkosValidationError> =
        concepts.filter { it.prefLabels.isEmpty() }.map { c ->
            SkosValidationError(c.uri, SkosValidationRule.MISSING_PREF_LABEL,
                "Concept '${c.uri}' has no skos:prefLabel")
        }

    private fun checkDuplicatePrefLabelPerLang(concepts: List<SkosConcept>): List<SkosValidationError> =
        concepts.flatMap { c ->
            c.prefLabels.groupBy { it.lang }
                .filter { (_, labels) -> labels.size > 1 }
                .map { (lang, _) ->
                    SkosValidationError(c.uri, SkosValidationRule.DUPLICATE_PREF_LABEL_PER_LANG,
                        "Concept '${c.uri}' has ${c.prefLabels.count { it.lang == lang }} prefLabels for language '$lang'")
                }
        }

    private fun checkCircularBroader(concepts: List<SkosConcept>): List<SkosValidationError> {
        val errors = mutableListOf<SkosValidationError>()
        val broaderMap = concepts.associate { it.uri to it.broader }
        for (concept in concepts) {
            val visited = mutableSetOf<String>()
            var current = concept.broader
            while (current.isNotEmpty()) {
                val next = mutableListOf<String>()
                for (parentUri in current) {
                    if (parentUri == concept.uri) {
                        errors.add(SkosValidationError(concept.uri, SkosValidationRule.CIRCULAR_BROADER,
                            "Circular broader chain detected for '${concept.uri}'"))
                        break
                    }
                    if (parentUri !in visited) {
                        visited.add(parentUri)
                        broaderMap[parentUri]?.let { next.addAll(it) }
                    }
                }
                if (errors.any { it.uri == concept.uri && it.rule == SkosValidationRule.CIRCULAR_BROADER }) break
                current = next
            }
        }
        return errors
    }

    private fun checkUnknownScheme(
        schemes: List<SkosConceptScheme>,
        concepts: List<SkosConcept>
    ): List<SkosValidationError> {
        val schemeUris = schemes.map { it.uri }.toSet()
        return concepts.filter { it.inScheme != null && it.inScheme !in schemeUris }.map { c ->
            SkosValidationError(c.uri, SkosValidationRule.UNKNOWN_SCHEME,
                "Concept '${c.uri}' references unknown scheme '${c.inScheme}'")
        }
    }

    private fun checkBroaderNarrowerAsymmetry(concepts: List<SkosConcept>): List<SkosValidationError> {
        val conceptMap = concepts.associateBy { it.uri }
        val errors = mutableListOf<SkosValidationError>()
        for (concept in concepts) {
            for (narrowerUri in concept.narrower) {
                val narrowerConcept = conceptMap[narrowerUri]
                if (narrowerConcept != null && concept.uri !in narrowerConcept.broader) {
                    errors.add(SkosValidationError(concept.uri, SkosValidationRule.BROADER_NARROWER_ASYMMETRY,
                        "'${concept.uri}' has narrower '${narrowerUri}' but '${narrowerUri}' does not have broader '${concept.uri}'"))
                }
            }
        }
        return errors
    }
}
