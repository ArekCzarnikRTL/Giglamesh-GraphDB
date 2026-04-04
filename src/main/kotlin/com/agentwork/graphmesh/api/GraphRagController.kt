package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.query.graphrag.GraphRagQuery
import com.agentwork.graphmesh.query.graphrag.GraphRagResult
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class GraphRagController(
    private val graphRagService: GraphRagService
) {

    @QueryMapping
    fun graphRag(@Argument input: GraphRagInput): GraphRagResult {
        val query = GraphRagQuery(
            question = input.question,
            collectionId = input.collectionId,
            maxEdges = input.maxEdges ?: 150,
            maxDepth = input.maxDepth ?: 2,
            maxSelectedEdges = input.maxSelectedEdges ?: 30
        )
        return graphRagService.query(query)
    }
}

data class GraphRagInput(
    val question: String,
    val collectionId: String,
    val maxEdges: Int?,
    val maxDepth: Int?,
    val maxSelectedEdges: Int?
)
