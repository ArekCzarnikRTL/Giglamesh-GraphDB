package com.agentwork.graphmesh.extraction.ontology

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import com.agentwork.graphmesh.collection.Collection
import com.agentwork.graphmesh.collection.CollectionService
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.ontology.DatatypeProperty
import com.agentwork.graphmesh.ontology.LangLabel
import com.agentwork.graphmesh.ontology.ObjectProperty
import com.agentwork.graphmesh.ontology.Ontology
import com.agentwork.graphmesh.ontology.OntologyClass
import com.agentwork.graphmesh.ontology.OntologyMetadata
import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OntologyGuidedExtractorServiceTest {

    private val promptExecutor = mockk<PromptExecutor>()
    private val ontologyService = mockk<OntologyService>()
    private val collectionService = mockk<CollectionService>()
    private val quadStore = mockk<QuadStore>(relaxed = true)
    private val librarianService = mockk<LibrarianService>()
    private val objectMapper = jacksonObjectMapper()

    private val service = OntologyGuidedExtractorService(
        promptExecutor = promptExecutor,
        ontologyService = ontologyService,
        collectionService = collectionService,
        quadStore = quadStore,
        librarianService = librarianService,
        objectMapper = objectMapper,
        modelName = "gpt-4o"
    )

    private val testOntology = Ontology(
        metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
        classes = mapOf(
            "Person" to OntologyClass(
                id = "Person",
                uri = "http://test.org/Person",
                labels = listOf(LangLabel("Person", "en"))
            ),
            "Organization" to OntologyClass(
                id = "Organization",
                uri = "http://test.org/Organization"
            )
        ),
        objectProperties = mapOf(
            "worksFor" to ObjectProperty(
                id = "worksFor",
                uri = "http://test.org/worksFor",
                domain = "Person",
                range = "Organization"
            )
        ),
        datatypeProperties = mapOf(
            "name" to DatatypeProperty(
                id = "name",
                uri = "http://test.org/name",
                domain = "Person"
            )
        )
    )

    @Test
    fun `extract returns FREE when collection has no ontologyKey`() {
        every { collectionService.findById("col-1") } returns Collection(
            id = "col-1",
            name = "test",
            metadata = emptyMap()
        )

        val result = service.extract("chunk-1", "col-1")

        assertEquals(ExtractionMode.FREE, result.mode)
        assertEquals(0, result.entitiesExtracted)
        assertEquals(0, result.relationshipsExtracted)
        assertEquals(0, result.attributesExtracted)
        assertEquals(0, result.validationFailures)
    }

    @Test
    fun `extract returns FREE when collection not found`() {
        every { collectionService.findById("col-1") } returns null

        val result = service.extract("chunk-1", "col-1")

        assertEquals(ExtractionMode.FREE, result.mode)
    }

    @Test
    fun `extract returns FREE when ontology not found`() {
        every { collectionService.findById("col-1") } returns Collection(
            id = "col-1",
            name = "test",
            metadata = mapOf("ontologyKey" to "animals")
        )
        every { ontologyService.get("animals") } returns null

        val result = service.extract("chunk-1", "col-1")

        assertEquals(ExtractionMode.FREE, result.mode)
    }

    @Test
    fun `extract returns FREE when chunk text is blank`() {
        every { collectionService.findById("col-1") } returns Collection(
            id = "col-1",
            name = "test",
            metadata = mapOf("ontologyKey" to "test-onto")
        )
        every { ontologyService.get("test-onto") } returns testOntology
        every { librarianService.getContent("chunk-1") } returns "   ".toByteArray()

        val result = service.extract("chunk-1", "col-1")

        assertEquals(ExtractionMode.FREE, result.mode)
    }

    @Test
    fun `parseEntities parses valid JSONL`() {
        val response = """
            {"entity": "Alice", "entity_type": "Person"}
            {"entity": "Acme", "entity_type": "Organization"}
            this is not json
            {"entity": "", "entity_type": "Person"}
        """.trimIndent()

        val entities = service.parseEntities(response)

        assertEquals(2, entities.size)
        assertEquals(ExtractedEntity("Alice", "Person"), entities[0])
        assertEquals(ExtractedEntity("Acme", "Organization"), entities[1])
    }

    @Test
    fun `parseEntities skips code fences`() {
        val response = """
            ```json
            {"entity": "Alice", "entity_type": "Person"}
            ```
        """.trimIndent()

        val entities = service.parseEntities(response)

        assertEquals(1, entities.size)
        assertEquals("Alice", entities[0].entity)
    }

    @Test
    fun `parseExtractionItems parses relationships and attributes`() {
        val response = """
            {"type": "relationship", "subject": "Alice", "subject_type": "Person", "relation": "worksFor", "object": "Acme", "object_type": "Organization"}
            {"type": "attribute", "entity": "Alice", "entity_type": "Person", "attribute": "name", "value": "Alice Smith"}
            {"type": "unknown", "foo": "bar"}
        """.trimIndent()

        val items = service.parseExtractionItems(response)

        assertEquals(2, items.size)
        val rel = items[0] as ExtractionItem.Relationship
        assertEquals("Alice", rel.subject)
        assertEquals("worksFor", rel.relation)
        assertEquals("Acme", rel.objectValue)

        val attr = items[1] as ExtractionItem.Attribute
        assertEquals("Alice", attr.entity)
        assertEquals("name", attr.attribute)
        assertEquals("Alice Smith", attr.value)
    }

    @Test
    fun `extract performs two-pass extraction with validation`() {
        every { collectionService.findById("col-1") } returns Collection(
            id = "col-1",
            name = "test",
            metadata = mapOf("ontologyKey" to "test-onto")
        )
        every { ontologyService.get("test-onto") } returns testOntology
        every { librarianService.getContent("chunk-1") } returns
            "Alice works for Acme Corp. Her name is Alice Smith.".toByteArray()

        // Pass 1: entity classification
        val classificationResponse = """
            {"entity": "Alice", "entity_type": "Person"}
            {"entity": "Acme Corp", "entity_type": "Organization"}
        """.trimIndent()

        // Pass 2: relationship/attribute extraction
        val relationshipResponse = """
            {"type": "relationship", "subject": "Alice", "subject_type": "Person", "relation": "worksFor", "object": "Acme Corp", "object_type": "Organization"}
            {"type": "attribute", "entity": "Alice", "entity_type": "Person", "attribute": "name", "value": "Alice Smith"}
        """.trimIndent()

        val msg1 = mockk<Message.Response>()
        every { msg1.content } returns classificationResponse
        val msg2 = mockk<Message.Response>()
        every { msg2.content } returns relationshipResponse

        coEvery { promptExecutor.execute(any(), any()) } returnsMany listOf(
            listOf(msg1),
            listOf(msg2)
        )

        val result = service.extract("chunk-1", "col-1")

        assertEquals(ExtractionMode.ONTOLOGY_GUIDED, result.mode)
        assertEquals(2, result.entitiesExtracted)
        assertEquals(1, result.relationshipsExtracted)
        assertEquals(1, result.attributesExtracted)
        assertEquals(0, result.validationFailures)

        // Verify quads were persisted
        val quadsSlot = slot<List<StoredQuad>>()
        verify { quadStore.insertBatch("col-1", capture(quadsSlot)) }
        val quads = quadsSlot.captured

        // 2 rdf:type + 2 rdfs:label + 1 worksFor + 1 name = 6 knowledge quads
        // + 6 provenance quads = 12 total
        assertEquals(12, quads.size)

        // Verify rdf:type quads exist
        val typeQuads = quads.filter { it.predicate == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" }
        assertEquals(2, typeQuads.size)

        // Verify worksFor relationship quad
        val worksForQuads = quads.filter { it.predicate == "http://test.org/worksFor" }
        assertEquals(1, worksForQuads.size)

        // Verify name attribute quad
        val nameQuads = quads.filter { it.predicate == "http://test.org/name" }
        assertEquals(1, nameQuads.size)
        assertEquals("Alice Smith", nameQuads[0].objectValue)

        // Verify provenance quads
        val provenanceQuads = quads.filter { it.dataset == "urn:graph:source" }
        assertEquals(6, provenanceQuads.size)
    }

    @Test
    fun `extract counts validation failures`() {
        every { collectionService.findById("col-1") } returns Collection(
            id = "col-1",
            name = "test",
            metadata = mapOf("ontologyKey" to "test-onto")
        )
        every { ontologyService.get("test-onto") } returns testOntology
        every { librarianService.getContent("chunk-1") } returns
            "Alice works for Acme Corp.".toByteArray()

        // Pass 1: includes an entity with unknown type
        val classificationResponse = """
            {"entity": "Alice", "entity_type": "Person"}
            {"entity": "Berlin", "entity_type": "City"}
        """.trimIndent()

        // Pass 2: includes invalid relationship (unknownRelation not in ontology)
        val relationshipResponse = """
            {"type": "relationship", "subject": "Alice", "subject_type": "Person", "relation": "unknownRelation", "object": "Acme Corp", "object_type": "Organization"}
            {"type": "attribute", "entity": "Alice", "entity_type": "Person", "attribute": "unknownAttr", "value": "test"}
        """.trimIndent()

        val msg1 = mockk<Message.Response>()
        every { msg1.content } returns classificationResponse
        val msg2 = mockk<Message.Response>()
        every { msg2.content } returns relationshipResponse

        coEvery { promptExecutor.execute(any(), any()) } returnsMany listOf(
            listOf(msg1),
            listOf(msg2)
        )

        val result = service.extract("chunk-1", "col-1")

        assertEquals(ExtractionMode.ONTOLOGY_GUIDED, result.mode)
        // "City" is filtered in entity classification step, so only 1 entity
        assertEquals(1, result.entitiesExtracted)
        assertEquals(0, result.relationshipsExtracted)
        assertEquals(0, result.attributesExtracted)
        // unknownRelation + unknownAttr = 2 validation failures
        assertEquals(2, result.validationFailures)
    }
}
