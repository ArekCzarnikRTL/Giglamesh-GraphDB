package com.agentwork.graphmesh.cli

import com.agentwork.graphmesh.cli.output.JsonOutput
import com.agentwork.graphmesh.cli.output.Output
import com.agentwork.graphmesh.cli.output.TableOutput
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.requireObject

/**
 * Base class for all GraphMesh CLI subcommands. Pulls the shared [CliConfig]
 * from the Clikt context (set by the root [GraphMeshCli]) and exposes a lazy
 * [Output] and [GatewayOps] so subclasses stay free of boilerplate.
 */
abstract class BaseCommand(
    name: String
) : SuspendingCliktCommand(name = name) {

    protected val cfg: CliConfig by requireObject()

    protected val out: Output by lazy {
        when (cfg.format) {
            OutputFormat.TABLE -> TableOutput(sink = ::println)
            OutputFormat.JSON  -> JsonOutput(sink = ::println, mapper = jacksonObjectMapper())
        }
    }

    protected fun gateway(): GatewayOps = cfg.gatewayFactory(cfg)
}
