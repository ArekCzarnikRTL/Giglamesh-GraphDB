package com.agentwork.graphmesh.cli

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import com.expediagroup.graphql.client.types.GraphQLClientRequest
import com.github.ajalt.clikt.core.CliktError
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import java.net.URL

/**
 * Thin wrapper around [GraphQLKtorClient] that injects the Bearer token header
 * and maps GraphQL errors to [CliktError]s so Clikt can render them cleanly.
 */
class GraphQlGateway private constructor(
    private val client: GraphQLKtorClient,
    private val token: String
) : GatewayOps, AutoCloseable {

    constructor(cfg: CliConfig) : this(
        client = GraphQLKtorClient(
            url = URL(cfg.endpoint),
            httpClient = HttpClient(CIO) {
                install(HttpTimeout) { requestTimeoutMillis = 60_000 }
            }
        ),
        token = cfg.token
    )

    override suspend fun <T : Any> execute(request: GraphQLClientRequest<T>): T {
        val response = client.execute(request) {
            if (token.isNotBlank()) {
                header("Authorization", "Bearer $token")
            }
        }
        val errors = response.errors
        if (!errors.isNullOrEmpty()) {
            throw CliktError("GraphQL error: " + errors.joinToString(", ") { it.message ?: "unknown" })
        }
        return response.data ?: throw CliktError("GraphQL response contained no data")
    }

    override fun close() = client.close()

    companion object {
        /** Visible for testing only — injects a pre-built client. */
        internal fun forTesting(client: GraphQLKtorClient, token: String = ""): GraphQlGateway =
            GraphQlGateway(client, token)
    }
}
