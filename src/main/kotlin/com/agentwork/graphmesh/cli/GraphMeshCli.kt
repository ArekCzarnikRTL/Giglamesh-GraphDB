package com.agentwork.graphmesh.cli

import com.agentwork.graphmesh.cli.commands.CollectionCommand
import com.agentwork.graphmesh.cli.commands.ConfigCommand
import com.agentwork.graphmesh.cli.commands.DocumentCommand
import com.agentwork.graphmesh.cli.commands.ExplainCommand
import com.agentwork.graphmesh.cli.commands.QueryCommand
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum

/**
 * Root command. Parses global options into a [CliConfig] and installs it on the
 * Clikt context so every subcommand can read it via [requireObject].
 */
class GraphMeshCli : SuspendingCliktCommand(name = "graphmesh") {

    private val endpoint by option("--endpoint", "-e", help = "GraphQL endpoint URL")
        .default(System.getenv("GRAPHMESH_ENDPOINT") ?: "http://localhost:8080/graphql")

    private val token by option("--token", "-t", help = "Bearer token for authentication")
        .default(System.getenv("GRAPHMESH_TOKEN") ?: "")

    private val format by option("--format", "-f", help = "Output format")
        .enum<OutputFormat>()
        .default(OutputFormat.TABLE)

    private val config by findOrSetObject { CliConfig(endpoint, token, format) }

    override suspend fun run() {
        // findOrSetObject initialises the context object on first access.
        // Accessing `config` here ensures it is set before subcommands execute.
        @Suppress("UNUSED_EXPRESSION")
        config
    }
}

suspend fun main(args: Array<String>) {
    GraphMeshCli()
        .subcommands(
            CollectionCommand(),
            DocumentCommand(),
            QueryCommand(),
            ConfigCommand(),
            ExplainCommand()
        )
        .main(args.toList())
}
