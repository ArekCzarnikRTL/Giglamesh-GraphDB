package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.storage.vector.VectorPayload
import com.agentwork.graphmesh.storage.vector.VectorPoint
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class BundleWriterReaderTest {

    private val mapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    @Test
    fun `write and read bundle roundtrip`() {
        val manifest = CoreManifest(
            coreId = "test-core",
            version = "1.0.0",
            sourceCollection = "my-collection",
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            createdBy = "tester",
            stats = CoreStats(quadCount = 2, entityCount = 2, chunkEmbeddingCount = 1, ontologyAxiomCount = 0),
            embeddingModel = "text-embedding-3-small",
            embeddingDimension = 1536,
            checksum = ""
        )
        val nquads = "<http://ex.org/A> <http://ex.org/p> <http://ex.org/B> .\n"
        val ontologyTtl = "@prefix ex: <http://ex.org/> .\nex:Person a owl:Class .\n"
        val embeddings = listOf(
            VectorPoint("chunk-1", floatArrayOf(0.1f, 0.2f, 0.3f), VectorPayload(collection = "", extra = mapOf("text" to "hello")))
        )
        val policies = RetrievalPolicies()

        val zipBytes = BundleWriter.write(manifest, nquads, ontologyTtl, embeddings, policies, mapper)
        assertTrue(zipBytes.size > 100)

        val reader = BundleReader(zipBytes, mapper)
        val readManifest = reader.readManifest()
        assertEquals("test-core", readManifest.coreId)
        assertEquals("1.0.0", readManifest.version)

        val readNquads = reader.readNQuads()
        assertEquals(nquads, readNquads)

        val readOntology = reader.readOntology()
        assertEquals(ontologyTtl, readOntology)

        val readEmbeddings = reader.readEmbeddings()
        assertEquals(1, readEmbeddings.size)
        assertEquals("chunk-1", readEmbeddings[0].id)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), readEmbeddings[0].vector)

        assertTrue(reader.verifyChecksums())
    }

    @Test
    fun `checksum mismatch detected`() {
        val manifest = CoreManifest(
            coreId = "bad", version = "0.1.0", sourceCollection = "x",
            createdAt = Instant.now(), createdBy = "test",
            stats = CoreStats(0, 0, 0, 0),
            embeddingModel = "m", embeddingDimension = 3, checksum = ""
        )
        val zipBytes = BundleWriter.write(manifest, "", "", emptyList(), RetrievalPolicies(), mapper)

        val tampered = zipBytes.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2] + 1).toByte()

        try {
            val reader = BundleReader(tampered, mapper)
            assertFalse(reader.verifyChecksums())
        } catch (_: Exception) {
            // zip is unreadable after tampering — also acceptable
        }
    }
}
