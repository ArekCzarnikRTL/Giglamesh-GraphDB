package com.agentwork.graphmesh.agent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.agentwork.graphmesh.query.docrag.DocumentRagQuery
import com.agentwork.graphmesh.query.docrag.DocumentRagService
import com.agentwork.graphmesh.query.graphrag.GraphRagQuery
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import kotlinx.serialization.Serializable

class KnowledgeQueryTool(
    private val graphRagService: GraphRagService,
    private val collectionId: String
) : SimpleTool<KnowledgeQueryTool.Args>(
    argsType = typeToken<Args>(),
    name = "knowledge_query",
    description = "Query the knowledge graph for entities and relationships. Use this when you need factual information about concepts, people, organizations, or their relationships."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The question to ask the knowledge graph")
        val question: String
    )

    override suspend fun execute(args: Args): String {
        val result = graphRagService.query(
            GraphRagQuery(question = args.question, collectionId = collectionId)
        )
        val sources = result.selectedEdges.joinToString("\n") { edge ->
            "  - ${edge.subject} --[${edge.predicate}]--> ${edge.objectValue}"
        }
        return "${result.answer}\n\nSources (${result.selectedEdges.size} edges):\n$sources"
    }
}

class DocumentQueryTool(
    private val documentRagService: DocumentRagService,
    private val collectionId: String
) : SimpleTool<DocumentQueryTool.Args>(
    argsType = typeToken<Args>(),
    name = "document_query",
    description = "Search documents for relevant text passages. Use this when you need detailed explanations, quotes, or context from the original documents."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The question to search documents for")
        val question: String
    )

    override suspend fun execute(args: Args): String {
        val result = documentRagService.query(
            DocumentRagQuery(question = args.question, collectionId = collectionId)
        )
        val sources = result.sources.joinToString("\n") { src ->
            "  - ${src.documentTitle} (page ${src.pageNumber ?: "?"}, score: ${"%.2f".format(src.score)}): ${src.snippet}"
        }
        return "${result.answer}\n\nSources (${result.sources.size} chunks):\n$sources"
    }
}
