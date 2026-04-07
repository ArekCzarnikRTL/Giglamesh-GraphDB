package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.storage.GraphMetadataView
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

private const val MAX_TRIPLE_LIMIT = 5000
private const val DEFAULT_TRIPLE_LIMIT = 500
private const val MAX_ENTITY_SEARCH_LIMIT = 200
private const val DEFAULT_ENTITY_SEARCH_LIMIT = 20

@Controller
class GraphController(
    private val quadStore: QuadStore
) {

    @QueryMapping
    fun triples(
        @Argument collectionId: String,
        @Argument subject: String?,
        @Argument predicate: String?,
        @Argument("object") objectValue: String?,
        @Argument dataset: String?,
        @Argument limit: Int? = null
    ): List<StoredQuad> {
        val effectiveLimit = (limit ?: DEFAULT_TRIPLE_LIMIT).coerceIn(1, MAX_TRIPLE_LIMIT)
        return quadStore.query(
            collectionId,
            QuadQuery(
                subject = subject,
                predicate = predicate,
                objectValue = objectValue,
                dataset = dataset
            ),
            limit = effectiveLimit
        )
    }

    @QueryMapping
    fun entitySearch(
        @Argument collectionId: String,
        @Argument prefix: String,
        @Argument limit: Int? = null
    ): List<String> {
        val effectiveLimit = (limit ?: DEFAULT_ENTITY_SEARCH_LIMIT).coerceIn(1, MAX_ENTITY_SEARCH_LIMIT)
        return quadStore.findSubjects(collectionId, prefix, effectiveLimit)
    }

    @QueryMapping
    fun graphMetadata(@Argument collectionId: String): GraphMetadataView {
        return quadStore.aggregateMetadata(collectionId)
    }

    // Schema field is `object`, Kotlin property is `objectValue`.
    @SchemaMapping(typeName = "Quad", field = "object")
    fun quadObject(quad: StoredQuad): String = quad.objectValue

    // GraphQL enum exposed as String.
    @SchemaMapping(typeName = "Quad", field = "objectType")
    fun quadObjectType(quad: StoredQuad): String = quad.objectType.name
}
