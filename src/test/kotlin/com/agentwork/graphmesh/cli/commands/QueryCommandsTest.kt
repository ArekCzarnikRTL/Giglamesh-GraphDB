package com.agentwork.graphmesh.cli.commands

import com.agentwork.graphmesh.cli.CliConfig
import com.agentwork.graphmesh.cli.FakeGateway
import com.agentwork.graphmesh.cli.GraphMeshCli
import com.agentwork.graphmesh.cli.OutputFormat
import com.agentwork.graphmesh.cli.generated.DocRagQuery
import com.agentwork.graphmesh.cli.generated.GraphRagQuery
import com.agentwork.graphmesh.cli.generated.NlpQuery
import com.agentwork.graphmesh.cli.generated.enums.QueryIntentEnum
import com.agentwork.graphmesh.cli.generated.docragquery.DocumentRagResponse
import com.agentwork.graphmesh.cli.generated.docragquery.SourceAttributionType
import com.agentwork.graphmesh.cli.generated.graphragquery.GraphRagResponse
import com.agentwork.graphmesh.cli.generated.graphragquery.SelectedEdgeType
import com.agentwork.graphmesh.cli.generated.nlpquery.DetectedIntentType
import com.agentwork.graphmesh.cli.generated.nlpquery.NlpQueryResponse
import com.github.ajalt.clikt.command.test
import com.github.ajalt.clikt.core.subcommands
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryCommandsTest {

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
        root.subcommands(QueryCommand())
        return root
    }

    @Test
    fun `query graphrag prints answer`() = runBlocking {
        val fake = FakeGateway.builder()
            .on(GraphRagQuery::class) { _ ->
                GraphRagQuery.Result(
                    graphRag = GraphRagResponse(
                        sessionId = "sess-1",
                        answer = "The answer is 42.",
                        retrievedEdgeCount = 10,
                        durationMs = 150,
                        selectedEdges = listOf(
                            SelectedEdgeType(
                                subject = "Earth",
                                predicate = "hasAnswer",
                                objectValue = "42",
                                reasoning = "because",
                                relevanceScore = 0.99
                            )
                        )
                    )
                )
            }
            .build()

        val result = cliWith(fake).test("query graphrag -c col-1 \"What?\"")

        assertEquals(0, result.statusCode, "Non-zero exit: ${result.stderr}")
        assertTrue(result.stdout.contains("The answer is 42."), "Expected answer in stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("sess-1"), "Expected sessionId in stdout: ${result.stdout}")
    }

    @Test
    fun `query docrag prints answer and sources`() = runBlocking {
        val fake = FakeGateway.builder()
            .on(DocRagQuery::class) { _ ->
                DocRagQuery.Result(
                    documentRag = DocumentRagResponse(
                        sessionId = "sess-2",
                        answer = "DocRag found the answer.",
                        retrievedChunkCount = 5,
                        durationMs = 200,
                        sources = listOf(
                            SourceAttributionType(
                                chunkId = "chunk-1",
                                documentId = "doc-1",
                                documentTitle = "GreatPaper",
                                pageNumber = 7,
                                score = 0.87,
                                snippet = "...relevant text..."
                            )
                        )
                    )
                )
            }
            .build()

        val result = cliWith(fake).test("query docrag -c col-1 \"What is the answer?\"")

        assertEquals(0, result.statusCode, "Non-zero exit: ${result.stderr}")
        assertTrue(result.stdout.contains("DocRag found the answer."), "Expected answer in stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("GreatPaper"), "Expected source title in stdout: ${result.stdout}")
    }

    @Test
    fun `query nlp prints answer and intent`() = runBlocking {
        val fake = FakeGateway.builder()
            .on(NlpQuery::class) { _ ->
                NlpQuery.Result(
                    nlpQuery = NlpQueryResponse(
                        answer = "NLP answer here.",
                        wasReformulated = false,
                        effectiveQuestion = "What is the meaning?",
                        durationMs = 120,
                        sources = listOf("src-1"),
                        detectedIntent = DetectedIntentType(
                            intent = QueryIntentEnum.GRAPH_QUERY,
                            confidence = 0.95,
                            reasoning = "looks like a graph query"
                        )
                    )
                )
            }
            .build()

        val result = cliWith(fake).test("query nlp -c col-1 \"What is the meaning?\"")

        assertEquals(0, result.statusCode, "Non-zero exit: ${result.stderr}")
        assertTrue(result.stdout.contains("NLP answer here."), "Expected answer in stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("GRAPH_QUERY"), "Expected intent in stdout: ${result.stdout}")
    }
}
