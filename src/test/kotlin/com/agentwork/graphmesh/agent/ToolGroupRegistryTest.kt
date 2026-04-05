package com.agentwork.graphmesh.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolGroupRegistryTest {

    private val registry = ToolGroupRegistry()

    @Test
    fun `getGroups returns all predefined groups`() {
        val groups = registry.getGroups()
        assertEquals(4, groups.size)
        assertTrue(groups.any { it.name == "all" })
        assertTrue(groups.any { it.name == "basic" })
        assertTrue(groups.any { it.name == "advanced" })
        assertTrue(groups.any { it.name == "read-only" })
    }

    @Test
    fun `resolveToolNames with all returns both tools`() {
        val tools = registry.resolveToolNames(setOf("all"))
        assertEquals(setOf("knowledge_query", "document_query"), tools)
    }

    @Test
    fun `resolveToolNames with basic returns only knowledge_query`() {
        val tools = registry.resolveToolNames(setOf("basic"))
        assertEquals(setOf("knowledge_query"), tools)
    }

    @Test
    fun `resolveToolNames with advanced returns both tools`() {
        val tools = registry.resolveToolNames(setOf("advanced"))
        assertEquals(setOf("knowledge_query", "document_query"), tools)
    }

    @Test
    fun `resolveToolNames with empty set returns all tools`() {
        val tools = registry.resolveToolNames(emptySet())
        assertEquals(setOf("knowledge_query", "document_query"), tools)
    }

    @Test
    fun `resolveToolNames with unknown group returns empty`() {
        val tools = registry.resolveToolNames(setOf("nonexistent"))
        assertTrue(tools.isEmpty())
    }

    @Test
    fun `resolveToolNames with multiple groups returns union`() {
        val tools = registry.resolveToolNames(setOf("basic", "advanced"))
        assertEquals(setOf("knowledge_query", "document_query"), tools)
    }

    @Test
    fun `getGroupsForTool returns correct groups`() {
        val groups = registry.getGroupsForTool("knowledge_query")
        assertTrue(groups.contains("all"))
        assertTrue(groups.contains("basic"))
        assertTrue(groups.contains("advanced"))
        assertTrue(groups.contains("read-only"))
    }

    @Test
    fun `getGroupsForTool for document_query excludes basic`() {
        val groups = registry.getGroupsForTool("document_query")
        assertTrue(groups.contains("all"))
        assertTrue(groups.contains("advanced"))
        assertTrue(groups.contains("read-only"))
        assertTrue("basic" !in groups)
    }
}
