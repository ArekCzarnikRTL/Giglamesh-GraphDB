package com.agentwork.graphmesh.extraction.agent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.query.graphrag.GraphRagQuery
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.storage.QuadStore
import kotlinx.serialization.Serializable

class GraphQueryTool(
    private val graphRagService: GraphRagService,
    private val collectionId: String
) : SimpleTool<GraphQueryTool.Args>(
    argsType = typeToken<Args>(),
    name = "graph_query",
    description = "Query the knowledge graph to find existing entities and relationships. Use this to check what is already known before extracting new knowledge."
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

class ValidateEntityTool(
    private val quadStore: QuadStore,
    private val collectionId: String
) : SimpleTool<ValidateEntityTool.Args>(
    argsType = typeToken<Args>(),
    name = "validate_entity",
    description = "Check if an entity already exists in the knowledge graph. Returns EXISTS with predicates or NOT_FOUND."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The entity name to validate")
        val entityName: String
    )

    override suspend fun execute(args: Args): String {
        val entityId = EntityIdGenerator.generate(args.entityName).value
        val quads = quadStore.findByEntities(collectionId, listOf(entityId))
        return if (quads.isNotEmpty()) {
            val predicates = quads.map { it.predicate.substringAfterLast("/").substringAfterLast("#") }.distinct()
            "EXISTS: Entity '${args.entityName}' found with predicates: ${predicates.joinToString(", ")}"
        } else {
            "NOT_FOUND"
        }
    }
}

class ContextExpandTool(
    private val librarianService: LibrarianService,
    private val currentChunkId: String
) : SimpleTool<ContextExpandTool.Args>(
    argsType = typeToken<Args>(),
    name = "context_expand",
    description = "Load sibling chunks from the same document to get more context around the current text."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Why you need more context")
        val reason: String
    )

    override suspend fun execute(args: Args): String {
        val chunk = librarianService.findById(currentChunkId)
            ?: return "Chunk '$currentChunkId' not found."

        val parentId = chunk.parentId ?: return "No sibling chunks available (no parent document)."

        val siblings = librarianService.findChildren(parentId)
            .filter { it.id != currentChunkId }

        if (siblings.isEmpty()) return "No sibling chunks found."

        return siblings.joinToString("\n\n---\n\n") { sibling ->
            val text = String(librarianService.getContent(sibling.id), Charsets.UTF_8)
            "[${sibling.id}]:\n$text"
        }
    }
}
