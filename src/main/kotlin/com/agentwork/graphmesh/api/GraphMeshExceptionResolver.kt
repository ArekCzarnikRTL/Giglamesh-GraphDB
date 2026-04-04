package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.collection.CollectionNotFoundException
import com.agentwork.graphmesh.librarian.DocumentNotFoundException
import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.stereotype.Component

@Component
class GraphMeshExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(
        ex: Throwable,
        env: DataFetchingEnvironment
    ): GraphQLError? {
        return when (ex) {
            is CollectionNotFoundException -> GraphQLError.newError()
                .message(ex.message)
                .extensions(mapOf("code" to "COLLECTION_NOT_FOUND"))
                .build()

            is DocumentNotFoundException -> GraphQLError.newError()
                .message(ex.message)
                .extensions(mapOf("code" to "DOCUMENT_NOT_FOUND"))
                .build()

            is IllegalArgumentException -> GraphQLError.newError()
                .message(ex.message)
                .extensions(mapOf("code" to "BAD_REQUEST"))
                .build()

            else -> null
        }
    }
}
