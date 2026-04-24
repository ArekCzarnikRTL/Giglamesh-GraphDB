package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.rdf.XsdTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuadDataFetcherTest {

    @Test
    fun `convertLiteral returns string for xsd string`() {
        assertEquals("hello", QuadDataFetcher.convertLiteral("hello", XsdTypes.STRING))
    }

    @Test
    fun `convertLiteral returns Int for xsd integer`() {
        assertEquals(42, QuadDataFetcher.convertLiteral("42", XsdTypes.INTEGER))
    }

    @Test
    fun `convertLiteral returns Long for xsd long`() {
        assertEquals(9999999999L, QuadDataFetcher.convertLiteral("9999999999", XsdTypes.LONG))
    }

    @Test
    fun `convertLiteral returns true for xsd boolean true`() {
        assertEquals(true, QuadDataFetcher.convertLiteral("true", XsdTypes.BOOLEAN))
    }

    @Test
    fun `convertLiteral returns false for xsd boolean false`() {
        assertEquals(false, QuadDataFetcher.convertLiteral("false", XsdTypes.BOOLEAN))
    }

    @Test
    fun `convertLiteral returns Double for xsd double`() {
        assertEquals(3.14, QuadDataFetcher.convertLiteral("3.14", XsdTypes.DOUBLE))
    }

    @Test
    fun `convertLiteral returns Double for xsd float`() {
        val result = QuadDataFetcher.convertLiteral("2.5", XsdTypes.FLOAT) as Double
        assertEquals(2.5, result, 0.0001)
    }

    @Test
    fun `convertLiteral returns string for unknown datatype`() {
        assertEquals("abc", QuadDataFetcher.convertLiteral("abc", "http://example.org/custom"))
    }

    @Test
    fun `convertLiteral returns null for invalid integer`() {
        assertNull(QuadDataFetcher.convertLiteral("notanumber", XsdTypes.INTEGER))
    }
}
