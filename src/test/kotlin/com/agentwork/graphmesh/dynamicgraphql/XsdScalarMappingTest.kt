package com.agentwork.graphmesh.dynamicgraphql

import graphql.Scalars
import graphql.scalars.ExtendedScalars
import kotlin.test.Test
import kotlin.test.assertEquals

class XsdScalarMappingTest {

    @Test
    fun `string maps to GraphQLString`() {
        assertEquals(Scalars.GraphQLString, XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#string"))
    }

    @Test
    fun `integer maps to GraphQLInt`() {
        assertEquals(Scalars.GraphQLInt, XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#integer"))
    }

    @Test
    fun `int maps to GraphQLInt`() {
        assertEquals(Scalars.GraphQLInt, XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#int"))
    }

    @Test
    fun `short maps to GraphQLInt`() {
        assertEquals(Scalars.GraphQLInt, XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#short"))
    }

    @Test
    fun `byte maps to GraphQLInt`() {
        assertEquals(Scalars.GraphQLInt, XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#byte"))
    }

    @Test
    fun `long maps to GraphQLLong`() {
        assertEquals(ExtendedScalars.GraphQLLong, XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#long"))
    }

    @Test
    fun `float maps to GraphQLFloat`() {
        assertEquals(Scalars.GraphQLFloat, XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#float"))
    }

    @Test
    fun `double maps to GraphQLFloat`() {
        assertEquals(Scalars.GraphQLFloat, XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#double"))
    }

    @Test
    fun `decimal maps to GraphQLFloat`() {
        assertEquals(Scalars.GraphQLFloat, XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#decimal"))
    }

    @Test
    fun `boolean maps to GraphQLBoolean`() {
        assertEquals(Scalars.GraphQLBoolean, XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#boolean"))
    }

    @Test
    fun `anyURI maps to GraphQLID`() {
        assertEquals(Scalars.GraphQLID, XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#anyURI"))
    }

    @Test
    fun `date maps to Date extended scalar`() {
        assertEquals(ExtendedScalars.Date, XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#date"))
    }

    @Test
    fun `dateTime maps to DateTime extended scalar`() {
        assertEquals(ExtendedScalars.DateTime, XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#dateTime"))
    }

    @Test
    fun `rdfs Literal maps to GraphQLString`() {
        assertEquals(Scalars.GraphQLString, XsdScalarMapping.resolve("http://www.w3.org/2000/01/rdf-schema#Literal"))
    }

    @Test
    fun `unknown uri falls back to GraphQLString`() {
        assertEquals(Scalars.GraphQLString, XsdScalarMapping.resolve("http://example.org/unknown#type"))
    }

    @Test
    fun `empty string falls back to GraphQLString`() {
        assertEquals(Scalars.GraphQLString, XsdScalarMapping.resolve(""))
    }
}
