package com.agentwork.graphmesh.provenance

import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.RdfTerm
import com.agentwork.graphmesh.rdf.Triple
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProvenanceServiceTest {

    private val service = ProvenanceService()

    @Test
    fun `buildSubgraphQuads generates tg-contains for each triple`() {
        val triples = listOf(
            Triple(RdfTerm.Uri("urn:a"), RdfTerm.Uri("urn:p1"), RdfTerm.Uri("urn:b")),
            Triple(RdfTerm.Uri("urn:c"), RdfTerm.Uri("urn:p2"), RdfTerm.Literal("val"))
        )
        val provenance = SubgraphProvenance(
            extractedTriples = triples,
            chunkUri = "urn:chunk:chunk-1",
            agentLabel = "RelationshipExtractor",
            modelName = "gpt-4o"
        )

        val quads = service.buildSubgraphQuads(provenance)

        val containsQuads = quads.filter { it.predicate.value == ProvenanceNamespaces.TG_CONTAINS }
        assertEquals(2, containsQuads.size)
        assertTrue(containsQuads.all { it.objectTerm is RdfTerm.QuotedTriple })
        assertTrue(containsQuads.all { it.graph == NamedGraph.SOURCE })
    }

    @Test
    fun `buildSubgraphQuads generates PROV-O activity metadata`() {
        val provenance = SubgraphProvenance(
            extractedTriples = listOf(
                Triple(RdfTerm.Uri("urn:a"), RdfTerm.Uri("urn:p"), RdfTerm.Uri("urn:b"))
            ),
            chunkUri = "urn:chunk:chunk-1",
            agentLabel = "DefinitionExtractor",
            modelName = "gpt-4o"
        )

        val quads = service.buildSubgraphQuads(provenance)

        // Activity type
        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.RDF_TYPE &&
            (it.objectTerm as? RdfTerm.Uri)?.value == ProvenanceNamespaces.PROV_ACTIVITY
        })

        // Activity used chunk
        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.PROV_USED &&
            (it.objectTerm as? RdfTerm.Uri)?.value == "urn:chunk:chunk-1"
        })

        // Activity label
        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.RDFS_LABEL &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "DefinitionExtractor extraction"
        })
    }

    @Test
    fun `buildSubgraphQuads generates PROV-O agent metadata`() {
        val provenance = SubgraphProvenance(
            extractedTriples = listOf(
                Triple(RdfTerm.Uri("urn:a"), RdfTerm.Uri("urn:p"), RdfTerm.Uri("urn:b"))
            ),
            chunkUri = "urn:chunk:chunk-1",
            agentLabel = "RelationshipExtractor"
        )

        val quads = service.buildSubgraphQuads(provenance)

        // Agent type
        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.RDF_TYPE &&
            (it.objectTerm as? RdfTerm.Uri)?.value == ProvenanceNamespaces.PROV_AGENT
        })

        // Agent label
        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.RDFS_LABEL &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "RelationshipExtractor"
        })
    }

    @Test
    fun `buildSubgraphQuads includes llmModel when provided`() {
        val provenance = SubgraphProvenance(
            extractedTriples = listOf(
                Triple(RdfTerm.Uri("urn:a"), RdfTerm.Uri("urn:p"), RdfTerm.Uri("urn:b"))
            ),
            chunkUri = "urn:chunk:chunk-1",
            agentLabel = "Test",
            modelName = "gpt-4o"
        )

        val quads = service.buildSubgraphQuads(provenance)

        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.TG_LLM_MODEL &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "gpt-4o"
        })
    }

    @Test
    fun `buildSubgraphQuads omits llmModel when null`() {
        val provenance = SubgraphProvenance(
            extractedTriples = listOf(
                Triple(RdfTerm.Uri("urn:a"), RdfTerm.Uri("urn:p"), RdfTerm.Uri("urn:b"))
            ),
            chunkUri = "urn:chunk:chunk-1",
            agentLabel = "Test",
            modelName = null
        )

        val quads = service.buildSubgraphQuads(provenance)

        assertTrue(quads.none { it.predicate.value == ProvenanceNamespaces.TG_LLM_MODEL })
    }

    @Test
    fun `buildSubgraphQuads volume is N plus metadata`() {
        val n = 20
        val triples = (1..n).map {
            Triple(RdfTerm.Uri("urn:s$it"), RdfTerm.Uri("urn:p"), RdfTerm.Uri("urn:o$it"))
        }
        val provenance = SubgraphProvenance(
            extractedTriples = triples,
            chunkUri = "urn:chunk:chunk-1",
            agentLabel = "Test",
            modelName = "gpt-4o"
        )

        val quads = service.buildSubgraphQuads(provenance)

        // N tg:contains + 2 subgraph + 5 activity + 2 agent = N + 9
        assertEquals(n + 9, quads.size)
    }

    @Test
    fun `buildSubgraphQuads all quads in SOURCE graph`() {
        val provenance = SubgraphProvenance(
            extractedTriples = listOf(
                Triple(RdfTerm.Uri("urn:a"), RdfTerm.Uri("urn:p"), RdfTerm.Uri("urn:b"))
            ),
            chunkUri = "urn:chunk:chunk-1",
            agentLabel = "Test"
        )

        val quads = service.buildSubgraphQuads(provenance)

        assertTrue(quads.all { it.graph == NamedGraph.SOURCE })
    }

    @Test
    fun `buildSubgraphQuads with empty triples generates only metadata`() {
        val provenance = SubgraphProvenance(
            extractedTriples = emptyList(),
            chunkUri = "urn:chunk:chunk-1",
            agentLabel = "Test"
        )

        val quads = service.buildSubgraphQuads(provenance)

        // 0 tg:contains + 2 subgraph + 4 activity (no llmModel) + 2 agent = 8
        assertEquals(8, quads.size)
    }
}
