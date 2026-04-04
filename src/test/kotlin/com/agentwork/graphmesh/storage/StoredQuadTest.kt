package com.agentwork.graphmesh.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoredQuadTest {

    @Test
    fun `StoredQuad default values`() {
        val quad = StoredQuad(
            subject = "http://example.org/alice",
            predicate = "http://xmlns.com/foaf/0.1/knows",
            objectValue = "http://example.org/bob",
            dataset = "http://example.org/graph1"
        )
        assertEquals(ObjectType.URI, quad.objectType)
        assertEquals("", quad.datatype)
        assertEquals("", quad.language)
    }

    @Test
    fun `StoredQuad with literal object`() {
        val quad = StoredQuad(
            subject = "http://example.org/alice",
            predicate = "http://xmlns.com/foaf/0.1/name",
            objectValue = "Alice",
            dataset = "http://example.org/graph1",
            objectType = ObjectType.LITERAL,
            datatype = "http://www.w3.org/2001/XMLSchema#string",
            language = "en"
        )
        assertEquals(ObjectType.LITERAL, quad.objectType)
        assertEquals("http://www.w3.org/2001/XMLSchema#string", quad.datatype)
        assertEquals("en", quad.language)
    }

    @Test
    fun `ObjectType enum codes`() {
        assertEquals("U", ObjectType.URI.code)
        assertEquals("L", ObjectType.LITERAL.code)
        assertEquals("T", ObjectType.QUOTED_TRIPLE.code)
    }

    @Test
    fun `ObjectType fromCode`() {
        assertEquals(ObjectType.URI, ObjectType.fromCode("U"))
        assertEquals(ObjectType.LITERAL, ObjectType.fromCode("L"))
        assertEquals(ObjectType.QUOTED_TRIPLE, ObjectType.fromCode("T"))
    }

    @Test
    fun `QuadQuery all wildcards`() {
        val query = QuadQuery()
        assertNull(query.subject)
        assertNull(query.predicate)
        assertNull(query.objectValue)
        assertNull(query.dataset)
    }

    @Test
    fun `QuadQuery with specific fields`() {
        val query = QuadQuery(
            subject = "http://example.org/alice",
            predicate = "http://xmlns.com/foaf/0.1/knows"
        )
        assertEquals("http://example.org/alice", query.subject)
        assertEquals("http://xmlns.com/foaf/0.1/knows", query.predicate)
        assertNull(query.objectValue)
        assertNull(query.dataset)
    }
}
