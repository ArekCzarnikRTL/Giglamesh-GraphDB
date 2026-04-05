package com.agentwork.graphmesh.agent

import org.springframework.stereotype.Component

@Component
class ToolGroupRegistry {

    private val groups = listOf(
        ToolGroup(
            name = "all",
            description = "All available tools",
            toolNames = setOf("knowledge_query", "document_query")
        ),
        ToolGroup(
            name = "basic",
            description = "Basic query tools (knowledge graph only)",
            toolNames = setOf("knowledge_query")
        ),
        ToolGroup(
            name = "advanced",
            description = "Advanced tools (knowledge graph + document search)",
            toolNames = setOf("knowledge_query", "document_query")
        ),
        ToolGroup(
            name = "read-only",
            description = "Read-only tools (no write operations)",
            toolNames = setOf("knowledge_query", "document_query")
        )
    )

    fun getGroups(): List<ToolGroup> = groups

    fun resolveToolNames(allowedGroups: Set<String>): Set<String> {
        if (allowedGroups.isEmpty() || "all" in allowedGroups) {
            return groups.first { it.name == "all" }.toolNames
        }
        return groups
            .filter { it.name in allowedGroups }
            .flatMap { it.toolNames }
            .toSet()
    }

    fun getGroupsForTool(toolName: String): List<String> {
        return groups.filter { toolName in it.toolNames }.map { it.name }
    }
}
