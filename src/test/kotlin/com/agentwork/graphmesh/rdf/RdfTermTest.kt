package com.agentwork.graphmesh.rdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class RdfTermTest {

    @Test
    fun `Uri toNTriples wraps in angle brackets`() {
        val uri = RdfTerm.Uri("http://example.org/Alice")
        assertEquals("<http://example.org/Alice>", uri.toNTriples())
    }

    @Test
    fun `Literal default is plain string`() {
        val lit = RdfTerm.Literal("hello")
        assertEquals("\"hello\"", lit.toNTriples())
    }

    @Test
    fun `Literal with datatype uses caret notation`() {
        val lit = RdfTerm.Literal("42", datatype = XsdTypes.INTEGER)
        assertEquals("\"42\"^^<${XsdTypes.INTEGER}>", lit.toNTriples())
    }

    @Test
    fun `Literal with language tag uses at notation`() {
        val lit = RdfTerm.Literal("Hallo", language = "de")
        assertEquals("\"Hallo\"@de", lit.toNTriples())
    }

    @Test
    fun `Literal rejects language tag with non-string datatype`() {
        assertThrows<IllegalArgumentException> {
            RdfTerm.Literal("42", datatype = XsdTypes.INTEGER, language = "en")
        }
    }

    @Test
    fun `Literal allows language tag with langString datatype`() {
        val lit = RdfTerm.Literal("hello", datatype = RdfTypes.LANG_STRING, language = "en")
        assertEquals("\"hello\"@en", lit.toNTriples())
    }

    @Test
    fun `BlankNode toNTriples uses underscore prefix`() {
        val bn = RdfTerm.BlankNode("b1")
        assertEquals("_:b1", bn.toNTriples())
    }

    @Test
    fun `QuotedTriple toNTriples uses RDF-Star syntax`() {
        val qt = RdfTerm.QuotedTriple(
            Triple(
                RdfTerm.Uri("http://example.org/Alice"),
                RdfTerm.Uri("http://example.org/knows"),
                RdfTerm.Uri("http://example.org/Bob")
            )
        )
        assertEquals(
            "<< <http://example.org/Alice> <http://example.org/knows> <http://example.org/Bob> >>",
            qt.toNTriples()
        )
    }
}
