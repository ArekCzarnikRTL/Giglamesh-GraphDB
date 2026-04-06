package com.agentwork.graphmesh.cli.commands

import com.agentwork.graphmesh.cli.BaseCommand
import com.agentwork.graphmesh.cli.generated.DocRagQuery
import com.agentwork.graphmesh.cli.generated.GraphRagQuery
import com.agentwork.graphmesh.cli.generated.NlpQuery
import com.agentwork.graphmesh.cli.generated.enums.QueryIntentEnum
import com.agentwork.graphmesh.cli.generated.inputs.DocumentRagInput
import com.agentwork.graphmesh.cli.generated.inputs.GraphRagInput
import com.agentwork.graphmesh.cli.generated.inputs.NlpQueryInput
import com.agentwork.graphmesh.cli.output.DocRagResponseView
import com.agentwork.graphmesh.cli.output.DocRagSourceView
import com.agentwork.graphmesh.cli.output.GraphRagResponseView
import com.agentwork.graphmesh.cli.output.NlpResponseView
import com.agentwork.graphmesh.cli.output.SelectedEdgeView
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int

class QueryCommand : BaseCommand("query") {
    init { subcommands(QueryGraphRag(), QueryDocRag(), QueryNlp()) }
    override suspend fun run() = Unit
}

class QueryGraphRag : BaseCommand("graphrag") {
    private val question by argument()
    private val collectionId by option("--collection", "-c").required()
    private val maxEdges by option("--max-edges").int()
    private val maxDepth by option("--max-depth").int()
    private val maxSelectedEdges by option("--max-selected").int()

    override suspend fun run() {
        val result = gateway().execute(GraphRagQuery(GraphRagQuery.Variables(
            input = GraphRagInput(
                question = question,
                collectionId = collectionId,
                maxEdges = maxEdges,
                maxDepth = maxDepth,
                maxSelectedEdges = maxSelectedEdges
            )
        )))
        val r = result.graphRag
        out.writeGraphRag(GraphRagResponseView(
            sessionId = r.sessionId,
            answer = r.answer,
            retrievedEdgeCount = r.retrievedEdgeCount,
            durationMs = r.durationMs,
            selectedEdges = r.selectedEdges.map { edge ->
                SelectedEdgeView(
                    subject = edge.subject,
                    predicate = edge.predicate,
                    objectValue = edge.objectValue,
                    reasoning = edge.reasoning,
                    relevanceScore = edge.relevanceScore
                )
            }
        ))
    }
}

class QueryDocRag : BaseCommand("docrag") {
    private val question by argument()
    private val collectionId by option("--collection", "-c").required()
    private val topK by option("--top-k").int()
    private val threshold by option("--threshold").double()

    override suspend fun run() {
        val result = gateway().execute(DocRagQuery(DocRagQuery.Variables(
            input = DocumentRagInput(
                question = question,
                collectionId = collectionId,
                topK = topK,
                similarityThreshold = threshold
            )
        )))
        val r = result.documentRag
        out.writeDocRag(DocRagResponseView(
            sessionId = r.sessionId,
            answer = r.answer,
            retrievedChunkCount = r.retrievedChunkCount,
            durationMs = r.durationMs,
            sources = r.sources.map { src ->
                DocRagSourceView(
                    chunkId = src.chunkId,
                    documentId = src.documentId,
                    documentTitle = src.documentTitle,
                    pageNumber = src.pageNumber,
                    score = src.score,
                    snippet = src.snippet
                )
            }
        ))
    }
}

class QueryNlp : BaseCommand("nlp") {
    private val question by argument()
    private val collectionId by option("--collection", "-c").required()
    private val forceIntentStr by option("--force-intent")

    override suspend fun run() {
        val forceIntent = forceIntentStr?.let { s ->
            QueryIntentEnum.values().find { it.name == s }
        }
        val result = gateway().execute(NlpQuery(NlpQuery.Variables(
            input = NlpQueryInput(
                question = question,
                collectionId = collectionId,
                forceIntent = forceIntent
            )
        )))
        val r = result.nlpQuery
        out.writeNlp(NlpResponseView(
            answer = r.answer,
            detectedIntent = r.detectedIntent.intent.toString(),
            intentConfidence = r.detectedIntent.confidence,
            wasReformulated = r.wasReformulated,
            effectiveQuestion = r.effectiveQuestion,
            durationMs = r.durationMs,
            sources = r.sources
        ))
    }
}
