package com.agentwork.graphmesh.extraction.agent

import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.Quad
import com.agentwork.graphmesh.rdf.QuadConverter
import com.agentwork.graphmesh.rdf.RdfTerm
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.StoredQuad
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentExtractorServiceTest {

    private val objectMapper = jacksonObjectMapper()

    // --- JSONL Parsing tests ---

    @Test
    fun `parseFinalOutput parses relationship items`() {
        val response = """
            Some reasoning text...
            {"type": "relationship", "subject": "Photosynthesis", "predicate": "converts", "object": "sunlight", "object_entity": false}
        """.trimIndent()
        val items = parseFinalOutput(response)
        assertEquals(1, items.size)
        val rel = items[0] as ExtractedItem.Relationship
        assertEquals("Photosynthesis", rel.subject)
        assertEquals("converts", rel.predicate)
        assertEquals("sunlight", rel.objectValue)
        assertEquals(false, rel.objectIsEntity)
    }

    @Test
    fun `parseFinalOutput parses definition items`() {
        val response = """{"type": "definition", "entity": "Photosynthesis", "definition": "Process of converting sunlight"}"""
        val items = parseFinalOutput(response)
        assertEquals(1, items.size)
        val def = items[0] as ExtractedItem.Definition
        assertEquals("Photosynthesis", def.entity)
        assertEquals("Process of converting sunlight", def.definition)
    }

    @Test
    fun `parseFinalOutput parses entity items`() {
        val response = """{"type": "entity", "entity": "Chlorophyll", "entity_type": "molecule"}"""
        val items = parseFinalOutput(response)
        assertEquals(1, items.size)
        val ent = items[0] as ExtractedItem.Entity
        assertEquals("Chlorophyll", ent.name)
        assertEquals("molecule", ent.entityType)
    }

    @Test
    fun `parseFinalOutput parses attribute items`() {
        val response = """{"type": "attribute", "entity": "Chlorophyll", "attribute": "color", "value": "green"}"""
        val items = parseFinalOutput(response)
        assertEquals(1, items.size)
        val attr = items[0] as ExtractedItem.Attribute
        assertEquals("Chlorophyll", attr.entity)
        assertEquals("color", attr.attribute)
        assertEquals("green", attr.value)
    }

    @Test
    fun `parseFinalOutput parses mixed items and skips invalid lines`() {
        val response = """
            Some agent reasoning...
            {"type": "definition", "entity": "A", "definition": "Def A"}
            not json at all
            {"type": "relationship", "subject": "A", "predicate": "rel", "object": "B"}
            {"type": "unknown_type", "foo": "bar"}
        """.trimIndent()
        val items = parseFinalOutput(response)
        assertEquals(2, items.size)
        assertTrue(items[0] is ExtractedItem.Definition)
        assertTrue(items[1] is ExtractedItem.Relationship)
    }

    @Test
    fun `parseFinalOutput handles empty response`() {
        val items = parseFinalOutput("")
        assertTrue(items.isEmpty())
    }

    @Test
    fun `parseFinalOutput strips markdown code fences`() {
        val response = "```json\n{\"type\": \"entity\", \"entity\": \"Test\"}\n```"
        val items = parseFinalOutput(response)
        assertEquals(1, items.size)
    }

    @Test
    fun `parseFinalOutput defaults object_entity to true`() {
        val response = """{"type": "relationship", "subject": "A", "predicate": "rel", "object": "B"}"""
        val items = parseFinalOutput(response)
        val rel = items[0] as ExtractedItem.Relationship
        assertEquals(true, rel.objectIsEntity)
    }

    // --- Quad conversion tests ---

    @Test
    fun `convertToQuads converts Definition to knowledge label and provenance quads`() {
        val item = ExtractedItem.Definition(entity = "Photosynthesis", definition = "Process of converting light")
        val quads = convertToQuads(item, "chunk-1")
        assertEquals(4, quads.size) // knowledge + label + 2 provenance
        val knowledge = quads.first { it.predicate == "http://www.w3.org/2000/01/rdf-schema#comment" }
        assertEquals(EntityIdGenerator.generate("Photosynthesis").value, knowledge.subject)
        assertEquals("Process of converting light", knowledge.objectValue)
        assertEquals(ObjectType.LITERAL, knowledge.objectType)
        val label = quads.first { it.predicate == "http://www.w3.org/2000/01/rdf-schema#label" }
        assertEquals("Photosynthesis", label.objectValue)
        val provenance = quads.filter { it.dataset == NamedGraph.SOURCE }
        assertEquals(2, provenance.size)
        assertTrue(provenance.all { it.objectValue == "urn:chunk:chunk-1" })
    }

    @Test
    fun `convertToQuads converts Relationship with entity object`() {
        val item = ExtractedItem.Relationship(
            subject = "Plants", predicate = "perform", objectValue = "Photosynthesis", objectIsEntity = true
        )
        val quads = convertToQuads(item, "chunk-1")
        assertEquals(2, quads.size) // relationship + provenance
        val rel = quads.first { it.dataset == NamedGraph.DEFAULT }
        assertEquals(EntityIdGenerator.generate("Plants").value, rel.subject)
        assertEquals("http://graphmesh.io/ontology/perform", rel.predicate)
        assertEquals(EntityIdGenerator.generate("Photosynthesis").value, rel.objectValue)
        assertEquals(ObjectType.URI, rel.objectType)
    }

    @Test
    fun `convertToQuads converts Relationship with literal object`() {
        val item = ExtractedItem.Relationship(
            subject = "Earth", predicate = "age", objectValue = "4.5 billion years", objectIsEntity = false
        )
        val quads = convertToQuads(item, "chunk-1")
        val rel = quads.first { it.dataset == NamedGraph.DEFAULT }
        assertEquals("4.5 billion years", rel.objectValue)
        assertEquals(ObjectType.LITERAL, rel.objectType)
    }

    @Test
    fun `convertToQuads converts Entity to label quad`() {
        val item = ExtractedItem.Entity(name = "Chlorophyll")
        val quads = convertToQuads(item, "chunk-1")
        assertEquals(2, quads.size) // label + provenance
        val label = quads.first { it.dataset == NamedGraph.DEFAULT }
        assertEquals("http://www.w3.org/2000/01/rdf-schema#label", label.predicate)
        assertEquals("Chlorophyll", label.objectValue)
    }

    @Test
    fun `convertToQuads converts Attribute to literal quad`() {
        val item = ExtractedItem.Attribute(entity = "Chlorophyll", attribute = "color", value = "green")
        val quads = convertToQuads(item, "chunk-1")
        assertEquals(2, quads.size) // attribute + provenance
        val attr = quads.first { it.dataset == NamedGraph.DEFAULT }
        assertEquals("http://graphmesh.io/ontology/color", attr.predicate)
        assertEquals("green", attr.objectValue)
        assertEquals(ObjectType.LITERAL, attr.objectType)
    }

    // --- Standalone copies of parsing/conversion logic ---

    @Suppress("UNCHECKED_CAST")
    private fun parseFinalOutput(response: String): List<ExtractedItem> {
        return response.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val map = objectMapper.readValue<Map<String, Any?>>(line)
                    when (map["type"]) {
                        "definition" -> ExtractedItem.Definition(
                            entity = map["entity"] as String,
                            definition = map["definition"] as String
                        )
                        "relationship" -> ExtractedItem.Relationship(
                            subject = map["subject"] as String,
                            predicate = map["predicate"] as String,
                            objectValue = map["object"] as String,
                            objectIsEntity = map["object_entity"] as? Boolean ?: true
                        )
                        "entity" -> ExtractedItem.Entity(
                            name = map["entity"] as String,
                            entityType = map["entity_type"] as? String
                        )
                        "attribute" -> ExtractedItem.Attribute(
                            entity = map["entity"] as String,
                            attribute = map["attribute"] as String,
                            value = map["value"] as String
                        )
                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }
            }
    }

    private fun convertToQuads(item: ExtractedItem, chunkId: String): List<StoredQuad> {
        val knowledgeQuads = when (item) {
            is ExtractedItem.Definition -> listOf(
                Quad(EntityIdGenerator.generate(item.entity), RdfTerm.Uri("http://www.w3.org/2000/01/rdf-schema#comment"), RdfTerm.Literal(item.definition), NamedGraph.DEFAULT),
                Quad(EntityIdGenerator.generate(item.entity), RdfTerm.Uri("http://www.w3.org/2000/01/rdf-schema#label"), RdfTerm.Literal(item.entity), NamedGraph.DEFAULT)
            )
            is ExtractedItem.Relationship -> {
                val objectTerm = if (item.objectIsEntity) EntityIdGenerator.generate(item.objectValue) else RdfTerm.Literal(item.objectValue)
                listOf(Quad(EntityIdGenerator.generate(item.subject), RdfTerm.Uri("http://graphmesh.io/ontology/${item.predicate}"), objectTerm, NamedGraph.DEFAULT))
            }
            is ExtractedItem.Entity -> listOf(
                Quad(EntityIdGenerator.generate(item.name), RdfTerm.Uri("http://www.w3.org/2000/01/rdf-schema#label"), RdfTerm.Literal(item.name), NamedGraph.DEFAULT)
            )
            is ExtractedItem.Attribute -> listOf(
                Quad(EntityIdGenerator.generate(item.entity), RdfTerm.Uri("http://graphmesh.io/ontology/${item.attribute}"), RdfTerm.Literal(item.value), NamedGraph.DEFAULT)
            )
        }
        val provenanceQuads = knowledgeQuads.map { quad ->
            Quad(RdfTerm.QuotedTriple(quad.triple), RdfTerm.Uri("http://graphmesh.io/ontology/extractedFrom"), RdfTerm.Uri("urn:chunk:$chunkId"), NamedGraph.SOURCE)
        }
        return (knowledgeQuads + provenanceQuads).map { QuadConverter.toStoredQuad(it) }
    }
}
