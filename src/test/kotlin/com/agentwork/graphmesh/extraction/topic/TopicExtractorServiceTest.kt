package com.agentwork.graphmesh.extraction.topic

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.provenance.ProvenanceService
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.rdf.SkosTypes
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopicExtractorServiceTest {

    private val promptExecutor = mockk<PromptExecutor>()
    private val quadStore = mockk<QuadStore>(relaxed = true)
    private val librarianService = mockk<LibrarianService>()
    private val provenanceService = mockk<ProvenanceService>(relaxed = true)
    private val ontologyMatcher = mockk<TopicOntologyMatcher>()

    private fun buildService(minConfidence: Double = 0.5): TopicExtractorService =
        TopicExtractorService(
            promptExecutor = promptExecutor,
            quadStore = quadStore,
            librarianService = librarianService,
            provenanceService = provenanceService,
            ontologyMatcher = ontologyMatcher,
            modelName = "gpt-4o",
            minConfidence = minConfidence
        )

    @Test
    fun `extract generates type label subject and confidence quads`() {
        every { provenanceService.buildSubgraphQuads(any()) } returns emptyList()
        every { ontologyMatcher.getHints("col-1") } returns emptyList()
        every { ontologyMatcher.resolveOrCreate(any(), eq("col-1")) } answers {
            EntityIdGenerator.generate(firstArg<String>().trim().lowercase().replace(Regex("\\s+"), " "))
        }
        every { librarianService.getContent("chunk-1") } returns
            "Text about Insolvenzrecht and related topics.".toByteArray()

        val llmResponse = """{"topic": "Insolvenzrecht", "confidence": 0.95, "rationale": "Hauptthema"}"""
        val message = mockk<Message.Response>()
        every { message.content } returns llmResponse
        coEvery { promptExecutor.execute(any(), any()) } returns listOf(message)

        val service = buildService()
        val result = service.extract("chunk-1", "col-1")

        assertEquals(1, result.topicsExtracted)
        assertEquals(listOf("Insolvenzrecht"), result.topics)

        val quadsSlot = slot<List<StoredQuad>>()
        io.mockk.verify { quadStore.insertBatch("col-1", capture(quadsSlot)) }
        val quads = quadsSlot.captured

        // rdf:type quad
        val typeQuad = quads.first { it.predicate == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" }
        assertEquals(SkosTypes.CONCEPT, typeQuad.objectValue)
        assertEquals(ObjectType.URI, typeQuad.objectType)

        // rdfs:label quad
        val labelQuad = quads.first { it.predicate == "http://www.w3.org/2000/01/rdf-schema#label" }
        assertEquals("Insolvenzrecht", labelQuad.objectValue)
        assertEquals(ObjectType.LITERAL, labelQuad.objectType)

        // dct:subject quad
        val subjectQuad = quads.first { it.predicate == "http://purl.org/dc/terms/subject" }
        assertEquals("urn:chunk:chunk-1", subjectQuad.subject)

        // confidence quoted triple
        val confidenceQuad = quads.first { it.predicate == "http://graphmesh.io/ontology/topicConfidence" }
        assertEquals("0.95", confidenceQuad.objectValue)
        assertEquals(ObjectType.LITERAL, confidenceQuad.objectType)
    }

    @Test
    fun `extract returns zero result for blank chunk text`() {
        every { librarianService.getContent("chunk-1") } returns "   ".toByteArray()

        val service = buildService()
        val result = service.extract("chunk-1", "col-1")

        assertEquals(0, result.topicsExtracted)
        assertTrue(result.topics.isEmpty())
    }

    @Test
    fun `extract filters topics below minConfidence`() {
        every { provenanceService.buildSubgraphQuads(any()) } returns emptyList()
        every { ontologyMatcher.getHints("col-1") } returns emptyList()
        every { ontologyMatcher.resolveOrCreate(any(), eq("col-1")) } answers {
            EntityIdGenerator.generate(firstArg<String>().trim().lowercase().replace(Regex("\\s+"), " "))
        }
        every { librarianService.getContent("chunk-1") } returns "Some text".toByteArray()

        val llmResponse = """
            {"topic": "HighConf", "confidence": 0.9, "rationale": "r"}
            {"topic": "LowConf", "confidence": 0.3, "rationale": "r"}
        """.trimIndent()
        val message = mockk<Message.Response>()
        every { message.content } returns llmResponse
        coEvery { promptExecutor.execute(any(), any()) } returns listOf(message)

        val service = buildService(minConfidence = 0.5)
        val result = service.extract("chunk-1", "col-1")

        assertEquals(1, result.topicsExtracted)
        assertEquals(listOf("HighConf"), result.topics)
    }

    @Test
    fun `extract deduplicates topics by normalized label`() {
        every { provenanceService.buildSubgraphQuads(any()) } returns emptyList()
        every { ontologyMatcher.getHints("col-1") } returns emptyList()
        every { ontologyMatcher.resolveOrCreate(any(), eq("col-1")) } answers {
            EntityIdGenerator.generate(firstArg<String>().trim().lowercase().replace(Regex("\\s+"), " "))
        }
        every { librarianService.getContent("chunk-1") } returns "Some text".toByteArray()

        val llmResponse = """
            {"topic": "Insolvenzrecht", "confidence": 0.9, "rationale": "r"}
            {"topic": "insolvenzrecht", "confidence": 0.7, "rationale": "r"}
            {"topic": "Photosynthese", "confidence": 0.8, "rationale": "r"}
        """.trimIndent()
        val message = mockk<Message.Response>()
        every { message.content } returns llmResponse
        coEvery { promptExecutor.execute(any(), any()) } returns listOf(message)

        val service = buildService()
        val result = service.extract("chunk-1", "col-1")

        assertEquals(2, result.topicsExtracted)
    }
}
