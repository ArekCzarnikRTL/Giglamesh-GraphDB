package com.agentwork.graphmesh.api.mcp

import com.agentwork.graphmesh.collection.CollectionService
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.query.docrag.DocumentRagQuery
import com.agentwork.graphmesh.query.docrag.DocumentRagService
import com.agentwork.graphmesh.query.graphrag.GraphRagQuery
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Service

@Service
class GraphMeshMcpTools(
    private val graphRagService: GraphRagService,
    private val documentRagService: DocumentRagService,
    private val collectionService: CollectionService,
    private val librarianService: LibrarianService
) {

    @McpTool(description = "Query the knowledge graph using Graph RAG. Finds relevant entities and relationships and generates a source-based answer.")
    fun knowledgeQuery(
        @McpToolParam(description = "The natural language question") question: String,
        @McpToolParam(description = "The collection ID to search in") collectionId: String,
        @McpToolParam(description = "Maximum number of edges in subgraph (default: 150)", required = false) maxEdges: Int?
    ): String {
        val result = graphRagService.query(
            GraphRagQuery(
                question = question,
                collectionId = collectionId,
                maxEdges = maxEdges ?: 150
            )
        )

        val sources = result.selectedEdges.joinToString("\n") { edge ->
            "- ${edge.subject} --[${edge.predicate}]--> ${edge.objectValue} (Reasoning: ${edge.reasoning})"
        }

        return "${result.answer}\n\n--- Sources (${result.selectedEdges.size} edges from ${result.retrievedEdgeCount} retrieved) ---\n$sources"
    }

    @McpTool(description = "Query documents using Document RAG. Searches semantically similar text chunks and generates an answer with source citations.")
    fun documentQuery(
        @McpToolParam(description = "The natural language question") question: String,
        @McpToolParam(description = "The collection ID to search in") collectionId: String,
        @McpToolParam(description = "Number of chunks to retrieve (default: 10)", required = false) topK: Int?
    ): String {
        val result = documentRagService.query(
            DocumentRagQuery(
                question = question,
                collectionId = collectionId,
                topK = topK ?: 10
            )
        )

        val sources = result.sources.joinToString("\n") { src ->
            "- ${src.documentTitle} (page ${src.pageNumber ?: "?"}, score: ${"%.2f".format(src.score)}): ${src.snippet}"
        }

        return "${result.answer}\n\n--- Sources (${result.sources.size} chunks) ---\n$sources"
    }

    @McpTool(description = "List all available collections with name, description and tags.")
    fun collectionList(
        @McpToolParam(description = "Comma-separated tags to filter by (optional)", required = false) tags: String?
    ): String {
        val tagSet = tags?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val collections = collectionService.findAll(tagSet)

        if (collections.isEmpty()) return "No collections found."

        return collections.joinToString("\n") { col ->
            "- ${col.name} (ID: ${col.id}): ${col.description} [Tags: ${col.tags.joinToString(", ")}]"
        }
    }

    @McpTool(description = "Search documents in a collection by title.")
    fun documentSearch(
        @McpToolParam(description = "The collection ID to search in") collectionId: String,
        @McpToolParam(description = "Title search term (optional)", required = false) titleFilter: String?
    ): String {
        val documents = librarianService.findByCollection(collectionId)

        val filtered = if (titleFilter != null) {
            documents.filter { it.title.contains(titleFilter, ignoreCase = true) }
        } else {
            documents
        }

        if (filtered.isEmpty()) return "No documents found."

        return filtered.joinToString("\n") { doc ->
            "- ${doc.title} (ID: ${doc.id}, type: ${doc.type}, state: ${doc.state})"
        }
    }
}
