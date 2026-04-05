package com.agentwork.graphmesh.extraction.definition

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefinitionExtractorServiceTest {

    private val objectMapper = jacksonObjectMapper()

    // --- JSONL Parsing (standalone copy) ---

    @Test
    fun `parseJsonlDefinitions extracts valid definitions`() {
        val response = """
            {"entity": "Photosynthesis", "definition": "Process by which plants convert sunlight into chemical energy"}
            {"entity": "Chlorophyll", "definition": "Green pigment that enables photosynthesis"}
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(2, results.size)
        assertEquals("Photosynthesis", results[0].entity)
        assertEquals("Process by which plants convert sunlight into chemical energy", results[0].definition)
        assertEquals("Chlorophyll", results[1].entity)
    }

    @Test
    fun `parseJsonlDefinitions skips blank lines`() {
        val response = """
            {"entity": "A", "definition": "Def A"}

            {"entity": "B", "definition": "Def B"}
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `parseJsonlDefinitions skips invalid JSON lines`() {
        val response = """
            {"entity": "A", "definition": "Def A"}
            This is not JSON
            {"entity": "B", "definition": "Def B"}
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `parseJsonlDefinitions skips lines with missing fields`() {
        val response = """
            {"entity": "A", "definition": "Def A"}
            {"entity": "B"}
            {"definition": "orphan definition"}
            {"entity": "C", "definition": "Def C"}
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(2, results.size)
        assertEquals("A", results[0].entity)
        assertEquals("C", results[1].entity)
    }

    @Test
    fun `parseJsonlDefinitions skips lines with empty values`() {
        val response = """
            {"entity": "", "definition": "Def A"}
            {"entity": "B", "definition": ""}
            {"entity": "C", "definition": "Def C"}
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(1, results.size)
        assertEquals("C", results[0].entity)
    }

    @Test
    fun `parseJsonlDefinitions strips markdown code fences`() {
        val response = """
            ```json
            {"entity": "A", "definition": "Def A"}
            {"entity": "B", "definition": "Def B"}
            ```
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(2, results.size)
    }

    @Test
    fun `parseJsonlDefinitions handles empty response`() {
        val results = parseJsonlDefinitions("")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `parseJsonlDefinitions handles truncated last line`() {
        val response = """
            {"entity": "A", "definition": "Def A"}
            {"entity": "B", "defini
        """.trimIndent()

        val results = parseJsonlDefinitions(response)
        assertEquals(1, results.size)
        assertEquals("A", results[0].entity)
    }

    // --- extract() with MockK ---

    @Test
    fun `extract generates knowledge label and provenance quads`() {
        val promptExecutor = mockk<PromptExecutor>()
        val quadStore = mockk<QuadStore>(relaxed = true)
        val librarianService = mockk<LibrarianService>()

        val service = DefinitionExtractorService(promptExecutor, quadStore, librarianService, "gpt-4o")

        every { librarianService.getContent("chunk-1") } returns
            "Photosynthesis is the process by which plants convert sunlight.".toByteArray()

        val llmResponse = """{"entity": "Photosynthesis", "definition": "Process by which plants convert sunlight into chemical energy"}"""
        val message = mockk<Message.Response>()
        every { message.content } returns llmResponse
        coEvery { promptExecutor.execute(any(), any()) } returns listOf(message)

        val result = service.extract("chunk-1", "col-1")

        assertEquals(1, result.definitionsExtracted)
        assertEquals(listOf("Photosynthesis"), result.entitiesFound)
        assertEquals("chunk-1", result.chunkId)

        val quadsSlot = slot<List<StoredQuad>>()
        io.mockk.verify { quadStore.insertBatch("col-1", capture(quadsSlot)) }
        val quads = quadsSlot.captured

        // 1 knowledge + 1 label + 1 provenance = 3 quads
        assertEquals(3, quads.size)

        // Knowledge quad: rdfs:comment
        val knowledgeQuad = quads.first { it.predicate == "http://www.w3.org/2000/01/rdf-schema#comment" }
        assertEquals(EntityIdGenerator.generate("Photosynthesis").value, knowledgeQuad.subject)
        assertEquals("Process by which plants convert sunlight into chemical energy", knowledgeQuad.objectValue)
        assertEquals(ObjectType.LITERAL, knowledgeQuad.objectType)

        // Label quad: rdfs:label
        val labelQuad = quads.first { it.predicate == "http://www.w3.org/2000/01/rdf-schema#label" }
        assertEquals(EntityIdGenerator.generate("Photosynthesis").value, labelQuad.subject)
        assertEquals("Photosynthesis", labelQuad.objectValue)
        assertEquals(ObjectType.LITERAL, labelQuad.objectType)

        // Provenance quad: extractedFrom in SOURCE graph
        val provenanceQuad = quads.first { it.predicate == "http://graphmesh.io/ontology/extractedFrom" }
        assertEquals("urn:chunk:chunk-1", provenanceQuad.objectValue)
        assertEquals("urn:graph:source", provenanceQuad.dataset)
        assertEquals(ObjectType.URI, provenanceQuad.objectType)
    }

    @Test
    fun `extract returns zero result for blank chunk text`() {
        val promptExecutor = mockk<PromptExecutor>()
        val quadStore = mockk<QuadStore>()
        val librarianService = mockk<LibrarianService>()

        val service = DefinitionExtractorService(promptExecutor, quadStore, librarianService, "gpt-4o")

        every { librarianService.getContent("chunk-1") } returns "   ".toByteArray()

        val result = service.extract("chunk-1", "col-1")

        assertEquals(0, result.definitionsExtracted)
        assertTrue(result.entitiesFound.isEmpty())
    }

    @Test
    fun `extract deduplicates label quads by subject`() {
        val promptExecutor = mockk<PromptExecutor>()
        val quadStore = mockk<QuadStore>(relaxed = true)
        val librarianService = mockk<LibrarianService>()

        val service = DefinitionExtractorService(promptExecutor, quadStore, librarianService, "gpt-4o")

        every { librarianService.getContent("chunk-1") } returns "Some text".toByteArray()

        val llmResponse = """
            {"entity": "Photosynthesis", "definition": "Definition one"}
            {"entity": "Photosynthesis", "definition": "Definition two"}
        """.trimIndent()
        val message = mockk<Message.Response>()
        every { message.content } returns llmResponse
        coEvery { promptExecutor.execute(any(), any()) } returns listOf(message)

        service.extract("chunk-1", "col-1")

        val quadsSlot = slot<List<StoredQuad>>()
        io.mockk.verify { quadStore.insertBatch("col-1", capture(quadsSlot)) }
        val quads = quadsSlot.captured

        // 2 knowledge + 1 label (deduplicated) + 2 provenance = 5 quads
        val labelQuads = quads.filter { it.predicate == "http://www.w3.org/2000/01/rdf-schema#label" }
        assertEquals(1, labelQuads.size)
    }

    // Standalone copy of parsing logic (same pattern as RelationshipExtractorServiceTest)
    private fun parseJsonlDefinitions(llmResponse: String): List<DefinitionResult> {
        return llmResponse.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val map = objectMapper.readValue<Map<String, String>>(line)
                    val entity = map["entity"]?.takeIf { it.isNotBlank() }
                    val definition = map["definition"]?.takeIf { it.isNotBlank() }
                    if (entity != null && definition != null) DefinitionResult(entity, definition) else null
                } catch (_: Exception) {
                    null
                }
            }
    }
}
