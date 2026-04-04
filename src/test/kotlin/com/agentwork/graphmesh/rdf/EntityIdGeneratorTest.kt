package com.agentwork.graphmesh.rdf

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EntityIdGeneratorTest {

    @Test
    fun `same label produces same ID`() {
        val id1 = EntityIdGenerator.generate("Alice")
        val id2 = EntityIdGenerator.generate("Alice")
        assertEquals(id1, id2)
    }

    @Test
    fun `different labels produce different IDs`() {
        val id1 = EntityIdGenerator.generate("Alice")
        val id2 = EntityIdGenerator.generate("Bob")
        assertNotEquals(id1, id2)
    }

    @Test
    fun `normalizes whitespace and case`() {
        val id1 = EntityIdGenerator.generate("Alice")
        val id2 = EntityIdGenerator.generate("  ALICE  ")
        val id3 = EntityIdGenerator.generate("alice")
        assertEquals(id1, id2)
        assertEquals(id1, id3)
    }

    @Test
    fun `multi-field generation is deterministic`() {
        val id1 = EntityIdGenerator.generate("Alice", "knows", "Bob")
        val id2 = EntityIdGenerator.generate("Alice", "knows", "Bob")
        assertEquals(id1, id2)
    }

    @Test
    fun `generated URI starts with namespace`() {
        val id = EntityIdGenerator.generate("Alice")
        assert(id.value.startsWith("http://graphmesh.io/entity/"))
    }
}
