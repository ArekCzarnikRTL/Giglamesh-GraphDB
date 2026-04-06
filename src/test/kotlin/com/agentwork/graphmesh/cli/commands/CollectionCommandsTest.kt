package com.agentwork.graphmesh.cli.commands

import com.agentwork.graphmesh.cli.CliConfig
import com.agentwork.graphmesh.cli.FakeGateway
import com.agentwork.graphmesh.cli.GraphMeshCli
import com.agentwork.graphmesh.cli.OutputFormat
import com.agentwork.graphmesh.cli.generated.CreateCollection
import com.agentwork.graphmesh.cli.generated.DeleteCollection
import com.agentwork.graphmesh.cli.generated.ListCollections
import com.agentwork.graphmesh.cli.generated.createcollection.Collection as CreatedCollection
import com.agentwork.graphmesh.cli.generated.listcollections.Collection as ListedCollection
import com.github.ajalt.clikt.command.test
import com.github.ajalt.clikt.core.subcommands
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionCommandsTest {

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
        root.subcommands(CollectionCommand())
        return root
    }

    @Test
    fun `collection list renders table`() = runBlocking {
        val fake = FakeGateway.builder()
            .on(ListCollections::class) { _ ->
                ListCollections.Result(
                    collections = listOf(
                        ListedCollection(
                            id = "c1",
                            name = "Alpha",
                            description = "d",
                            tags = listOf("a"),
                            createdAt = "2026-01-01T00:00:00Z"
                        )
                    )
                )
            }
            .build()

        val result = cliWith(fake).test("collection list")

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("Alpha"), "Expected 'Alpha' in stdout: ${result.stdout}")
    }

    @Test
    fun `collection create echoes new id`() = runBlocking {
        val fake = FakeGateway.builder()
            .on(CreateCollection::class) { _ ->
                CreateCollection.Result(
                    createCollection = CreatedCollection(
                        id = "c-new",
                        name = "Test",
                        description = null,
                        tags = listOf(),
                        createdAt = "2026-01-01T00:00:00Z"
                    )
                )
            }
            .build()

        val result = cliWith(fake).test("collection create Test")

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("c-new"), "Expected 'c-new' in stdout: ${result.stdout}")
    }

    @Test
    fun `collection delete returns success`() = runBlocking {
        val fake = FakeGateway.builder()
            .on(DeleteCollection::class) { _ ->
                DeleteCollection.Result(deleteCollection = true)
            }
            .build()

        val result = cliWith(fake).test("collection delete c-42")

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("c-42"), "Expected 'c-42' in stdout: ${result.stdout}")
    }
}
