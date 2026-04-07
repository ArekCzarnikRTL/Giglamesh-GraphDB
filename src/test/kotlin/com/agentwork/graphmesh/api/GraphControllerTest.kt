package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.storage.InMemoryQuadStore
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.RDF_TYPE_URI
import com.agentwork.graphmesh.storage.StoredQuad
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphControllerTest {

    private fun seeded(): Pair<GraphController, InMemoryQuadStore> {
        val store = InMemoryQuadStore()
        for (i in 1..6) {
            store.insert("c1", StoredQuad(
                subject = "http://ex.org/e$i",
                predicate = "http://ex.org/p",
                objectValue = "v$i",
                dataset = NamedGraph.SOURCE,
                objectType = ObjectType.LITERAL
            ))
        }
        store.insert("c1", StoredQuad(
            subject = "http://ex.org/e1",
            predicate = RDF_TYPE_URI,
            objectValue = "http://ex.org/Person",
            dataset = NamedGraph.SOURCE,
            objectType = ObjectType.URI
        ))
        return GraphController(store) to store
    }

    @Test fun `triples respects explicit limit`() {
        val (ctl, _) = seeded()
        val result = ctl.triples("c1", null, null, null, null, limit = 3)
        assertEquals(3, result.size)
    }

    @Test fun `triples caps limit at 5000`() {
        var capturedLimit: Int? = null
        val store = object : QuadStore by InMemoryQuadStore() {
            override fun query(collection: String, query: QuadQuery, limit: Int?): List<StoredQuad> {
                capturedLimit = limit
                return emptyList()
            }
        }
        val ctl = GraphController(store)
        ctl.triples("c1", null, null, null, null, limit = 100000)
        assertEquals(5000, capturedLimit)
    }

    @Test fun `triples uses default limit when null`() {
        var capturedLimit: Int? = null
        val store = object : QuadStore by InMemoryQuadStore() {
            override fun query(collection: String, query: QuadQuery, limit: Int?): List<StoredQuad> {
                capturedLimit = limit
                return emptyList()
            }
        }
        val ctl = GraphController(store)
        ctl.triples("c1", null, null, null, null, limit = null)
        assertEquals(500, capturedLimit)
    }

    @Test fun `entitySearch returns matching subjects`() {
        val (ctl, _) = seeded()
        val matches = ctl.entitySearch("c1", prefix = "e1", limit = 10)
        assertTrue("http://ex.org/e1" in matches)
    }

    @Test fun `graphMetadata returns aggregated lists`() {
        val (ctl, _) = seeded()
        val meta = ctl.graphMetadata("c1")
        assertTrue(NamedGraph.SOURCE in meta.datasets)
        assertTrue("http://ex.org/p" in meta.predicates)
        assertTrue(RDF_TYPE_URI in meta.predicates)
        assertEquals(listOf("http://ex.org/Person"), meta.entityTypes)
    }
}
