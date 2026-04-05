# Feature 24: Agent-based Extractor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a ReAct-style extraction agent using Koog's AIAgent that iteratively extracts knowledge from text chunks, consults the knowledge graph, and stores results as RDF quads.

**Architecture:** Koog `AIAgent` with `reActStrategy` and 3 custom tools (graph-query, validate-entity, context-expand). The agent outputs JSONL which is parsed into `ExtractedItem` objects, converted to RDF `Quad`s, and stored via `QuadStore`. Triggered by Kafka `chunk.created` events.

**Tech Stack:** Kotlin, Spring Boot, Koog AIAgent (ReAct strategy), Koog SimpleTool, Jackson (JSONL parsing), Spring Kafka (@KafkaListener, Avro), MockK (tests)

**Spec:** `docs/superpowers/specs/2026-04-05-agent-based-extractor-design.md`

---

## File Structure

| File | Responsibility |
|------|---------------|
| `src/main/kotlin/com/agentwork/graphmesh/extraction/agent/Models.kt` | Data classes: ExtractionStrategy, OutputType, ExtractedItem (sealed), AgentExtractionResult |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/agent/ExtractionTools.kt` | 3 Koog SimpleTool definitions: GraphQueryTool, ValidateEntityTool, ContextExpandTool |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/agent/AgentExtractorService.kt` | @Service — Creates AIAgent, parses JSONL, converts to quads, stores |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/agent/AgentExtractorConsumer.kt` | @Component — Kafka consumer for chunk.created |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/agent/ExtractionToolsTest.kt` | Tests for tool execute() methods |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/agent/AgentExtractorServiceTest.kt` | Tests for JSONL parsing, quad conversion, E2E extract() |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/agent/AgentExtractorConsumerTest.kt` | Tests for Kafka consumer delegation |

---

### Task 1: Models

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/agent/Models.kt`

- [ ] **Step 1: Create Models.kt with data classes**

```kotlin
package com.agentwork.graphmesh.extraction.agent

data class ExtractionStrategy(
    val name: String,
    val systemPrompt: String,
    val maxIterations: Int = 5,
    val outputTypes: List<OutputType> = listOf(OutputType.RELATIONSHIP, OutputType.DEFINITION)
)

enum class OutputType { DEFINITION, RELATIONSHIP, ENTITY, ATTRIBUTE }

sealed class ExtractedItem {
    data class Definition(val entity: String, val definition: String) : ExtractedItem()
    data class Relationship(
        val subject: String,
        val predicate: String,
        val objectValue: String,
        val objectIsEntity: Boolean = true
    ) : ExtractedItem()
    data class Entity(val name: String, val entityType: String? = null) : ExtractedItem()
    data class Attribute(val entity: String, val attribute: String, val value: String) : ExtractedItem()
}

data class AgentExtractionResult(
    val chunkId: String,
    val extractedItems: List<ExtractedItem>,
    val strategy: String
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/agent/Models.kt
git commit -m "feat(agent-extractor): add data models for agent-based extraction"
```

---

### Task 2: ExtractionTools + Tests

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/extraction/agent/ExtractionToolsTest.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/agent/ExtractionTools.kt`

- [ ] **Step 1: Write failing tests for extraction tools**

```kotlin
package com.agentwork.graphmesh.extraction.agent

import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.query.graphrag.GraphRagQuery
import com.agentwork.graphmesh.query.graphrag.GraphRagResult
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import com.agentwork.graphmesh.query.graphrag.SelectedEdge
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import com.agentwork.graphmesh.storage.ObjectType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtractionToolsTest {

    @Test
    fun `GraphQueryTool queries graph and returns answer with sources`() = runBlocking {
        val graphRagService = mockk<GraphRagService>()
        every { graphRagService.query(any()) } returns GraphRagResult(
            answer = "Photosynthesis converts sunlight to energy.",
            selectedEdges = listOf(
                SelectedEdge(
                    subject = "Photosynthesis",
                    predicate = "converts",
                    objectValue = "sunlight",
                    reasoning = "Direct relationship"
                )
            ),
            retrievedEdgeCount = 10
        )

        val tool = GraphQueryTool(graphRagService, "col-1")
        val result = tool.execute(GraphQueryTool.Args(question = "What is photosynthesis?"))

        assertContains(result, "Photosynthesis converts sunlight to energy.")
        assertContains(result, "Photosynthesis")
    }

    @Test
    fun `ValidateEntityTool returns EXISTS when entity found`() = runBlocking {
        val quadStore = mockk<QuadStore>()
        val entityId = EntityIdGenerator.generate("Photosynthesis").value
        every { quadStore.findByEntities("col-1", listOf(entityId)) } returns listOf(
            StoredQuad(
                subject = entityId,
                predicate = "http://www.w3.org/2000/01/rdf-schema#label",
                objectValue = "Photosynthesis",
                dataset = "",
                objectType = ObjectType.LITERAL
            )
        )

        val tool = ValidateEntityTool(quadStore, "col-1")
        val result = tool.execute(ValidateEntityTool.Args(entityName = "Photosynthesis"))

        assertContains(result, "EXISTS")
        assertContains(result, "rdfs#label")
    }

    @Test
    fun `ValidateEntityTool returns NOT_FOUND when entity missing`() = runBlocking {
        val quadStore = mockk<QuadStore>()
        val entityId = EntityIdGenerator.generate("Unknown").value
        every { quadStore.findByEntities("col-1", listOf(entityId)) } returns emptyList()

        val tool = ValidateEntityTool(quadStore, "col-1")
        val result = tool.execute(ValidateEntityTool.Args(entityName = "Unknown"))

        assertEquals("NOT_FOUND", result)
    }

    @Test
    fun `ContextExpandTool returns sibling chunk texts`() = runBlocking {
        val librarianService = mockk<LibrarianService>()

        val chunk = Document(
            id = "chunk-1", collectionId = "col-1", parentId = "page-1",
            type = DocumentType.CHUNK, title = "Chunk 1"
        )
        val sibling = Document(
            id = "chunk-2", collectionId = "col-1", parentId = "page-1",
            type = DocumentType.CHUNK, title = "Chunk 2"
        )

        every { librarianService.findById("chunk-1") } returns chunk
        every { librarianService.findChildren("page-1") } returns listOf(chunk, sibling)
        every { librarianService.getContent("chunk-2") } returns "Sibling chunk text.".toByteArray()

        val tool = ContextExpandTool(librarianService, "chunk-1")
        val result = tool.execute(ContextExpandTool.Args(reason = "need more context"))

        assertContains(result, "Sibling chunk text.")
        assertContains(result, "chunk-2")
    }

    @Test
    fun `ContextExpandTool returns empty message when no parent`() = runBlocking {
        val librarianService = mockk<LibrarianService>()

        val chunk = Document(
            id = "chunk-1", collectionId = "col-1", parentId = null,
            type = DocumentType.CHUNK, title = "Chunk 1"
        )
        every { librarianService.findById("chunk-1") } returns chunk

        val tool = ContextExpandTool(librarianService, "chunk-1")
        val result = tool.execute(ContextExpandTool.Args(reason = "need context"))

        assertContains(result, "No sibling chunks")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.agent.ExtractionToolsTest"`
Expected: FAIL — tool classes not found

- [ ] **Step 3: Create ExtractionTools.kt**

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.agent.ExtractionToolsTest"`
Expected: All 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/agent/ExtractionTools.kt \
        src/test/kotlin/com/agentwork/graphmesh/extraction/agent/ExtractionToolsTest.kt
git commit -m "feat(agent-extractor): add Koog extraction tools (graph-query, validate-entity, context-expand)"
```

---

### Task 3: AgentExtractorService + Tests

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/extraction/agent/AgentExtractorServiceTest.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/agent/AgentExtractorService.kt`

- [ ] **Step 1: Write failing tests for AgentExtractorService**

```kotlin
package com.agentwork.graphmesh.extraction.agent

import ai.koog.prompt.executor.model.PromptExecutor
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.QuadConverter
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentExtractorServiceTest {

    private val objectMapper = jacksonObjectMapper()

    // --- JSONL Parsing tests (standalone copy) ---

    @Test
    fun `parseFinalOutput parses relationship items`() {
        val response = """
            Some reasoning text...
            {"type": "relationship", "subject": "Photosynthesis", "predicate": "converts", "object": "sunlight", "object_entity": false}
        """.trimIndent()

        val items = parseFinalOutput(response)
        assertEquals(1, items.size)
        val rel = items[0] as ExtractedItem.Relationship
        assertEquals("Photosynthesis", rel.subject)
        assertEquals("converts", rel.predicate)
        assertEquals("sunlight", rel.objectValue)
        assertEquals(false, rel.objectIsEntity)
    }

    @Test
    fun `parseFinalOutput parses definition items`() {
        val response = """{"type": "definition", "entity": "Photosynthesis", "definition": "Process of converting sunlight"}"""

        val items = parseFinalOutput(response)
        assertEquals(1, items.size)
        val def = items[0] as ExtractedItem.Definition
        assertEquals("Photosynthesis", def.entity)
        assertEquals("Process of converting sunlight", def.definition)
    }

    @Test
    fun `parseFinalOutput parses entity items`() {
        val response = """{"type": "entity", "entity": "Chlorophyll", "entity_type": "molecule"}"""

        val items = parseFinalOutput(response)
        assertEquals(1, items.size)
        val ent = items[0] as ExtractedItem.Entity
        assertEquals("Chlorophyll", ent.name)
        assertEquals("molecule", ent.entityType)
    }

    @Test
    fun `parseFinalOutput parses attribute items`() {
        val response = """{"type": "attribute", "entity": "Chlorophyll", "attribute": "color", "value": "green"}"""

        val items = parseFinalOutput(response)
        assertEquals(1, items.size)
        val attr = items[0] as ExtractedItem.Attribute
        assertEquals("Chlorophyll", attr.entity)
        assertEquals("color", attr.attribute)
        assertEquals("green", attr.value)
    }

    @Test
    fun `parseFinalOutput parses mixed items and skips invalid lines`() {
        val response = """
            Some agent reasoning...
            {"type": "definition", "entity": "A", "definition": "Def A"}
            not json at all
            {"type": "relationship", "subject": "A", "predicate": "rel", "object": "B"}
            {"type": "unknown_type", "foo": "bar"}
        """.trimIndent()

        val items = parseFinalOutput(response)
        assertEquals(2, items.size)
        assertTrue(items[0] is ExtractedItem.Definition)
        assertTrue(items[1] is ExtractedItem.Relationship)
    }

    @Test
    fun `parseFinalOutput handles empty response`() {
        val items = parseFinalOutput("")
        assertTrue(items.isEmpty())
    }

    @Test
    fun `parseFinalOutput strips markdown code fences`() {
        val response = "```json\n{\"type\": \"entity\", \"entity\": \"Test\"}\n```"
        val items = parseFinalOutput(response)
        assertEquals(1, items.size)
    }

    @Test
    fun `parseFinalOutput defaults object_entity to true`() {
        val response = """{"type": "relationship", "subject": "A", "predicate": "rel", "object": "B"}"""
        val items = parseFinalOutput(response)
        val rel = items[0] as ExtractedItem.Relationship
        assertEquals(true, rel.objectIsEntity)
    }

    // --- Quad conversion tests ---

    @Test
    fun `convertToQuads converts Definition to knowledge and label quads`() {
        val item = ExtractedItem.Definition(entity = "Photosynthesis", definition = "Process of converting light")
        val quads = convertToQuads(item, "chunk-1")

        // knowledge + label + provenance = 3 quads
        assertEquals(3, quads.size)

        val knowledge = quads.first { it.predicate == "http://www.w3.org/2000/01/rdf-schema#comment" }
        assertEquals(EntityIdGenerator.generate("Photosynthesis").value, knowledge.subject)
        assertEquals("Process of converting light", knowledge.objectValue)
        assertEquals(ObjectType.LITERAL, knowledge.objectType)

        val label = quads.first { it.predicate == "http://www.w3.org/2000/01/rdf-schema#label" }
        assertEquals("Photosynthesis", label.objectValue)

        val provenance = quads.first { it.dataset == NamedGraph.SOURCE }
        assertEquals("urn:chunk:chunk-1", provenance.objectValue)
    }

    @Test
    fun `convertToQuads converts Relationship with entity object`() {
        val item = ExtractedItem.Relationship(
            subject = "Plants", predicate = "perform", objectValue = "Photosynthesis", objectIsEntity = true
        )
        val quads = convertToQuads(item, "chunk-1")

        // relationship + provenance = 2 quads
        assertEquals(2, quads.size)

        val rel = quads.first { it.dataset == NamedGraph.DEFAULT }
        assertEquals(EntityIdGenerator.generate("Plants").value, rel.subject)
        assertEquals("http://graphmesh.io/ontology/perform", rel.predicate)
        assertEquals(EntityIdGenerator.generate("Photosynthesis").value, rel.objectValue)
        assertEquals(ObjectType.URI, rel.objectType)
    }

    @Test
    fun `convertToQuads converts Relationship with literal object`() {
        val item = ExtractedItem.Relationship(
            subject = "Earth", predicate = "age", objectValue = "4.5 billion years", objectIsEntity = false
        )
        val quads = convertToQuads(item, "chunk-1")

        val rel = quads.first { it.dataset == NamedGraph.DEFAULT }
        assertEquals("4.5 billion years", rel.objectValue)
        assertEquals(ObjectType.LITERAL, rel.objectType)
    }

    @Test
    fun `convertToQuads converts Entity to label quad`() {
        val item = ExtractedItem.Entity(name = "Chlorophyll")
        val quads = convertToQuads(item, "chunk-1")

        assertEquals(2, quads.size) // label + provenance
        val label = quads.first { it.dataset == NamedGraph.DEFAULT }
        assertEquals("http://www.w3.org/2000/01/rdf-schema#label", label.predicate)
        assertEquals("Chlorophyll", label.objectValue)
    }

    @Test
    fun `convertToQuads converts Attribute to literal quad`() {
        val item = ExtractedItem.Attribute(entity = "Chlorophyll", attribute = "color", value = "green")
        val quads = convertToQuads(item, "chunk-1")

        assertEquals(2, quads.size) // attribute + provenance
        val attr = quads.first { it.dataset == NamedGraph.DEFAULT }
        assertEquals("http://graphmesh.io/ontology/color", attr.predicate)
        assertEquals("green", attr.objectValue)
        assertEquals(ObjectType.LITERAL, attr.objectType)
    }

    // Standalone copy of JSONL parsing logic
    @Suppress("UNCHECKED_CAST")
    private fun parseFinalOutput(response: String): List<ExtractedItem> {
        return response.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val map = objectMapper.readValue<Map<String, Any?>>(line)
                    when (map["type"]) {
                        "definition" -> ExtractedItem.Definition(
                            entity = map["entity"] as String,
                            definition = map["definition"] as String
                        )
                        "relationship" -> ExtractedItem.Relationship(
                            subject = map["subject"] as String,
                            predicate = map["predicate"] as String,
                            objectValue = map["object"] as String,
                            objectIsEntity = map["object_entity"] as? Boolean ?: true
                        )
                        "entity" -> ExtractedItem.Entity(
                            name = map["entity"] as String,
                            entityType = map["entity_type"] as? String
                        )
                        "attribute" -> ExtractedItem.Attribute(
                            entity = map["entity"] as String,
                            attribute = map["attribute"] as String,
                            value = map["value"] as String
                        )
                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }
            }
    }

    // Standalone copy of quad conversion logic
    private fun convertToQuads(item: ExtractedItem, chunkId: String): List<StoredQuad> {
        val knowledgeQuads = when (item) {
            is ExtractedItem.Definition -> listOf(
                com.agentwork.graphmesh.rdf.Quad(
                    subject = EntityIdGenerator.generate(item.entity),
                    predicate = com.agentwork.graphmesh.rdf.RdfTerm.Uri("http://www.w3.org/2000/01/rdf-schema#comment"),
                    objectTerm = com.agentwork.graphmesh.rdf.RdfTerm.Literal(item.definition),
                    graph = NamedGraph.DEFAULT
                ),
                com.agentwork.graphmesh.rdf.Quad(
                    subject = EntityIdGenerator.generate(item.entity),
                    predicate = com.agentwork.graphmesh.rdf.RdfTerm.Uri("http://www.w3.org/2000/01/rdf-schema#label"),
                    objectTerm = com.agentwork.graphmesh.rdf.RdfTerm.Literal(item.entity),
                    graph = NamedGraph.DEFAULT
                )
            )
            is ExtractedItem.Relationship -> {
                val objectTerm = if (item.objectIsEntity) {
                    EntityIdGenerator.generate(item.objectValue)
                } else {
                    com.agentwork.graphmesh.rdf.RdfTerm.Literal(item.objectValue)
                }
                listOf(
                    com.agentwork.graphmesh.rdf.Quad(
                        subject = EntityIdGenerator.generate(item.subject),
                        predicate = com.agentwork.graphmesh.rdf.RdfTerm.Uri("http://graphmesh.io/ontology/${item.predicate}"),
                        objectTerm = objectTerm,
                        graph = NamedGraph.DEFAULT
                    )
                )
            }
            is ExtractedItem.Entity -> listOf(
                com.agentwork.graphmesh.rdf.Quad(
                    subject = EntityIdGenerator.generate(item.name),
                    predicate = com.agentwork.graphmesh.rdf.RdfTerm.Uri("http://www.w3.org/2000/01/rdf-schema#label"),
                    objectTerm = com.agentwork.graphmesh.rdf.RdfTerm.Literal(item.name),
                    graph = NamedGraph.DEFAULT
                )
            )
            is ExtractedItem.Attribute -> listOf(
                com.agentwork.graphmesh.rdf.Quad(
                    subject = EntityIdGenerator.generate(item.entity),
                    predicate = com.agentwork.graphmesh.rdf.RdfTerm.Uri("http://graphmesh.io/ontology/${item.attribute}"),
                    objectTerm = com.agentwork.graphmesh.rdf.RdfTerm.Literal(item.value),
                    graph = NamedGraph.DEFAULT
                )
            )
        }

        val provenanceQuads = knowledgeQuads.map { quad ->
            com.agentwork.graphmesh.rdf.Quad(
                subject = com.agentwork.graphmesh.rdf.RdfTerm.QuotedTriple(quad.triple),
                predicate = com.agentwork.graphmesh.rdf.RdfTerm.Uri("http://graphmesh.io/ontology/extractedFrom"),
                objectTerm = com.agentwork.graphmesh.rdf.RdfTerm.Uri("urn:chunk:$chunkId"),
                graph = NamedGraph.SOURCE
            )
        }

        return (knowledgeQuads + provenanceQuads).map { QuadConverter.toStoredQuad(it) }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.agent.AgentExtractorServiceTest"`
Expected: FAIL — tests use standalone parsing, but ExtractedItem class must exist from Task 1

- [ ] **Step 3: Create AgentExtractorService.kt**

```kotlin
package com.agentwork.graphmesh.extraction.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.reActStrategy
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.query.graphrag.GraphRagService
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.Quad
import com.agentwork.graphmesh.rdf.QuadConverter
import com.agentwork.graphmesh.rdf.RdfTerm
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AgentExtractorService(
    private val promptExecutor: PromptExecutor,
    private val graphRagService: GraphRagService,
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val modelName: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    companion object {
        private const val RDFS_COMMENT = "http://www.w3.org/2000/01/rdf-schema#comment"
        private const val RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label"
        private const val ONTOLOGY_NS = "http://graphmesh.io/ontology/"
        private const val EXTRACTED_FROM = "${ONTOLOGY_NS}extractedFrom"

        val DEFAULT_STRATEGY = ExtractionStrategy(
            name = "default-extraction",
            systemPrompt = """
                Du bist ein Wissensextraktions-Agent. Extrahiere Wissen aus dem gegebenen Text.

                Verwende die verfuegbaren Tools um:
                1. Zu pruefen, ob Entitaeten bereits im Graph existieren (validate_entity)
                2. Existierende Beziehungen zu konsultieren (graph_query)
                3. Den Kontext bei Bedarf zu erweitern (context_expand)

                Wenn du fertig bist, gib die extrahierten Ergebnisse im JSONL-Format aus.
                Jede Zeile ist ein JSON-Objekt mit einem "type"-Feld:
                {"type": "relationship", "subject": "...", "predicate": "...", "object": "...", "object_entity": true}
                {"type": "definition", "entity": "...", "definition": "..."}
                {"type": "entity", "entity": "...", "entity_type": "..."}
                {"type": "attribute", "entity": "...", "attribute": "...", "value": "..."}

                Ziel: Hochqualitative, nicht-redundante Triples.
            """.trimIndent(),
            maxIterations = 5
        )
    }

    fun extract(chunkId: String, collectionId: String): AgentExtractionResult {
        return extract(chunkId, collectionId, DEFAULT_STRATEGY)
    }

    fun extract(chunkId: String, collectionId: String, strategy: ExtractionStrategy): AgentExtractionResult {
        val content = librarianService.getContent(chunkId)
        val chunkText = String(content, Charsets.UTF_8)

        if (chunkText.isBlank()) {
            return AgentExtractionResult(chunkId = chunkId, extractedItems = emptyList(), strategy = strategy.name)
        }

        val toolRegistry = ToolRegistry {
            tool(GraphQueryTool(graphRagService, collectionId))
            tool(ValidateEntityTool(quadStore, collectionId))
            tool(ContextExpandTool(librarianService, chunkId))
        }

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = LLModel(LLMProvider.OpenAI, modelName),
            strategy = reActStrategy(reasoningInterval = 1, name = "extraction_agent"),
            toolRegistry = toolRegistry,
            systemPrompt = strategy.systemPrompt
        )

        val agentResult = runBlocking {
            agent.run("Extrahiere Wissen aus folgendem Text:\n\n$chunkText")
        }

        val items = parseFinalOutput(agentResult)

        val storedQuads = items.flatMap { convertToQuads(it, chunkId) }
        if (storedQuads.isNotEmpty()) {
            quadStore.insertBatch(collectionId, storedQuads)
        }

        logger.info(
            "Agent extraction complete: chunkId={}, items={}, strategy={}",
            chunkId, items.size, strategy.name
        )

        return AgentExtractionResult(
            chunkId = chunkId,
            extractedItems = items,
            strategy = strategy.name
        )
    }

    @Suppress("UNCHECKED_CAST")
    internal fun parseFinalOutput(response: String): List<ExtractedItem> {
        return response.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val map = objectMapper.readValue<Map<String, Any?>>(line)
                    when (map["type"]) {
                        "definition" -> ExtractedItem.Definition(
                            entity = map["entity"] as String,
                            definition = map["definition"] as String
                        )
                        "relationship" -> ExtractedItem.Relationship(
                            subject = map["subject"] as String,
                            predicate = map["predicate"] as String,
                            objectValue = map["object"] as String,
                            objectIsEntity = map["object_entity"] as? Boolean ?: true
                        )
                        "entity" -> ExtractedItem.Entity(
                            name = map["entity"] as String,
                            entityType = map["entity_type"] as? String
                        )
                        "attribute" -> ExtractedItem.Attribute(
                            entity = map["entity"] as String,
                            attribute = map["attribute"] as String,
                            value = map["value"] as String
                        )
                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }
            }
    }

    internal fun convertToQuads(item: ExtractedItem, chunkId: String): List<StoredQuad> {
        val knowledgeQuads = when (item) {
            is ExtractedItem.Definition -> listOf(
                Quad(
                    subject = EntityIdGenerator.generate(item.entity),
                    predicate = RdfTerm.Uri(RDFS_COMMENT),
                    objectTerm = RdfTerm.Literal(item.definition),
                    graph = NamedGraph.DEFAULT
                ),
                Quad(
                    subject = EntityIdGenerator.generate(item.entity),
                    predicate = RdfTerm.Uri(RDFS_LABEL),
                    objectTerm = RdfTerm.Literal(item.entity),
                    graph = NamedGraph.DEFAULT
                )
            )
            is ExtractedItem.Relationship -> {
                val objectTerm = if (item.objectIsEntity) {
                    EntityIdGenerator.generate(item.objectValue)
                } else {
                    RdfTerm.Literal(item.objectValue)
                }
                listOf(
                    Quad(
                        subject = EntityIdGenerator.generate(item.subject),
                        predicate = RdfTerm.Uri("${ONTOLOGY_NS}${item.predicate}"),
                        objectTerm = objectTerm,
                        graph = NamedGraph.DEFAULT
                    )
                )
            }
            is ExtractedItem.Entity -> listOf(
                Quad(
                    subject = EntityIdGenerator.generate(item.name),
                    predicate = RdfTerm.Uri(RDFS_LABEL),
                    objectTerm = RdfTerm.Literal(item.name),
                    graph = NamedGraph.DEFAULT
                )
            )
            is ExtractedItem.Attribute -> listOf(
                Quad(
                    subject = EntityIdGenerator.generate(item.entity),
                    predicate = RdfTerm.Uri("${ONTOLOGY_NS}${item.attribute}"),
                    objectTerm = RdfTerm.Literal(item.value),
                    graph = NamedGraph.DEFAULT
                )
            )
        }

        val provenanceQuads = knowledgeQuads.map { quad ->
            Quad(
                subject = RdfTerm.QuotedTriple(quad.triple),
                predicate = RdfTerm.Uri(EXTRACTED_FROM),
                objectTerm = RdfTerm.Uri("urn:chunk:$chunkId"),
                graph = NamedGraph.SOURCE
            )
        }

        return (knowledgeQuads + provenanceQuads).map { QuadConverter.toStoredQuad(it) }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.agent.AgentExtractorServiceTest"`
Expected: All 13 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/agent/AgentExtractorService.kt \
        src/test/kotlin/com/agentwork/graphmesh/extraction/agent/AgentExtractorServiceTest.kt
git commit -m "feat(agent-extractor): add AgentExtractorService with JSONL parsing and quad conversion"
```

---

### Task 4: AgentExtractorConsumer + Tests

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/extraction/agent/AgentExtractorConsumerTest.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/extraction/agent/AgentExtractorConsumer.kt`

- [ ] **Step 1: Write failing tests for the consumer**

```kotlin
package com.agentwork.graphmesh.extraction.agent

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test

class AgentExtractorConsumerTest {

    @Test
    fun `handle delegates to extractor service`() {
        val extractorService = mockk<AgentExtractorService>(relaxed = true)
        val consumer = AgentExtractorConsumer(extractorService)

        val genericRecord = mockk<GenericRecord>()
        every { genericRecord["chunkId"] } returns "chunk-1"
        every { genericRecord["collectionId"] } returns "col-1"

        val record = ConsumerRecord<String, GenericRecord>("graphmesh.chunk.created", 0, 0L, "key", genericRecord)
        consumer.handle(record)

        verify { extractorService.extract("chunk-1", "col-1") }
    }

    @Test
    fun `handle catches and logs exceptions`() {
        val extractorService = mockk<AgentExtractorService>()
        val consumer = AgentExtractorConsumer(extractorService)

        every { extractorService.extract(any(), any()) } throws RuntimeException("Agent timeout")

        val genericRecord = mockk<GenericRecord>()
        every { genericRecord["chunkId"] } returns "chunk-fail"
        every { genericRecord["collectionId"] } returns "col-1"

        val record = ConsumerRecord<String, GenericRecord>("graphmesh.chunk.created", 0, 0L, "key", genericRecord)

        // Should not throw — exception is caught internally
        consumer.handle(record)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.agent.AgentExtractorConsumerTest"`
Expected: FAIL — `AgentExtractorConsumer` class not found

- [ ] **Step 3: Create AgentExtractorConsumer.kt**

```kotlin
package com.agentwork.graphmesh.extraction.agent

import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class AgentExtractorConsumer(
    private val extractorService: AgentExtractorService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.chunk.created"], groupId = "graphmesh-agent-extractor")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val chunkId = value["chunkId"].toString()
        val collectionId = value["collectionId"].toString()

        logger.info("Chunk received for agent extraction: chunkId={}", chunkId)

        try {
            val result = extractorService.extract(chunkId, collectionId)
            logger.info(
                "Agent extraction complete: chunkId={}, items={}, strategy={}",
                chunkId, result.extractedItems.size, result.strategy
            )
        } catch (e: Exception) {
            logger.error("Agent extraction failed for chunk {}", chunkId, e)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.agent.AgentExtractorConsumerTest"`
Expected: All 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/extraction/agent/AgentExtractorConsumer.kt \
        src/test/kotlin/com/agentwork/graphmesh/extraction/agent/AgentExtractorConsumerTest.kt
git commit -m "feat(agent-extractor): add Kafka consumer for chunk.created events"
```

---

### Task 5: Full Build Verification

- [ ] **Step 1: Run all agent-extractor tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.extraction.agent.*"`
Expected: BUILD SUCCESSFUL — all tests pass across 3 test classes

- [ ] **Step 2: Run full compilation**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify all new files are committed**

Run: `git status`
Expected: Clean working tree for the new agent files
