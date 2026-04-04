package com.agentwork.graphmesh.rdf

import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.StoredQuad
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class QuadConverterTest {

    @Test
    fun `converts URI quad to StoredQuad`() {
        val quad = Quad(
            RdfTerm.Uri("http://example.org/Alice"),
            RdfTerm.Uri("http://example.org/knows"),
            RdfTerm.Uri("http://example.org/Bob"),
            NamedGraph.DEFAULT
        )
        val stored = QuadConverter.toStoredQuad(quad)
        assertEquals("http://example.org/Alice", stored.subject)
        assertEquals("http://example.org/knows", stored.predicate)
        assertEquals("http://example.org/Bob", stored.objectValue)
        assertEquals(ObjectType.URI, stored.objectType)
        assertEquals("", stored.dataset)
    }

    @Test
    fun `converts Literal quad to StoredQuad`() {
        val quad = Quad(
            RdfTerm.Uri("http://example.org/Alice"),
            RdfTerm.Uri("http://example.org/age"),
            RdfTerm.Literal("30", datatype = XsdTypes.INTEGER),
            NamedGraph.DEFAULT
        )
        val stored = QuadConverter.toStoredQuad(quad)
        assertEquals("30", stored.objectValue)
        assertEquals(ObjectType.LITERAL, stored.objectType)
        assertEquals(XsdTypes.INTEGER, stored.datatype)
    }

    @Test
    fun `converts QuotedTriple quad to StoredQuad`() {
        val inner = Triple(
            RdfTerm.Uri("http://example.org/Alice"),
            RdfTerm.Uri("http://example.org/knows"),
            RdfTerm.Uri("http://example.org/Bob")
        )
        val quad = Quad(
            RdfTerm.QuotedTriple(inner),
            RdfTerm.Uri("http://example.org/supportedBy"),
            RdfTerm.Uri("http://example.org/doc1"),
            NamedGraph.SOURCE
        )
        val stored = QuadConverter.toStoredQuad(quad)
        assertEquals(ObjectType.URI, stored.objectType)
        assert(stored.subject.startsWith("<<"))
    }

    @Test
    fun `roundtrip URI quad`() {
        val original = Quad(
            RdfTerm.Uri("http://example.org/Alice"),
            RdfTerm.Uri("http://example.org/knows"),
            RdfTerm.Uri("http://example.org/Bob"),
            NamedGraph.SOURCE
        )
        val stored = QuadConverter.toStoredQuad(original)
        val restored = QuadConverter.fromStoredQuad(stored)
        assertEquals(original.subject, restored.subject)
        assertEquals(original.predicate, restored.predicate)
        assertEquals(original.objectTerm, restored.objectTerm)
        assertEquals(original.graph, restored.graph)
    }

    @Test
    fun `roundtrip Literal quad`() {
        val original = Quad(
            RdfTerm.Uri("http://example.org/Alice"),
            RdfTerm.Uri("http://example.org/name"),
            RdfTerm.Literal("Alice", language = "en"),
            NamedGraph.DEFAULT
        )
        val stored = QuadConverter.toStoredQuad(original)
        val restored = QuadConverter.fromStoredQuad(stored)
        assertEquals(original.objectTerm, restored.objectTerm)
    }
}
