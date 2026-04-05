# Feature 25: Agent System — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a general-purpose ReAct query agent that answers complex user questions by iteratively querying GraphRAG and DocumentRAG, exposed via GraphQL.

**Architecture:** Koog `AIAgent` with `reActStrategy` and 2 tools (knowledge_query, document_query). Users call `askAgent` GraphQL mutation, the agent iterates through tools, returns a final answer. Separate endpoint from NlpQueryService.

**Tech Stack:** Kotlin, Spring Boot, Koog AIAgent + reActStrategy + SimpleTool, Spring GraphQL (@Controller, @MutationMapping, @QueryMapping), MockK (tests)

**Spec:** `docs/superpowers/specs/2026-04-05-agent-system-design.md`

---

## File Structure

| File | Responsibility |
|------|---------------|
| `src/main/kotlin/com/agentwork/graphmesh/agent/Models.kt` | AgentQueryConfig, AgentQueryResult, ToolInfo |
| `src/main/kotlin/com/agentwork/graphmesh/agent/AgentQueryTools.kt` | 2 Koog SimpleTool: KnowledgeQueryTool, DocumentQueryTool |
| `src/main/kotlin/com/agentwork/graphmesh/agent/AgentService.kt` | @Service — Creates AIAgent, runs query, returns result |
| `src/main/resources/graphql/agent.graphqls` | GraphQL schema for agent endpoint |
| `src/main/kotlin/com/agentwork/graphmesh/api/AgentController.kt` | @Controller — GraphQL mutations and queries |
| `src/test/kotlin/com/agentwork/graphmesh/agent/AgentQueryToolsTest.kt` | Tests for tool execute() methods |
| `src/test/kotlin/com/agentwork/graphmesh/agent/AgentServiceTest.kt` | Tests for AgentService |
| `src/test/kotlin/com/agentwork/graphmesh/api/AgentControllerTest.kt` | Tests for GraphQL controller |

---

### Task 1: Models

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/agent/Models.kt`

- [ ] **Step 1: Create Models.kt with data classes**

```kotlin
package com.agentwork.graphmesh.agent

data class AgentQueryConfig(
    val maxIterations: Int = 10,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """Du bist ein Wissensassistent. Beantworte die Frage des Benutzers.
Verwende die verfuegbaren Tools um Informationen zu sammeln:
- knowledge_query: Frage den Knowledge Graph ab
- document_query: Suche in Dokumenten
Wenn du genug Informationen hast, gib eine ausfuehrliche Antwort."""
    }
}

data class AgentQueryResult(
    val answer: String,
    val durationMs: Long
)

data class ToolInfo(
    val name: String,
    val description: String
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/agent/Models.kt
git commit -m "feat(agent-system): add data models for agent query system"
```

---

### Task 2: AgentQueryTools + Tests

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/agent/AgentQueryToolsTest.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/agent/AgentQueryTools.kt`

- [ ] **Step 1: Write failing tests for agent query tools**

```kotlin
package com.agentwork.graphmesh.agent

import com.agentwork.graphmesh.query.docrag.DocumentRagQuery
import com.agentwork.graphmesh.query.docrag.DocumentRagResult
import com.agentwork.graphmesh.query.docrag.DocumentRagService
import com.agentwork.graphmesh.query.docrag.SourceAttribution
import com.agentwork.graphmesh.query.graphrag.GraphRagQuery
import com.agentwork.graphmesh.query.graphrag.GraphRagResult
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import com.agentwork.graphmesh.query.graphrag.SelectedEdge
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertContains

class AgentQueryToolsTest {

    @Test
    fun `KnowledgeQueryTool returns answer with sources`() = runBlocking {
        val graphRagService = mockk<GraphRagService>()
        every { graphRagService.query(any()) } returns GraphRagResult(
            answer = "Plants perform photosynthesis.",
            selectedEdges = listOf(
                SelectedEdge(
                    subject = "Plants",
                    predicate = "perform",
                    objectValue = "Photosynthesis",
                    dataset = "",
                    reasoning = "Direct relationship",
                    relevanceScore = 0.95
                )
            ),
            retrievedEdgeCount = 10,
            durationMs = 500
        )

        val tool = KnowledgeQueryTool(graphRagService, "col-1")
        val result = tool.execute(KnowledgeQueryTool.Args(question = "What do plants do?"))

        assertContains(result, "Plants perform photosynthesis.")
        assertContains(result, "Plants")
        assertContains(result, "Photosynthesis")
    }

    @Test
    fun `DocumentQueryTool returns answer with sources`() = runBlocking {
        val documentRagService = mockk<DocumentRagService>()
        every { documentRagService.query(any()) } returns DocumentRagResult(
            answer = "Photosynthesis is described in chapter 3.",
            sources = listOf(
                SourceAttribution(
                    chunkId = "chunk-1",
                    documentId = "doc-1",
                    documentTitle = "Biology Book",
                    pageNumber = 42,
                    score = 0.9f,
                    snippet = "Photosynthesis is the process..."
                )
            ),
            retrievedChunkCount = 5,
            durationMs = 300
        )

        val tool = DocumentQueryTool(documentRagService, "col-1")
        val result = tool.execute(DocumentQueryTool.Args(question = "Tell me about photosynthesis"))

        assertContains(result, "Photosynthesis is described in chapter 3.")
        assertContains(result, "Biology Book")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.agent.AgentQueryToolsTest"`
Expected: FAIL — tool classes not found

- [ ] **Step 3: Create AgentQueryTools.kt**

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.agent.AgentQueryToolsTest"`
Expected: All 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/agent/AgentQueryTools.kt \
        src/test/kotlin/com/agentwork/graphmesh/agent/AgentQueryToolsTest.kt
git commit -m "feat(agent-system): add KnowledgeQueryTool and DocumentQueryTool"
```

---

### Task 3: AgentService + Tests

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/agent/AgentServiceTest.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/agent/AgentService.kt`

- [ ] **Step 1: Write tests for AgentService**

```kotlin
package com.agentwork.graphmesh.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentServiceTest {

    @Test
    fun `getAvailableTools returns knowledge and document tools`() {
        val tools = AgentService.AVAILABLE_TOOLS
        assertEquals(2, tools.size)
        assertTrue(tools.any { it.name == "knowledge_query" })
        assertTrue(tools.any { it.name == "document_query" })
    }

    @Test
    fun `getAvailableTools has descriptions`() {
        val tools = AgentService.AVAILABLE_TOOLS
        assertTrue(tools.all { it.description.isNotBlank() })
    }

    @Test
    fun `DEFAULT_CONFIG has reasonable defaults`() {
        val config = AgentQueryConfig()
        assertEquals(10, config.maxIterations)
        assertTrue(config.systemPrompt.contains("knowledge_query"))
        assertTrue(config.systemPrompt.contains("document_query"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.agent.AgentServiceTest"`
Expected: FAIL — `AgentService` class not found

- [ ] **Step 3: Create AgentService.kt**

```kotlin
package com.agentwork.graphmesh.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.reActStrategy
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.query.docrag.DocumentRagService
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AgentService(
    private val promptExecutor: PromptExecutor,
    private val graphRagService: GraphRagService,
    private val documentRagService: DocumentRagService,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val modelName: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        val AVAILABLE_TOOLS = listOf(
            ToolInfo(
                name = "knowledge_query",
                description = "Query the knowledge graph for entities and relationships."
            ),
            ToolInfo(
                name = "document_query",
                description = "Search documents for relevant text passages."
            )
        )
    }

    fun query(
        question: String,
        collectionId: String,
        config: AgentQueryConfig = AgentQueryConfig()
    ): AgentQueryResult {
        val startTime = System.currentTimeMillis()

        val toolRegistry = ToolRegistry {
            tool(KnowledgeQueryTool(graphRagService, collectionId))
            tool(DocumentQueryTool(documentRagService, collectionId))
        }

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = LLModel(LLMProvider.OpenAI, modelName),
            strategy = reActStrategy(reasoningInterval = 1, name = "query_agent"),
            toolRegistry = toolRegistry,
            systemPrompt = config.systemPrompt
        )

        val answer = runBlocking {
            agent.run(question)
        }

        val durationMs = System.currentTimeMillis() - startTime

        logger.info(
            "Agent query complete: question='{}', durationMs={}",
            question.take(80), durationMs
        )

        return AgentQueryResult(
            answer = answer,
            durationMs = durationMs
        )
    }

    fun getAvailableTools(): List<ToolInfo> = AVAILABLE_TOOLS
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.agent.AgentServiceTest"`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/agent/AgentService.kt \
        src/test/kotlin/com/agentwork/graphmesh/agent/AgentServiceTest.kt
git commit -m "feat(agent-system): add AgentService with Koog AIAgent integration"
```

---

### Task 4: GraphQL Schema + Controller + Tests

**Files:**
- Create: `src/main/resources/graphql/agent.graphqls`
- Create: `src/test/kotlin/com/agentwork/graphmesh/api/AgentControllerTest.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/api/AgentController.kt`

- [ ] **Step 1: Create GraphQL schema**

```graphql
extend type Query {
    agentTools: [ToolInfo!]!
}

extend type Mutation {
    askAgent(input: AgentQueryInput!): AgentQueryResult!
}

input AgentQueryInput {
    question: String!
    collectionId: ID!
    maxIterations: Int = 10
}

type AgentQueryResult {
    answer: String!
    durationMs: Int!
}

type ToolInfo {
    name: String!
    description: String!
}
```

- [ ] **Step 2: Write failing tests for AgentController**

```kotlin
package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.agent.AgentQueryResult
import com.agentwork.graphmesh.agent.AgentService
import com.agentwork.graphmesh.agent.ToolInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AgentControllerTest {

    @Test
    fun `askAgent delegates to agent service`() {
        val agentService = mockk<AgentService>()
        every { agentService.query("What is X?", "col-1", any()) } returns AgentQueryResult(
            answer = "X is a concept.",
            durationMs = 1500
        )

        val controller = AgentController(agentService)
        val input = AgentQueryInput(question = "What is X?", collectionId = "col-1", maxIterations = null)

        val result = controller.askAgent(input)

        assertEquals("X is a concept.", result.answer)
        assertEquals(1500, result.durationMs)
        verify { agentService.query("What is X?", "col-1", any()) }
    }

    @Test
    fun `askAgent passes custom maxIterations`() {
        val agentService = mockk<AgentService>()
        every { agentService.query(any(), any(), any()) } returns AgentQueryResult(
            answer = "Answer", durationMs = 100
        )

        val controller = AgentController(agentService)
        val input = AgentQueryInput(question = "Q", collectionId = "col-1", maxIterations = 5)

        controller.askAgent(input)

        verify { agentService.query("Q", "col-1", match { it.maxIterations == 5 }) }
    }

    @Test
    fun `agentTools returns available tools`() {
        val agentService = mockk<AgentService>()
        every { agentService.getAvailableTools() } returns listOf(
            ToolInfo("knowledge_query", "Query the knowledge graph"),
            ToolInfo("document_query", "Search documents")
        )

        val controller = AgentController(agentService)
        val tools = controller.agentTools()

        assertEquals(2, tools.size)
        assertEquals("knowledge_query", tools[0].name)
        assertEquals("document_query", tools[1].name)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.api.AgentControllerTest"`
Expected: FAIL — `AgentController` class not found

- [ ] **Step 4: Create AgentController.kt**

```kotlin
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
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.api.AgentControllerTest"`
Expected: All 3 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/graphql/agent.graphqls \
        src/main/kotlin/com/agentwork/graphmesh/api/AgentController.kt \
        src/test/kotlin/com/agentwork/graphmesh/api/AgentControllerTest.kt
git commit -m "feat(agent-system): add GraphQL endpoint for agent queries"
```

---

### Task 5: Full Build Verification

- [ ] **Step 1: Run all agent tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.agent.*" --tests "com.agentwork.graphmesh.api.AgentControllerTest"`
Expected: BUILD SUCCESSFUL — all 8 tests pass

- [ ] **Step 2: Run full compilation**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify all new files are committed**

Run: `git status`
Expected: Clean working tree for new agent files
