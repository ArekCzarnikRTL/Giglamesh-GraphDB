package com.agentwork.graphmesh.cli

import com.expediagroup.graphql.client.types.GraphQLClientRequest

/**
 * Operations every CLI command needs from the GraphQL gateway.
 * Implemented by [GraphQlGateway] in production and by `FakeGateway` in tests.
 */
interface GatewayOps {
    suspend fun <T : Any> execute(request: GraphQLClientRequest<T>): T
    fun close() {}
}

/**
 * Shared configuration passed from the root command down to every subcommand
 * via Clikt's context object.
 */
data class CliConfig(
    val endpoint: String,
    val token: String,
    val format: OutputFormat,
    val gatewayFactory: (CliConfig) -> GatewayOps = { cfg -> GraphQlGateway(cfg) }
)

enum class OutputFormat { TABLE, JSON }
