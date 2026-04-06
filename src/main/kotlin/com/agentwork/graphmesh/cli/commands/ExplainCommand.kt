package com.agentwork.graphmesh.cli.commands

import com.agentwork.graphmesh.cli.BaseCommand
import com.agentwork.graphmesh.cli.generated.GetDocumentHierarchy
import com.agentwork.graphmesh.cli.generated.GetExplanationChain
import com.agentwork.graphmesh.cli.generated.ListExplanationSessions
import com.agentwork.graphmesh.cli.generated.enums.QueryMechanism
import com.agentwork.graphmesh.cli.generated.getdocumenthierarchy.DocumentNode
import com.agentwork.graphmesh.cli.generated.getdocumenthierarchy.DocumentNode2
import com.agentwork.graphmesh.cli.generated.getdocumenthierarchy.DocumentNode3
import com.agentwork.graphmesh.cli.generated.getdocumenthierarchy.DocumentNode4
import com.agentwork.graphmesh.cli.output.AnalysisView
import com.agentwork.graphmesh.cli.output.ConclusionView
import com.agentwork.graphmesh.cli.output.DocumentNodeView
import com.agentwork.graphmesh.cli.output.ExplanationChainView
import com.agentwork.graphmesh.cli.output.ExplorationView
import com.agentwork.graphmesh.cli.output.FocusEdgeView
import com.agentwork.graphmesh.cli.output.FocusView
import com.agentwork.graphmesh.cli.output.QuestionExplanationView
import com.agentwork.graphmesh.cli.output.SynthesisView
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int

class ExplainCommand : BaseCommand("explain") {
    init { subcommands(ExplainSessions(), ExplainTrace(), ExplainDocument()) }
    override suspend fun run() = Unit
}

class ExplainSessions : BaseCommand("sessions") {
    private val collectionId by option("--collection", "-c").required()
    private val limit by option("--limit").int().default(50)
    private val mechanismStr by option("--mechanism", "-m")

    override suspend fun run() {
        val mechanism = mechanismStr?.let { s -> QueryMechanism.values().find { it.name == s } }
        val result = gateway().execute(ListExplanationSessions(ListExplanationSessions.Variables(
            collectionId = collectionId, mechanism = mechanism, limit = limit
        )))
        val items = result.explanationSessions.map { session ->
            QuestionExplanationView(
                uri = session.uri,
                queryText = session.queryText,
                timestamp = session.timestamp,
                mechanism = session.mechanism.toString()
            )
        }
        out.writeExplanationSessions(items)
    }
}

class ExplainTrace : BaseCommand("trace") {
    private val sessionUri by argument()
    private val collectionId by option("--collection", "-c").required()
    private val maxAnswer by option("--max-answer").int().default(500)

    override suspend fun run() {
        val result = gateway().execute(GetExplanationChain(GetExplanationChain.Variables(
            collectionId = collectionId, sessionUri = sessionUri
        )))
        val chain = result.explanationChain ?: run {
            out.writeMessage("No explanation chain for session $sessionUri")
            return
        }
        out.writeExplanationChain(
            ExplanationChainView(
                question = QuestionExplanationView(
                    uri = chain.question.uri,
                    queryText = chain.question.queryText,
                    timestamp = chain.question.timestamp,
                    mechanism = chain.question.mechanism.toString()
                ),
                mechanism = chain.mechanism.toString(),
                exploration = chain.exploration?.let { ExplorationView(it.uri, it.edgeCount) },
                focus = chain.focus?.let { f ->
                    FocusView(uri = f.uri, selectedEdges = f.selectedEdges.map { edge ->
                        FocusEdgeView(edge.subject, edge.predicate, edge.objectValue, edge.reasoning)
                    })
                },
                analyses = chain.analyses?.map { a ->
                    AnalysisView(
                        uri = a.uri,
                        iterationIndex = a.iterationIndex,
                        thought = a.thought,
                        action = a.action,
                        arguments = a.arguments?.map { it.key to it.value } ?: emptyList(),
                        observation = a.observation
                    )
                },
                synthesis = chain.synthesis?.let { SynthesisView(it.uri, it.answerText) },
                conclusion = chain.conclusion?.let { ConclusionView(it.uri, it.answerText) }
            ),
            maxAnswerChars = maxAnswer
        )
    }
}

class ExplainDocument : BaseCommand("document") {
    private val documentId by argument()
    private val collectionId by option("--collection", "-c").required()

    override suspend fun run() {
        val result = gateway().execute(GetDocumentHierarchy(GetDocumentHierarchy.Variables(
            collectionId = collectionId, documentId = documentId
        )))
        val root = result.documentHierarchy ?: run {
            out.writeMessage("Document $documentId not found")
            return
        }
        out.writeDocumentHierarchy(toLevel0(root))
    }

    private fun toLevel0(node: DocumentNode): DocumentNodeView =
        DocumentNodeView(node.id, node.title, node.type, node.children.map { toLevel1(it) })

    private fun toLevel1(node: DocumentNode2): DocumentNodeView =
        DocumentNodeView(node.id, node.title, node.type, node.children.map { toLevel2(it) })

    private fun toLevel2(node: DocumentNode3): DocumentNodeView =
        DocumentNodeView(node.id, node.title, node.type, node.children.map { toLevel3(it) })

    private fun toLevel3(node: DocumentNode4): DocumentNodeView =
        DocumentNodeView(node.id, node.title, node.type, emptyList())
}
