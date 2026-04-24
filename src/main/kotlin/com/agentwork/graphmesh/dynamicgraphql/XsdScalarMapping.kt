package com.agentwork.graphmesh.dynamicgraphql

import graphql.Scalars
import graphql.scalars.ExtendedScalars
import graphql.schema.GraphQLScalarType

object XsdScalarMapping {

    private val mapping: Map<String, GraphQLScalarType> = mapOf(
        "http://www.w3.org/2001/XMLSchema#string"   to Scalars.GraphQLString,
        "http://www.w3.org/2001/XMLSchema#integer"  to Scalars.GraphQLInt,
        "http://www.w3.org/2001/XMLSchema#int"      to Scalars.GraphQLInt,
        "http://www.w3.org/2001/XMLSchema#short"    to Scalars.GraphQLInt,
        "http://www.w3.org/2001/XMLSchema#byte"     to Scalars.GraphQLInt,
        "http://www.w3.org/2001/XMLSchema#long"     to ExtendedScalars.GraphQLLong,
        "http://www.w3.org/2001/XMLSchema#float"    to Scalars.GraphQLFloat,
        "http://www.w3.org/2001/XMLSchema#double"   to Scalars.GraphQLFloat,
        "http://www.w3.org/2001/XMLSchema#decimal"  to Scalars.GraphQLFloat,
        "http://www.w3.org/2001/XMLSchema#boolean"  to Scalars.GraphQLBoolean,
        "http://www.w3.org/2001/XMLSchema#anyURI"   to Scalars.GraphQLID,
        "http://www.w3.org/2001/XMLSchema#date"     to ExtendedScalars.Date,
        "http://www.w3.org/2001/XMLSchema#dateTime" to ExtendedScalars.DateTime,
        "http://www.w3.org/2000/01/rdf-schema#Literal" to Scalars.GraphQLString,
    )

    fun resolve(xsdUri: String): GraphQLScalarType = mapping[xsdUri] ?: Scalars.GraphQLString
}
