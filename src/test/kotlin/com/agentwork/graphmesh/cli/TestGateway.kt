package com.agentwork.graphmesh.cli

import com.expediagroup.graphql.client.types.GraphQLClientRequest
import com.github.ajalt.clikt.core.CliktError
import kotlin.reflect.KClass

/**
 * In-memory [GatewayOps] implementation for command tests. Each command test
 * registers handlers that produce canned responses for specific request types.
 */
class FakeGateway private constructor(
    private val handlers: Map<KClass<out GraphQLClientRequest<*>>, suspend (GraphQLClientRequest<*>) -> Any>
) : GatewayOps {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> execute(request: GraphQLClientRequest<T>): T {
        val handler = handlers[request::class]
            ?: throw CliktError("FakeGateway: no handler for ${request::class.simpleName}")
        return handler(request) as T
    }

    class Builder {
        private val handlers = mutableMapOf<KClass<out GraphQLClientRequest<*>>, suspend (GraphQLClientRequest<*>) -> Any>()

        fun <R : GraphQLClientRequest<T>, T : Any> on(
            requestClass: KClass<R>,
            handler: suspend (R) -> T
        ): Builder = apply {
            @Suppress("UNCHECKED_CAST")
            handlers[requestClass] = { req -> handler(req as R) }
        }

        fun build(): FakeGateway = FakeGateway(handlers.toMap())
    }

    companion object {
        fun builder() = Builder()
    }
}
