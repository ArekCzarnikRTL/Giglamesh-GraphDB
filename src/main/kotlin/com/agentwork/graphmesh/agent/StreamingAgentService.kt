package com.agentwork.graphmesh.agent

import kotlinx.coroutines.flow.Flow

interface StreamingAgentService {
    fun queryStreaming(
        question: String,
        collectionId: String,
        config: AgentQueryConfig,
        allowedGroups: Set<String>
    ): Flow<StreamToken>
}
