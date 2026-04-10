package com.agentwork.graphmesh.rdfimport

import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import com.agentwork.graphmesh.extraction.embedding.EmbeddingConfig
import com.agentwork.graphmesh.ontology.JenaAdapter
import com.agentwork.graphmesh.storage.InMemoryQuadStore
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.StoredQuad
import com.agentwork.graphmesh.storage.vector.VectorPoint
import com.agentwork.graphmesh.storage.vector.VectorStore
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RdfImportServiceTest {

    private val quadStore = InMemoryQuadStore()
    private val jenaAdapter = JenaAdapter()
    private val embeddingProvider = mockk<LLMEmbeddingProvider>()
    private val vectorStore = mockk<VectorStore>(relaxed = true)
    private val embeddingConfig = EmbeddingConfig(model = "text-embedding-3-small")
    private val service = RdfImportService(jenaAdapter, quadStore, embeddingProvider, vectorStore, embeddingConfig)

    private val collectionId = "test-collection"

    @Test
    fun `imports Turtle data triples into QuadStore`() {
        val turtle = """
            @prefix ex: <http://example.org/> .
            ex:Alice ex:knows ex:Bob .
            ex:Alice ex:name "Alice" .
        """.trimIndent()

        val result = service.importRdf(collectionId, turtle, RdfFormat.TURTLE, null)

        assertEquals(2, result.tripleCount)
        assertEquals(0, result.skippedCount)

        val quads = quadStore.query(collectionId, QuadQuery())
        assertEquals(2, quads.size)

        val uriQuad = quads.first { it.objectType == ObjectType.URI }
        assertEquals("http://example.org/Alice", uriQuad.subject)
        assertEquals("http://example.org/knows", uriQuad.predicate)
        assertEquals("http://example.org/Bob", uriQuad.objectValue)

        val literalQuad = quads.first { it.objectType == ObjectType.LITERAL }
        assertEquals("Alice", literalQuad.objectValue)
    }

    @Test
    fun `imports RDF-XML data triples`() {
        val rdfXml = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:ex="http://example.org/">
                <rdf:Description rdf:about="http://example.org/Alice">
                    <ex:knows rdf:resource="http://example.org/Bob"/>
                </rdf:Description>
            </rdf:RDF>
        """.trimIndent()

        val result = service.importRdf(collectionId, rdfXml, RdfFormat.RDFXML, null)

        assertEquals(1, result.tripleCount)
        assertEquals(0, result.skippedCount)
    }

    @Test
    fun `imports N-Triples data`() {
        val ntriples = """
            <http://example.org/Alice> <http://example.org/knows> <http://example.org/Bob> .
            <http://example.org/Alice> <http://example.org/name> "Alice" .
        """.trimIndent()

        val result = service.importRdf(collectionId, ntriples, RdfFormat.NTRIPLES, null)

        assertEquals(2, result.tripleCount)
        assertEquals(0, result.skippedCount)
    }

    @Test
    fun `skips blank nodes and counts them`() {
        val turtle = """
            @prefix ex: <http://example.org/> .
            _:b0 ex:knows ex:Bob .
            ex:Alice ex:knows ex:Bob .
        """.trimIndent()

        val result = service.importRdf(collectionId, turtle, RdfFormat.TURTLE, null)

        assertEquals(1, result.tripleCount)
        assertEquals(1, result.skippedCount)
    }

    @Test
    fun `stores dataset when provided`() {
        val turtle = """
            @prefix ex: <http://example.org/> .
            ex:Alice ex:knows ex:Bob .
        """.trimIndent()

        service.importRdf(collectionId, turtle, RdfFormat.TURTLE, "wikidata")

        val quads = quadStore.query(collectionId, QuadQuery())
        assertEquals("wikidata", quads.single().dataset)
    }

    @Test
    fun `uses empty dataset when none provided`() {
        val turtle = """
            @prefix ex: <http://example.org/> .
            ex:Alice ex:knows ex:Bob .
        """.trimIndent()

        service.importRdf(collectionId, turtle, RdfFormat.TURTLE, null)

        val quads = quadStore.query(collectionId, QuadQuery())
        assertEquals("", quads.single().dataset)
    }

    @Test
    fun `preserves literal datatype and language`() {
        val turtle = """
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:Alice ex:age "30"^^xsd:integer .
            ex:Alice ex:name "Alice"@en .
        """.trimIndent()

        service.importRdf(collectionId, turtle, RdfFormat.TURTLE, null)

        val quads = quadStore.query(collectionId, QuadQuery())
        assertEquals(2, quads.size)

        val intQuad = quads.first { it.objectValue == "30" }
        assertEquals("http://www.w3.org/2001/XMLSchema#integer", intQuad.datatype)

        val langQuad = quads.first { it.objectValue == "Alice" }
        assertEquals("en", langQuad.language)
    }

    @Test
    fun `returns positive durationMs`() {
        val turtle = """
            @prefix ex: <http://example.org/> .
            ex:Alice ex:knows ex:Bob .
        """.trimIndent()

        val result = service.importRdf(collectionId, turtle, RdfFormat.TURTLE, null)

        assert(result.durationMs >= 0)
    }

    @Test
    fun `buildEntityText creates readable text from triples`() {
        val triples = listOf(
            StoredQuad(
                subject = "http://example.org/Alice",
                predicate = "http://example.org/knows",
                objectValue = "http://example.org/Bob",
                dataset = "", objectType = ObjectType.URI
            ),
            StoredQuad(
                subject = "http://example.org/Alice",
                predicate = "http://example.org/name",
                objectValue = "Alice Mueller",
                dataset = "", objectType = ObjectType.LITERAL
            ),
        )

        val text = RdfImportService.buildEntityText("http://example.org/Alice", triples)

        assertTrue(text.contains("Alice"))
        assertTrue(text.contains("knows"))
        assertTrue(text.contains("Bob"))
        assertTrue(text.contains("Alice Mueller"))
    }

    @Test
    fun `generateEmbeddings creates entity embeddings in vector store`() {
        val turtle = """
            @prefix ex: <http://example.org/> .
            ex:Alice ex:knows ex:Bob .
            ex:Alice ex:name "Alice" .
        """.trimIndent()

        coEvery { embeddingProvider.embed(any(), any()) } returns listOf(0.1, 0.2, 0.3)

        val result = service.importRdf(collectionId, turtle, RdfFormat.TURTLE, null, generateEmbeddings = true)

        assertEquals(2, result.tripleCount)
        assertTrue(result.embeddingsGenerated > 0)

        val pointsSlot = slot<List<VectorPoint>>()
        verify { vectorStore.upsert(collectionId, capture(pointsSlot)) }
        val points = pointsSlot.captured
        assertTrue(points.any { it.payload["entity_uri"] == "http://example.org/Alice" })
        assertTrue(points.any { it.payload["source"] == "rdf-import" })
    }

    @Test
    fun `importRdf without generateEmbeddings does not call vectorStore`() {
        val turtle = """
            @prefix ex: <http://example.org/> .
            ex:Alice ex:knows ex:Bob .
        """.trimIndent()

        val result = service.importRdf(collectionId, turtle, RdfFormat.TURTLE, null)

        assertEquals(0, result.embeddingsGenerated)
        verify(exactly = 0) { vectorStore.upsert(any(), any()) }
    }
}
