package com.agentwork.graphmesh.cli

/**
 * Shared configuration passed from the root command down to every subcommand
 * via Clikt's context object.
 */
data class CliConfig(
    val endpoint: String,
    val token: String,
    val format: OutputFormat,
    val gatewayFactory: (CliConfig) -> GraphQlGateway = ::defaultGatewayFactory
)

enum class OutputFormat { TABLE, JSON }

internal fun defaultGatewayFactory(cfg: CliConfig): GraphQlGateway = GraphQlGateway(cfg)
