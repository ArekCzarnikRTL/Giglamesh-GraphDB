package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.collection.CollectionEvent
import com.agentwork.graphmesh.collection.CollectionEventType
import graphql.Scalars
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DynamicGraphQlRegistryTest {

    private lateinit var registry: DynamicGraphQlRegistry

    @BeforeEach
    fun setup() {
        registry = DynamicGraphQlRegistry()
    }

    private fun dummySchema(queryTypeName: String = "Query"): GraphQLSchema {
        val queryType = GraphQLObjectType.newObject()
            .name(queryTypeName)
            .field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name("id")
                    .type(Scalars.GraphQLString)
                    .build()
            )
            .build()
        return GraphQLSchema.newSchema().query(queryType).build()
    }

    @Test
    fun `register and get returns GraphQL instance`() {
        registry.register("col-1", dummySchema())
        assertNotNull(registry.get("col-1"))
    }

    @Test
    fun `get unknown collection returns null`() {
        assertNull(registry.get("unknown"))
    }

    @Test
    fun `has returns true after register`() {
        registry.register("col-2", dummySchema())
        assertTrue(registry.has("col-2"))
    }

    @Test
    fun `has returns false for unknown collection`() {
        assertFalse(registry.has("unknown"))
    }

    @Test
    fun `remove clears entry`() {
        registry.register("col-3", dummySchema())
        registry.remove("col-3")
        assertNull(registry.get("col-3"))
        assertFalse(registry.has("col-3"))
    }

    @Test
    fun `register replaces existing schema`() {
        registry.register("col-4", dummySchema("QueryA"))
        val first = registry.get("col-4")
        registry.register("col-4", dummySchema("QueryB"))
        val second = registry.get("col-4")
        assertNotNull(second)
        assertTrue(first !== second)
    }

    @Test
    fun `onCollectionDeleted removes schema when type is DELETED`() {
        registry.register("col-5", dummySchema())
        registry.onCollectionDeleted(CollectionEvent(CollectionEventType.DELETED, "col-5", "Collection Five"))
        assertNull(registry.get("col-5"))
    }

    @Test
    fun `onCollectionDeleted does not remove schema when type is not DELETED`() {
        registry.register("col-6", dummySchema())
        registry.onCollectionDeleted(CollectionEvent(CollectionEventType.CREATED, "col-6", "Collection Six"))
        assertNotNull(registry.get("col-6"))
    }
}
