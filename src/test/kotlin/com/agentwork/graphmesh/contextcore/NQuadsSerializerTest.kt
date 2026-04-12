package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.StoredQuad
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NQuadsSerializerTest {

    private val serializer = NQuadsSerializer()

    @Test
    fun `serialize and deserialize URI quad roundtrip`() {
        val quad = StoredQuad(
            subject = "http://example.org/Alice",
            predicate = "http://example.org/knows",
            objectValue = "http://example.org/Bob",
            dataset = "default",
            objectType = ObjectType.URI
        )
        val nquads = serializer.serialize(listOf(quad))
        val result = serializer.deserialize(nquads)
        assertEquals(1, result.size)
        assertEquals(quad.subject, result[0].subject)
        assertEquals(quad.predicate, result[0].predicate)
        assertEquals(quad.objectValue, result[0].objectValue)
        assertEquals(ObjectType.URI, result[0].objectType)
    }

    @Test
    fun `serialize and deserialize literal quad roundtrip`() {
        val quad = StoredQuad(
            subject = "http://example.org/Alice",
            predicate = "http://example.org/age",
            objectValue = "30",
            dataset = "default",
            objectType = ObjectType.LITERAL,
            datatype = "http://www.w3.org/2001/XMLSchema#integer"
        )
        val nquads = serializer.serialize(listOf(quad))
        val result = serializer.deserialize(nquads)
        assertEquals(1, result.size)
        assertEquals("30", result[0].objectValue)
        assertEquals(ObjectType.LITERAL, result[0].objectType)
        assertEquals("http://www.w3.org/2001/XMLSchema#integer", result[0].datatype)
    }

    @Test
    fun `serialize and deserialize language-tagged literal`() {
        val quad = StoredQuad(
            subject = "http://example.org/Alice",
            predicate = "http://example.org/name",
            objectValue = "Alice",
            dataset = "default",
            objectType = ObjectType.LITERAL,
            language = "en"
        )
        val nquads = serializer.serialize(listOf(quad))
        val result = serializer.deserialize(nquads)
        assertEquals(1, result.size)
        assertEquals("Alice", result[0].objectValue)
        assertEquals("en", result[0].language)
    }

    @Test
    fun `serialize empty list returns empty string`() {
        assertEquals("", serializer.serialize(emptyList()))
    }

    @Test
    fun `deserialize empty string returns empty list`() {
        assertEquals(emptyList<StoredQuad>(), serializer.deserialize(""))
    }
}
