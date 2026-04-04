package com.agentwork.graphmesh.rdf

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NamespaceRegistryTest {

    private val registry = NamespaceRegistry()

    @Test
    fun `expand resolves standard prefix`() {
        assertEquals("http://www.w3.org/2000/01/rdf-schema#label", registry.expand("rdfs:label"))
    }

    @Test
    fun `expand returns input for unknown prefix`() {
        assertEquals("unknown:foo", registry.expand("unknown:foo"))
    }

    @Test
    fun `expand returns input for non-prefixed string`() {
        assertEquals("http://example.org", registry.expand("http://example.org"))
    }

    @Test
    fun `compact compresses known URI`() {
        assertEquals("xsd:integer", registry.compact("http://www.w3.org/2001/XMLSchema#integer"))
    }

    @Test
    fun `compact returns full URI for unknown namespace`() {
        assertEquals("http://unknown.org/foo", registry.compact("http://unknown.org/foo"))
    }

    @Test
    fun `register adds custom prefix`() {
        registry.register("ex", "http://example.org/")
        assertEquals("http://example.org/Alice", registry.expand("ex:Alice"))
        assertEquals("ex:Alice", registry.compact("http://example.org/Alice"))
    }

    @Test
    fun `standard prefixes are registered`() {
        val prefixes = registry.allNamespaces().map { it.prefix }.toSet()
        assertEquals(setOf("rdf", "rdfs", "xsd", "owl", "gm", "gms"), prefixes)
    }
}
