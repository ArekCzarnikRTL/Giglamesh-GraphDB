package com.agentwork.graphmesh.skos

import com.agentwork.graphmesh.ontology.LangLabel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkosValidatorTest {

    private val validator = SkosValidator()

    private fun concept(
        uri: String,
        prefLabels: List<LangLabel> = listOf(LangLabel("Label", "en")),
        altLabels: List<LangLabel> = emptyList(),
        broader: List<String> = emptyList(),
        narrower: List<String> = emptyList(),
        related: List<String> = emptyList(),
        inScheme: String? = null
    ) = SkosConcept(uri, prefLabels, altLabels, broader, narrower, related, inScheme)

    private fun scheme(
        uri: String,
        prefLabels: List<LangLabel> = listOf(LangLabel("Scheme", "en")),
        topConcepts: List<String> = emptyList()
    ) = SkosConceptScheme(uri, prefLabels, topConcepts)

    @Test
    fun `valid taxonomy produces no errors`() {
        val schemes = listOf(scheme("http://ex.org/scheme", topConcepts = listOf("http://ex.org/A")))
        val concepts = listOf(
            concept("http://ex.org/A", inScheme = "http://ex.org/scheme", narrower = listOf("http://ex.org/B")),
            concept("http://ex.org/B", inScheme = "http://ex.org/scheme", broader = listOf("http://ex.org/A"))
        )
        val errors = validator.validate(schemes, concepts)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `concept without prefLabel is an error`() {
        val concepts = listOf(concept("http://ex.org/A", prefLabels = emptyList()))
        val errors = validator.validate(emptyList(), concepts)
        assertEquals(1, errors.size)
        assertEquals(SkosValidationRule.MISSING_PREF_LABEL, errors[0].rule)
        assertEquals("http://ex.org/A", errors[0].uri)
    }

    @Test
    fun `duplicate prefLabel per language is an error`() {
        val concepts = listOf(
            concept("http://ex.org/A", prefLabels = listOf(
                LangLabel("Label1", "en"),
                LangLabel("Label2", "en")
            ))
        )
        val errors = validator.validate(emptyList(), concepts)
        assertEquals(1, errors.size)
        assertEquals(SkosValidationRule.DUPLICATE_PREF_LABEL_PER_LANG, errors[0].rule)
    }

    @Test
    fun `circular broader chain is an error`() {
        val concepts = listOf(
            concept("http://ex.org/A", broader = listOf("http://ex.org/B")),
            concept("http://ex.org/B", broader = listOf("http://ex.org/C")),
            concept("http://ex.org/C", broader = listOf("http://ex.org/A"))
        )
        val errors = validator.validate(emptyList(), concepts)
        assertTrue(errors.any { it.rule == SkosValidationRule.CIRCULAR_BROADER })
    }

    @Test
    fun `inScheme pointing to non-existent scheme is an error`() {
        val concepts = listOf(
            concept("http://ex.org/A", inScheme = "http://ex.org/missing-scheme")
        )
        val errors = validator.validate(emptyList(), concepts)
        assertEquals(1, errors.size)
        assertEquals(SkosValidationRule.UNKNOWN_SCHEME, errors[0].rule)
    }

    @Test
    fun `broader-narrower asymmetry is a warning`() {
        val concepts = listOf(
            concept("http://ex.org/A", narrower = listOf("http://ex.org/B")),
            concept("http://ex.org/B")
        )
        val errors = validator.validate(emptyList(), concepts)
        assertTrue(errors.any { it.rule == SkosValidationRule.BROADER_NARROWER_ASYMMETRY })
    }
}
