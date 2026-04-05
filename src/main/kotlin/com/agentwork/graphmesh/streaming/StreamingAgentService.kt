package com.agentwork.graphmesh.streaming

import com.agentwork.graphmesh.agent.AgentQueryConfig
import kotlinx.coroutines.flow.Flow

interface StreamingAgentService {
    fun queryStreaming(
        question: String,
        collectionId: String,
        config: AgentQueryConfig,
        allowedGroups: Set<String>
    ): Flow<StreamToken>
}
