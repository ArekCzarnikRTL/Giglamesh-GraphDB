package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.rdf.XsdTypes
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.RDF_TYPE_URI
import graphql.schema.DataFetcher

object QuadDataFetcher {

    fun convertLiteral(value: String, datatype: String): Any? = when (datatype) {
        XsdTypes.STRING, "" -> value
        XsdTypes.INTEGER -> value.toIntOrNull()
        XsdTypes.LONG -> value.toLongOrNull()
        XsdTypes.FLOAT, XsdTypes.DOUBLE -> value.toDoubleOrNull()
        XsdTypes.BOOLEAN -> value.toBooleanStrictOrNull()
        XsdTypes.DATE, XsdTypes.DATE_TIME, XsdTypes.ANY_URI -> value
        else -> value
    }

    fun topLevelListFetcher(
        quadStore: QuadStore,
        collectionId: String,
        classUri: String,
        datatypeProperties: Map<String, String>,  // fieldName -> propertyUri
    ): DataFetcher<List<Map<String, Any>>> = DataFetcher { env ->
        val limit = env.getArgumentOrDefault("limit", 20)
        val offset = env.getArgumentOrDefault("offset", 0)
        val filter: Map<String, Any>? = env.getArgument("filter")

        val typeQuads = quadStore.query(
            collectionId,
            QuadQuery(predicate = RDF_TYPE_URI, objectValue = classUri)
        )
        var subjectUris = typeQuads.map { it.subject }.distinct()

        if (filter != null && filter.isNotEmpty()) {
            subjectUris = applyFilter(quadStore, collectionId, subjectUris, filter, datatypeProperties)
        }

        subjectUris
            .drop(offset)
            .take(limit)
            .map { uri -> mapOf<String, Any>("id" to uri, "_collectionId" to collectionId) }
    }

    fun topLevelByIdFetcher(
        quadStore: QuadStore,
        collectionId: String,
        classUri: String,
    ): DataFetcher<Map<String, Any>?> = DataFetcher { env ->
        val id: String = env.getArgument<String>("id")!!
        val typeQuads = quadStore.query(
            collectionId,
            QuadQuery(subject = id, predicate = RDF_TYPE_URI, objectValue = classUri)
        )
        if (typeQuads.isNotEmpty()) {
            mapOf<String, Any>("id" to id, "_collectionId" to collectionId)
        } else {
            null
        }
    }

    fun datatypePropertyFetcher(
        quadStore: QuadStore,
        propertyUri: String,
        datatype: String,
        functional: Boolean,
    ): DataFetcher<Any?> = DataFetcher { env ->
        @Suppress("UNCHECKED_CAST")
        val parent = env.getSource<Any>() as Map<String, Any>
        val subjectUri = parent["id"] as String
        val collectionId = parent["_collectionId"] as String

        val quads = quadStore.query(
            collectionId,
            QuadQuery(subject = subjectUri, predicate = propertyUri)
        )

        if (functional) {
            quads.firstOrNull()?.let { convertLiteral(it.objectValue, datatype) }
        } else {
            quads.mapNotNull { convertLiteral(it.objectValue, datatype) }
        }
    }

    fun objectPropertyFetcher(
        quadStore: QuadStore,
        propertyUri: String,
        functional: Boolean,
    ): DataFetcher<Any?> = DataFetcher { env ->
        @Suppress("UNCHECKED_CAST")
        val parent = env.getSource<Any>() as Map<String, Any>
        val subjectUri = parent["id"] as String
        val collectionId = parent["_collectionId"] as String

        val quads = quadStore.query(
            collectionId,
            QuadQuery(subject = subjectUri, predicate = propertyUri)
        ).filter { it.objectType == ObjectType.URI }

        val objectUris = quads.map { it.objectValue }.distinct()

        if (functional) {
            objectUris.firstOrNull()?.let { uri ->
                mapOf<String, Any>("id" to uri, "_collectionId" to collectionId)
            }
        } else {
            val limit = env.getArgumentOrDefault("limit", 10)
            val offset = env.getArgumentOrDefault("offset", 0)
            objectUris
                .drop(offset)
                .take(limit)
                .map { uri -> mapOf<String, Any>("id" to uri, "_collectionId" to collectionId) }
        }
    }

    private fun applyFilter(
        quadStore: QuadStore,
        collectionId: String,
        subjectUris: List<String>,
        filter: Map<String, Any>,
        datatypeProperties: Map<String, String>,
    ): List<String> {
        var filtered = subjectUris
        for ((fieldName, filterValue) in filter) {
            val propertyUri = datatypeProperties[fieldName] ?: continue
            filtered = filtered.filter { uri ->
                val quads = quadStore.query(
                    collectionId,
                    QuadQuery(subject = uri, predicate = propertyUri, objectValue = filterValue.toString())
                )
                quads.isNotEmpty()
            }
        }
        return filtered
    }
}
