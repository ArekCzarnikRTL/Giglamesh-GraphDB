package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.collection.CollectionEvent
import com.agentwork.graphmesh.collection.CollectionEventType
import graphql.GraphQL
import graphql.schema.GraphQLSchema
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class DynamicGraphQlRegistry {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val schemas = ConcurrentHashMap<String, GraphQL>()

    fun register(collectionId: String, schema: GraphQLSchema) {
        schemas[collectionId] = GraphQL.newGraphQL(schema).build()
        logger.info("Dynamic GraphQL schema registered for collection '{}'", collectionId)
    }

    fun get(collectionId: String): GraphQL? = schemas[collectionId]

    fun remove(collectionId: String) {
        if (schemas.remove(collectionId) != null) {
            logger.info("Dynamic GraphQL schema removed for collection '{}'", collectionId)
        }
    }

    fun has(collectionId: String): Boolean = schemas.containsKey(collectionId)

    @EventListener
    fun onCollectionDeleted(event: CollectionEvent) {
        if (event.type == CollectionEventType.DELETED) {
            remove(event.collectionId)
        }
    }
}
