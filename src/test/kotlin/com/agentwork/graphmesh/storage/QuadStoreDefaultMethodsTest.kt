package com.agentwork.graphmesh.storage

import com.agentwork.graphmesh.provenance.ProvenanceNamespaces
import com.agentwork.graphmesh.rdf.NamedGraph
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuadStoreDefaultMethodsTest {

    private val collection = "c1"
    private val chunkUrn = "urn:chunk:doc-abc/p1/c1"
    private val otherChunkUrn = "urn:chunk:doc-abc/p1/c2"
    private val subgraphUri = "urn:graphmesh:subgraph:sg-1"
    private val unrelatedSubgraph = "urn:graphmesh:subgraph:sg-2"
    private val entityA = "http://graphmesh.io/entity/aaa"
    private val entityB = "http://graphmesh.io/entity/bbb"

    private fun seededStore(): QuadStore {
        val store = InMemoryQuadStore()
        // sg-1 derived from chunkUrn, contains two quoted triples
        store.insert(collection, StoredQuad(
            subject = subgraphUri,
            predicate = ProvenanceNamespaces.PROV_WAS_DERIVED_FROM,
            objectValue = chunkUrn,
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.URI
        ))
        store.insert(collection, StoredQuad(
            subject = subgraphUri,
            predicate = ProvenanceNamespaces.TG_CONTAINS,
            objectValue = "<<$entityA|http://example.org/label|GraphMesh>>",
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.QUOTED_TRIPLE
        ))
        store.insert(collection, StoredQuad(
            subject = subgraphUri,
            predicate = ProvenanceNamespaces.TG_CONTAINS,
            objectValue = "<<$entityB|http://example.org/relatedTo|$entityA>>",
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.QUOTED_TRIPLE
        ))
        // sg-2 derived from a different chunk — must NOT be returned for chunkUrn
        store.insert(collection, StoredQuad(
            subject = unrelatedSubgraph,
            predicate = ProvenanceNamespaces.PROV_WAS_DERIVED_FROM,
            objectValue = otherChunkUrn,
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.URI
        ))
        store.insert(collection, StoredQuad(
            subject = unrelatedSubgraph,
            predicate = ProvenanceNamespaces.TG_CONTAINS,
            objectValue = "<<http://graphmesh.io/entity/zzz|http://example.org/label|Other>>",
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.QUOTED_TRIPLE
        ))
        return store
    }

    @Test
    fun `findSubgraphsForChunks returns subgraph URIs that wasDerivedFrom matching chunks`() {
        val store = seededStore()
        val result = store.findSubgraphsForChunks(collection, listOf(chunkUrn))
        assertEquals(listOf(subgraphUri), result)
    }

    @Test
    fun `findSubgraphsForChunks returns empty for unknown chunks`() {
        val store = seededStore()
        val result = store.findSubgraphsForChunks(collection, listOf("urn:chunk:does/not/exist"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `findSubgraphsForChunks returns empty for empty input`() {
        val store = seededStore()
        assertTrue(store.findSubgraphsForChunks(collection, emptyList()).isEmpty())
    }

    @Test
    fun `findSubgraphsForChunks deduplicates across chunks`() {
        val store = seededStore()
        // Same subgraph derived from two chunks → result still contains it once
        store.insert(collection, StoredQuad(
            subject = subgraphUri,
            predicate = ProvenanceNamespaces.PROV_WAS_DERIVED_FROM,
            objectValue = otherChunkUrn,
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.URI
        ))
        val result = store.findSubgraphsForChunks(collection, listOf(chunkUrn, otherChunkUrn))
        assertEquals(setOf(subgraphUri, unrelatedSubgraph), result.toSet())
        assertEquals(2, result.size, "result must be deduplicated")
    }

    @Test
    fun `findQuotedTriplesForSubgraphs returns inner triples as StoredQuads`() {
        val store = seededStore()
        val result = store.findQuotedTriplesForSubgraphs(collection, listOf(subgraphUri))
        assertEquals(2, result.size)
        val subjects = result.map { it.subject }.toSet()
        assertEquals(setOf(entityA, entityB), subjects)
        // The unpacked rows should NOT keep the quoted-triple object type
        assertTrue(result.all { it.objectType == ObjectType.URI })
    }

    @Test
    fun `findQuotedTriplesForSubgraphs returns empty for empty input`() {
        val store = seededStore()
        assertTrue(store.findQuotedTriplesForSubgraphs(collection, emptyList()).isEmpty())
    }

    @Test
    fun `findQuotedTriplesForSubgraphs returns empty for unknown subgraph`() {
        val store = seededStore()
        assertTrue(store.findQuotedTriplesForSubgraphs(collection, listOf("urn:graphmesh:subgraph:nope")).isEmpty())
    }
}
