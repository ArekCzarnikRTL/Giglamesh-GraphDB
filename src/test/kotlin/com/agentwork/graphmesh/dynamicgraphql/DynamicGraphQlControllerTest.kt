package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.collection.Collection
import com.agentwork.graphmesh.collection.CollectionService
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.FieldCoordinates
import graphql.schema.DataFetcher
import graphql.Scalars
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DynamicGraphQlControllerTest {

    private val registry = DynamicGraphQlRegistry()
    private val collectionService = mockk<CollectionService>()
    private val controller = DynamicGraphQlController(registry, collectionService)

    private fun registerDummySchema(collectionId: String) {
        val queryType = GraphQLObjectType.newObject()
            .name("Query")
            .field { it.name("hello").type(Scalars.GraphQLString) }
            .build()
        val schema = GraphQLSchema.newSchema()
            .query(queryType)
            .codeRegistry(
                GraphQLCodeRegistry.newCodeRegistry()
                    .dataFetcher(FieldCoordinates.coordinates("Query", "hello"), DataFetcher { "world" })
                    .build()
            ).build()
        registry.register(collectionId, schema)
    }

    @Test
    fun `returns 404 when collection not found`() {
        every { collectionService.findByName("unknown") } returns null
        val response = controller.execute("unknown", mapOf("query" to "{ hello }"))
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `returns 404 when no schema registered`() {
        every { collectionService.findByName("test") } returns Collection(id = "col-1", name = "test")
        val response = controller.execute("test", mapOf("query" to "{ hello }"))
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `returns 200 with data for valid query`() {
        every { collectionService.findByName("test") } returns Collection(id = "col-1", name = "test")
        registerDummySchema("col-1")
        val response = controller.execute("test", mapOf("query" to "{ hello }"))
        assertEquals(HttpStatus.OK, response.statusCode)
        @Suppress("UNCHECKED_CAST")
        val data = response.body!!["data"] as Map<String, Any>
        assertEquals("world", data["hello"])
    }

    @Test
    fun `returns 400 when query is missing`() {
        every { collectionService.findByName("test") } returns Collection(id = "col-1", name = "test")
        registerDummySchema("col-1")
        val response = controller.execute("test", emptyMap())
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }
}
