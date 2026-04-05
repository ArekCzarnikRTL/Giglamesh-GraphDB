package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.agent.AgentQueryConfig
import com.agentwork.graphmesh.agent.AgentQueryResult
import com.agentwork.graphmesh.agent.AgentService
import com.agentwork.graphmesh.agent.ToolInfo
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class AgentController(
    private val agentService: AgentService
) {

    @MutationMapping
    fun askAgent(@Argument input: AgentQueryInput): AgentQueryResult {
        val config = AgentQueryConfig(
            maxIterations = input.maxIterations ?: 10
        )
        return agentService.query(input.question, input.collectionId, config)
    }

    @QueryMapping
    fun agentTools(): List<ToolInfo> = agentService.getAvailableTools()
}

data class AgentQueryInput(
    val question: String,
    val collectionId: String,
    val maxIterations: Int?
)
