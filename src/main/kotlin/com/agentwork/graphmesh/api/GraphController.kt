package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

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
        @Argument dataset: String?
    ): List<StoredQuad> {
        return quadStore.query(
            collectionId,
            QuadQuery(
                subject = subject,
                predicate = predicate,
                objectValue = objectValue,
                dataset = dataset
            )
        )
    }

    // Schema-Feld heisst `object`, Kotlin-Property aber `objectValue` (object ist reserviert).
    @SchemaMapping(typeName = "Quad", field = "object")
    fun quadObject(quad: StoredQuad): String = quad.objectValue

    // GraphQL-Enum wird als String exponiert; Kotlin-Property liefert das Enum.
    @SchemaMapping(typeName = "Quad", field = "objectType")
    fun quadObjectType(quad: StoredQuad): String = quad.objectType.name
}
