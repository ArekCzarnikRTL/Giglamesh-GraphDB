package com.agentwork.graphmesh.cli.commands

import com.agentwork.graphmesh.cli.CliConfig
import com.agentwork.graphmesh.cli.FakeGateway
import com.agentwork.graphmesh.cli.GraphMeshCli
import com.agentwork.graphmesh.cli.OutputFormat
import com.agentwork.graphmesh.cli.generated.GetDocumentHierarchy
import com.agentwork.graphmesh.cli.generated.GetExplanationChain
import com.agentwork.graphmesh.cli.generated.ListExplanationSessions
import com.agentwork.graphmesh.cli.generated.enums.QueryMechanism
import com.agentwork.graphmesh.cli.generated.getdocumenthierarchy.DocumentNode
import com.agentwork.graphmesh.cli.generated.getdocumenthierarchy.DocumentNode2
import com.agentwork.graphmesh.cli.generated.getdocumenthierarchy.DocumentNode3
import com.agentwork.graphmesh.cli.generated.getdocumenthierarchy.DocumentNode4
import com.agentwork.graphmesh.cli.generated.getexplanationchain.ConclusionExplanation
import com.agentwork.graphmesh.cli.generated.getexplanationchain.ExplanationChain
import com.agentwork.graphmesh.cli.generated.getexplanationchain.QuestionExplanation
import com.agentwork.graphmesh.cli.generated.listexplanationsessions.QuestionExplanation as SessionExplanation
import com.github.ajalt.clikt.command.test
import com.github.ajalt.clikt.core.subcommands
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExplainCommandsTest {

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
        root.subcommands(ExplainCommand())
        return root
    }

    @Test
    fun `explain sessions renders table with question text`() = runBlocking {
        val fake = FakeGateway.builder()
            .on(ListExplanationSessions::class) { _ ->
                ListExplanationSessions.Result(
                    explanationSessions = listOf(
                        SessionExplanation(
                            uri = "urn:session:s1",
                            queryText = "What is GraphMesh?",
                            timestamp = "2026-01-01T00:00:00Z",
                            mechanism = QueryMechanism.GRAPH_RAG
                        )
                    )
                )
            }
            .build()

        val result = cliWith(fake).test("explain sessions -c col-1")

        assertEquals(0, result.statusCode, "Non-zero exit: ${result.stderr}")
        assertTrue(result.stdout.contains("What is GraphMesh?"), "Expected query text in stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("GRAPH_RAG"), "Expected mechanism in stdout: ${result.stdout}")
    }

    @Test
    fun `explain trace shows question and conclusion`() = runBlocking {
        val fake = FakeGateway.builder()
            .on(GetExplanationChain::class) { _ ->
                GetExplanationChain.Result(
                    explanationChain = ExplanationChain(
                        mechanism = QueryMechanism.GRAPH_RAG,
                        question = QuestionExplanation(
                            uri = "urn:session:s1",
                            queryText = "Deep question about reality.",
                            timestamp = "2026-01-01T00:00:00Z",
                            mechanism = QueryMechanism.GRAPH_RAG
                        ),
                        exploration = null,
                        focus = null,
                        analyses = null,
                        synthesis = null,
                        conclusion = ConclusionExplanation(
                            uri = "urn:conclusion:c1",
                            answerText = "The final answer is 42."
                        )
                    )
                )
            }
            .build()

        val result = cliWith(fake).test("explain trace urn:session:s1 -c col-1")

        assertEquals(0, result.statusCode, "Non-zero exit: ${result.stderr}")
        assertTrue(result.stdout.contains("Deep question about reality."), "Expected question in stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("The final answer is 42."), "Expected conclusion in stdout: ${result.stdout}")
    }

    @Test
    fun `explain document shows document title in tree`() = runBlocking {
        val fake = FakeGateway.builder()
            .on(GetDocumentHierarchy::class) { _ ->
                GetDocumentHierarchy.Result(
                    documentHierarchy = DocumentNode(
                        id = "doc-root",
                        title = "Annual Report 2026",
                        type = "SOURCE",
                        children = listOf(
                            DocumentNode2(
                                id = "page-1",
                                title = "Executive Summary",
                                type = "PAGE",
                                children = listOf(
                                    DocumentNode3(
                                        id = "chunk-1",
                                        title = "Paragraph 1",
                                        type = "CHUNK",
                                        children = listOf(
                                            DocumentNode4(
                                                id = "chunk-1a",
                                                title = "Sub-chunk 1a",
                                                type = "CHUNK"
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            }
            .build()

        val result = cliWith(fake).test("explain document doc-root -c col-1")

        assertEquals(0, result.statusCode, "Non-zero exit: ${result.stderr}")
        assertTrue(result.stdout.contains("Annual Report 2026"), "Expected document title in stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("Executive Summary"), "Expected child title in stdout: ${result.stdout}")
    }
}
