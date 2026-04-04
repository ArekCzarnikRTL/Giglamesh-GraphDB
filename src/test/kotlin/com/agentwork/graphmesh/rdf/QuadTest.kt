package com.agentwork.graphmesh.rdf

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuadTest {

    private val alice = RdfTerm.Uri("http://example.org/Alice")
    private val knows = RdfTerm.Uri("http://example.org/knows")
    private val bob = RdfTerm.Uri("http://example.org/Bob")

    @Test
    fun `Triple toNTriples ends with dot`() {
        val triple = Triple(alice, knows, bob)
        assertEquals("<http://example.org/Alice> <http://example.org/knows> <http://example.org/Bob> .", triple.toNTriples())
    }

    @Test
    fun `Quad in default graph serializes as triple`() {
        val quad = Quad(alice, knows, bob)
        assertEquals("<http://example.org/Alice> <http://example.org/knows> <http://example.org/Bob> .", quad.toNQuads())
    }

    @Test
    fun `Quad in named graph includes graph URI`() {
        val quad = Quad(alice, knows, bob, graph = NamedGraph.SOURCE)
        assertEquals(
            "<http://example.org/Alice> <http://example.org/knows> <http://example.org/Bob> <urn:graph:source> .",
            quad.toNQuads()
        )
    }

    @Test
    fun `Quad triple property returns Triple`() {
        val quad = Quad(alice, knows, bob, graph = NamedGraph.SOURCE)
        val triple = quad.triple
        assertEquals(alice, triple.subject)
        assertEquals(knows, triple.predicate)
        assertEquals(bob, triple.objectTerm)
    }

    @Test
    fun `NamedGraph isStandardGraph returns true for known graphs`() {
        assertTrue(NamedGraph.isStandardGraph(NamedGraph.DEFAULT))
        assertTrue(NamedGraph.isStandardGraph(NamedGraph.SOURCE))
        assertTrue(NamedGraph.isStandardGraph(NamedGraph.RETRIEVAL))
    }

    @Test
    fun `NamedGraph isStandardGraph returns false for custom graphs`() {
        assertFalse(NamedGraph.isStandardGraph("urn:graph:custom"))
    }
}
