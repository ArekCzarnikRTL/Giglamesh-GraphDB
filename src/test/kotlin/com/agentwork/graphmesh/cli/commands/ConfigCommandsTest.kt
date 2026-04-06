package com.agentwork.graphmesh.cli.commands

import com.agentwork.graphmesh.cli.CliConfig
import com.agentwork.graphmesh.cli.FakeGateway
import com.agentwork.graphmesh.cli.GraphMeshCli
import com.agentwork.graphmesh.cli.OutputFormat
import com.agentwork.graphmesh.cli.generated.GetConfigValue
import com.agentwork.graphmesh.cli.generated.ListConfigKeys
import com.agentwork.graphmesh.cli.generated.SetConfigValue
import com.agentwork.graphmesh.cli.generated.listconfigkeys.ConfigEntry as ListConfigEntry
import com.agentwork.graphmesh.cli.generated.getconfigvalue.ConfigEntry as GetConfigEntry
import com.agentwork.graphmesh.cli.generated.setconfigvalue.ConfigEntry as SetConfigEntry
import com.github.ajalt.clikt.command.test
import com.github.ajalt.clikt.core.subcommands
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigCommandsTest {

    private fun cliWith(fake: FakeGateway, format: OutputFormat = OutputFormat.TABLE): GraphMeshCli {
        val root = GraphMeshCli()
        root.setTestConfig(
            CliConfig(
                endpoint = "http://test",
                token = "",
                format = format,
                gatewayFactory = { fake }
            )
        )
        root.subcommands(ConfigCommand())
        return root
    }

    @Test
    fun `config list renders table with key and value`() = runBlocking {
        val fake = FakeGateway.builder()
            .on(ListConfigKeys::class) { _ ->
                ListConfigKeys.Result(
                    configKeys = listOf(
                        ListConfigEntry(
                            id = "cfg-1",
                            type = "LLM_SETTINGS",
                            key = "model",
                            value = "gpt-4",
                            version = 1
                        )
                    )
                )
            }
            .build()

        val result = cliWith(fake).test("config list")

        assertEquals(0, result.statusCode, "Non-zero exit: ${result.stderr}")
        assertTrue(result.stdout.contains("model"), "Expected key 'model' in stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("gpt-4"), "Expected value 'gpt-4' in stdout: ${result.stdout}")
    }

    @Test
    fun `config get shows value`() = runBlocking {
        val fake = FakeGateway.builder()
            .on(GetConfigValue::class) { _ ->
                GetConfigValue.Result(
                    configValue = GetConfigEntry(
                        id = "cfg-2",
                        type = "LLM_SETTINGS",
                        key = "temperature",
                        value = "0.7",
                        version = 2
                    )
                )
            }
            .build()

        val result = cliWith(fake).test("config get temperature --type LLM_SETTINGS")

        assertEquals(0, result.statusCode, "Non-zero exit: ${result.stderr}")
        assertTrue(result.stdout.contains("0.7"), "Expected value '0.7' in stdout: ${result.stdout}")
    }

    @Test
    fun `config set shows updated value`() = runBlocking {
        val fake = FakeGateway.builder()
            .on(SetConfigValue::class) { _ ->
                SetConfigValue.Result(
                    setConfig = SetConfigEntry(
                        id = "cfg-3",
                        type = "LLM_SETTINGS",
                        key = "maxTokens",
                        value = "2048",
                        version = 3
                    )
                )
            }
            .build()

        val result = cliWith(fake).test("config set maxTokens 2048 --type LLM_SETTINGS")

        assertEquals(0, result.statusCode, "Non-zero exit: ${result.stderr}")
        assertTrue(result.stdout.contains("2048"), "Expected value '2048' in stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("maxTokens"), "Expected key 'maxTokens' in stdout: ${result.stdout}")
    }
}
