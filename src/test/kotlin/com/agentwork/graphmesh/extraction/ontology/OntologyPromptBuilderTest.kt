package com.agentwork.graphmesh.extraction.ontology

import com.agentwork.graphmesh.ontology.DatatypeProperty
import com.agentwork.graphmesh.ontology.LangLabel
import com.agentwork.graphmesh.ontology.ObjectProperty
import com.agentwork.graphmesh.ontology.OntologyClass
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OntologyPromptBuilderTest {

    private val builder = OntologyPromptBuilder()

    private val personClass = OntologyClass(
        id = "Person",
        uri = "http://ex.org/Person",
        labels = listOf(LangLabel("Person", "en")),
        comment = "A human being"
    )

    private val orgClass = OntologyClass(
        id = "Organization",
        uri = "http://ex.org/Organization",
        labels = listOf(LangLabel("Organisation", "de"))
    )

    private val worksForProp = ObjectProperty(
        id = "worksFor",
        uri = "http://ex.org/worksFor",
        domain = "Person",
        range = "Organization",
        comment = "Employment relation"
    )

    private val nameProp = DatatypeProperty(
        id = "name",
        uri = "http://ex.org/name",
        domain = "Person",
        range = "http://www.w3.org/2001/XMLSchema#string"
    )

    // --- buildSchemaSection tests ---

    @Test
    fun `buildSchemaSection with classes only produces Entity Types section`() {
        val subset = OntologySubset(
            classes = mapOf("Person" to personClass),
            objectProperties = emptyMap(),
            datatypeProperties = emptyMap()
        )

        val result = builder.buildSchemaSection(subset)

        assertContains(result, "## Entity Types:")
        assertContains(result, "**Person**")
        assertContains(result, "A human being")
    }

    @Test
    fun `buildSchemaSection with classes only omits Relationships and Attributes sections`() {
        val subset = OntologySubset(
            classes = mapOf("Person" to personClass),
            objectProperties = emptyMap(),
            datatypeProperties = emptyMap()
        )

        val result = builder.buildSchemaSection(subset)

        assertFalse(result.contains("## Relationships:"), "Should not contain Relationships section")
        assertFalse(result.contains("## Attributes:"), "Should not contain Attributes section")
    }

    @Test
    fun `buildSchemaSection with classes and objectProperties produces both sections`() {
        val subset = OntologySubset(
            classes = mapOf("Person" to personClass, "Organization" to orgClass),
            objectProperties = mapOf("worksFor" to worksForProp),
            datatypeProperties = emptyMap()
        )

        val result = builder.buildSchemaSection(subset)

        assertContains(result, "## Entity Types:")
        assertContains(result, "## Relationships:")
        assertFalse(result.contains("## Attributes:"), "Should not contain Attributes section")
    }

    @Test
    fun `buildSchemaSection with all three types produces all three sections`() {
        val subset = OntologySubset(
            classes = mapOf("Person" to personClass, "Organization" to orgClass),
            objectProperties = mapOf("worksFor" to worksForProp),
            datatypeProperties = mapOf("name" to nameProp)
        )

        val result = builder.buildSchemaSection(subset)

        assertContains(result, "## Entity Types:")
        assertContains(result, "## Relationships:")
        assertContains(result, "## Attributes:")
    }

    @Test
    fun `buildSchemaSection uses first label when available`() {
        val subset = OntologySubset(
            classes = mapOf("Organization" to orgClass),
            objectProperties = emptyMap(),
            datatypeProperties = emptyMap()
        )

        val result = builder.buildSchemaSection(subset)

        assertContains(result, "Organisation")
        assertContains(result, "**Organization**")
    }

    @Test
    fun `buildSchemaSection falls back to ID when no labels`() {
        val classWithoutLabel = OntologyClass(
            id = "Vehicle",
            uri = "http://ex.org/Vehicle",
            labels = emptyList()
        )
        val subset = OntologySubset(
            classes = mapOf("Vehicle" to classWithoutLabel),
            objectProperties = emptyMap(),
            datatypeProperties = emptyMap()
        )

        val result = builder.buildSchemaSection(subset)

        // label falls back to id
        assertContains(result, "**Vehicle** (Vehicle)")
    }

    @Test
    fun `buildSchemaSection shows domain-range for objectProperties`() {
        val subset = OntologySubset(
            classes = mapOf("Person" to personClass, "Organization" to orgClass),
            objectProperties = mapOf("worksFor" to worksForProp),
            datatypeProperties = emptyMap()
        )

        val result = builder.buildSchemaSection(subset)

        assertContains(result, "**worksFor**")
        assertContains(result, "Employment relation")
        assertContains(result, "(Person -> Organization)")
    }

    @Test
    fun `buildSchemaSection shows domain-range for datatypeProperties`() {
        val subset = OntologySubset(
            classes = mapOf("Person" to personClass),
            objectProperties = emptyMap(),
            datatypeProperties = mapOf("name" to nameProp)
        )

        val result = builder.buildSchemaSection(subset)

        assertContains(result, "**name**")
        assertContains(result, "(Person -> http://www.w3.org/2001/XMLSchema#string)")
    }

    @Test
    fun `buildSchemaSection includes class comment when present`() {
        val subset = OntologySubset(
            classes = mapOf("Person" to personClass),
            objectProperties = emptyMap(),
            datatypeProperties = emptyMap()
        )

        val result = builder.buildSchemaSection(subset)

        assertContains(result, ": A human being")
    }

    @Test
    fun `buildSchemaSection omits comment when absent`() {
        val subset = OntologySubset(
            classes = mapOf("Organization" to orgClass),
            objectProperties = emptyMap(),
            datatypeProperties = emptyMap()
        )

        val result = builder.buildSchemaSection(subset)

        // No colon after the label if no comment
        assertTrue(!result.contains(": null"), "Should not include 'null' comment")
    }

    // --- classificationPrompt tests ---

    @Test
    fun `classificationPrompt contains schema section`() {
        val schema = "## Entity Types:\n- **Person** (Person): A human being"
        val result = builder.classificationPrompt(schema)

        assertContains(result, schema)
    }

    @Test
    fun `classificationPrompt contains JSONL format instructions`() {
        val result = builder.classificationPrompt("## Entity Types:")

        assertContains(result, "\"type\": \"entity\"")
        assertContains(result, "entity_type")
    }

    @Test
    fun `classificationPrompt contains ontology usage rules`() {
        val result = builder.classificationPrompt("schema")

        assertContains(result, "Schema")
    }

    // --- relationshipPrompt tests ---

    @Test
    fun `relationshipPrompt contains schema section`() {
        val schema = "## Relationships:\n- **worksFor**: Employment relation"
        val result = builder.relationshipPrompt(schema)

        assertContains(result, schema)
    }

    @Test
    fun `relationshipPrompt contains relationship format instructions`() {
        val result = builder.relationshipPrompt("## Relationships:")

        assertContains(result, "\"type\": \"relationship\"")
        assertContains(result, "subject_type")
        assertContains(result, "object_type")
    }

    @Test
    fun `relationshipPrompt contains attribute format instructions`() {
        val result = builder.relationshipPrompt("## Attributes:")

        assertContains(result, "\"type\": \"attribute\"")
        assertContains(result, "entity_type")
        assertContains(result, "attribute")
    }

    // --- Empty maps omit sections ---

    @Test
    fun `empty objectProperties omits Relationships section`() {
        val subset = OntologySubset(
            classes = mapOf("Person" to personClass),
            objectProperties = emptyMap(),
            datatypeProperties = mapOf("name" to nameProp)
        )

        val result = builder.buildSchemaSection(subset)

        assertFalse(result.contains("## Relationships:"), "Empty objectProperties should omit Relationships section")
        assertContains(result, "## Attributes:")
    }

    @Test
    fun `empty datatypeProperties omits Attributes section`() {
        val subset = OntologySubset(
            classes = mapOf("Person" to personClass),
            objectProperties = mapOf("worksFor" to worksForProp),
            datatypeProperties = emptyMap()
        )

        val result = builder.buildSchemaSection(subset)

        assertContains(result, "## Relationships:")
        assertFalse(result.contains("## Attributes:"), "Empty datatypeProperties should omit Attributes section")
    }
}
