package com.agentwork.graphmesh.structured

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class CassandraRowStoreTest {

    @Test
    fun `extractIndexValue returns single value for single field`() {
        val values = mapOf("id" to "123", "name" to "Alice")
        val result = extractIndexValue("id", values)
        assertEquals(listOf("123"), result)
    }

    @Test
    fun `extractIndexValue returns multiple values for composite index`() {
        val values = mapOf("tenant" to "acme", "id" to "123", "status" to "active")
        val result = extractIndexValue("tenant,id", values)
        assertEquals(listOf("acme", "123"), result)
    }

    @Test
    fun `extractIndexValue returns empty string for missing field`() {
        val values = mapOf("id" to "123")
        val result = extractIndexValue("id,missing", values)
        assertEquals(listOf("123", ""), result)
    }

    @Test
    fun `extractIndexValue handles all fields missing`() {
        val values = emptyMap<String, String>()
        val result = extractIndexValue("a,b,c", values)
        assertEquals(listOf("", "", ""), result)
    }

    @Test
    fun `extractIndexValue preserves field order from index name`() {
        val values = mapOf("z" to "last", "a" to "first", "m" to "middle")
        val result = extractIndexValue("m,a,z", values)
        assertEquals(listOf("middle", "first", "last"), result)
    }
}
