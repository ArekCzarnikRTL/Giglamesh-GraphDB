package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.agent.AgentQueryConfig
import com.agentwork.graphmesh.streaming.StreamToken
import com.agentwork.graphmesh.streaming.StreamingAgentService
import kotlinx.coroutines.flow.Flow
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.SubscriptionMapping
import org.springframework.stereotype.Controller

@Controller
class StreamingController(
    private val streamingAgentService: StreamingAgentService
) {

    @SubscriptionMapping
    fun agentStream(@Argument input: AgentStreamInput): Flow<StreamToken> {
        val config = AgentQueryConfig(
            maxIterations = input.maxIterations ?: 10
        )
        val allowedGroups = input.allowedGroups?.toSet() ?: setOf("all")
        return streamingAgentService.queryStreaming(
            input.question, input.collectionId, config, allowedGroups
        )
    }
}

data class AgentStreamInput(
    val question: String,
    val collectionId: String,
    val maxIterations: Int?,
    val allowedGroups: List<String>?
)
