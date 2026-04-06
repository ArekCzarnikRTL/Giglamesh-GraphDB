package com.agentwork.graphmesh.cli.commands

import com.agentwork.graphmesh.cli.BaseCommand
import com.agentwork.graphmesh.cli.generated.GetConfigValue
import com.agentwork.graphmesh.cli.generated.ListConfigKeys
import com.agentwork.graphmesh.cli.generated.SetConfigValue
import com.agentwork.graphmesh.cli.output.ConfigEntryView
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required

class ConfigCommand : BaseCommand("config") {
    init { subcommands(ConfigList(), ConfigGet(), ConfigSet()) }
    override suspend fun run() = Unit
}

class ConfigList : BaseCommand("list") {
    private val type by option("--type")

    override suspend fun run() {
        val result = gateway().execute(ListConfigKeys(ListConfigKeys.Variables(type = type)))
        val entries = result.configKeys.map { entry ->
            ConfigEntryView(entry.id, entry.type, entry.key, entry.value, entry.version)
        }
        out.writeConfigEntries(entries)
    }
}

class ConfigGet : BaseCommand("get") {
    private val key by argument(help = "Configuration key")
    private val type by option("--type").required()

    override suspend fun run() {
        val result = gateway().execute(GetConfigValue(GetConfigValue.Variables(key = key, type = type)))
        val entry = result.configValue
        if (entry == null) {
            out.writeMessage("Config not found: $type:$key")
            return
        }
        out.writeConfigEntry(ConfigEntryView(entry.id, entry.type, entry.key, entry.value, entry.version))
    }
}

class ConfigSet : BaseCommand("set") {
    private val key by argument(help = "Configuration key")
    private val value by argument(help = "Configuration value")
    private val type by option("--type").required()

    override suspend fun run() {
        val result = gateway().execute(SetConfigValue(SetConfigValue.Variables(
            key = key, value = value, type = type
        )))
        val entry = result.setConfig
        out.writeConfigEntry(ConfigEntryView(entry.id, entry.type, entry.key, entry.value, entry.version))
    }
}
