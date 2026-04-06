# Feature 24: Agent-based Extractor

## Problem

Einfache Single-Pass-Extraktoren (Feature 12, 19) senden den Text einmal an das LLM und akzeptieren das Ergebnis. Dies
fuehrt bei komplexen Texten zu unvollstaendiger Extraktion, da das LLM keine Moeglichkeit hat, den bestehenden Knowledge
Graph zu konsultieren, den Kontext zu erweitern oder seine Ergebnisse iterativ zu verfeinern. Ein agentenbasierter
Ansatz mit ReAct-Pattern (Reason + Act) ermoeglicht eine qualitativ hochwertigere Extraktion durch iterative
Verfeinerung und Kreuzreferenzierung mit dem existierenden Graphen.

## Ziel

Implementierung eines ReAct-Style Extraction Agents, der ueber MCP-Tools (Feature 17) iterativ Wissen aus Chunks
extrahiert, mit dem bestehenden Graph abgleicht und das Ergebnis im JSONL-Format ausgibt.

1. **ReAct-Agent-Loop** -- Iterativer Reasoning-Action-Observation-Zyklus fuer Wissensextraktion
2. **MCP-Tool-Integration** -- Agent nutzt MCP-Tools (Feature 17) zum Abfragen des Graphen, Kontext-Erweiterung und
   Validierung
3. **Konfigurierbare Strategien** -- Extraktionsstrategien ueber Prompts konfigurierbar
4. **Graph-Kreuzreferenzierung** -- Abgleich extrahierter Entitaeten mit dem bestehenden Knowledge Graph
5. **JSONL-Output** -- Strukturiertes, truncation-resistentes Ausgabeformat
6. **Kafka-Consumer** -- Empfaengt `chunk.created`-Events vom Chunker (Feature 11)

## Voraussetzungen

| Abhaengigkeit                                                             | Status     | Blocker? |
|---------------------------------------------------------------------------|------------|----------|
| Feature 05: LLM Provider Abstraction (LlmProvider, ChatCompletionService) | Geplant    | Ja       |
| Feature 11: Document Chunker (liefert chunk.created Events)               | Geplant    | Ja       |
| Feature 17: MCP Tool Interface (McpServer, McpTool)                       | Geplant    | Ja       |
| Spring Boot 4.x                                                           | Verfuegbar | Nein     |

## Architektur

### ExtractionStrategy

```kotlin
package com.graphmesh.extraction.agent

/**
 * Konfigurierbare Extraktionsstrategie.
 * Bestimmt, welche Aspekte der Agent aus dem Text extrahieren soll
 * und wie er dabei vorgeht.
 */
data class ExtractionStrategy(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val maxIterations: Int = 5,
    val tools: List<String> = emptyList(),
    val outputTypes: List<OutputType> = listOf(OutputType.RELATIONSHIP)
)

/**
 * Typen von Extraktionsergebnissen.
 */
enum class OutputType {
    DEFINITION,
    RELATIONSHIP,
    ENTITY,
    ATTRIBUTE
}
```

### AgentIteration

```kotlin
package com.graphmesh.extraction.agent

/**
 * Eine einzelne Iteration des ReAct-Zyklus.
 *
 * ReAct-Pattern:
 * 1. Thought: Agent ueberlegt, was als naechstes zu tun ist
 * 2. Action: Agent waehlt ein Tool und fuehrt es aus
 * 3. Observation: Agent erhaelt das Tool-Ergebnis
 * 4. Repeat oder Final Answer
 */
data class AgentIteration(
    val iterationNumber: Int,
    val thought: String,
    val action: AgentAction?,
    val observation: String?,
    val isFinal: Boolean = false
)

/**
 * Eine Aktion des Agents (Tool-Aufruf).
 */
data class AgentAction(
    val toolName: String,
    val parameters: Map<String, String>
)

/**
 * Ergebnis einer Agent-Extraktion.
 */
data class AgentExtractionResult(
    val chunkId: String,
    val iterations: Int,
    val extractedItems: List<ExtractedItem>,
    val toolCallCount: Int,
    val strategy: String
)

/**
 * Ein einzelnes extrahiertes Item im JSONL-Format.
 */
sealed class ExtractedItem {
    abstract val type: OutputType

    data class Definition(
        override val type: OutputType = OutputType.DEFINITION,
        val entity: String,
        val definition: String
    ) : ExtractedItem()

    data class Relationship(
        override val type: OutputType = OutputType.RELATIONSHIP,
        val subject: String,
        val predicate: String,
        val objectValue: String,
        val objectIsEntity: Boolean = true
    ) : ExtractedItem()

    data class Entity(
        override val type: OutputType = OutputType.ENTITY,
        val name: String,
        val entityType: String? = null
    ) : ExtractedItem()

    data class Attribute(
        override val type: OutputType = OutputType.ATTRIBUTE,
        val entity: String,
        val attribute: String,
        val value: String
    ) : ExtractedItem()
}
```

### ExtractionAgent

```kotlin
package com.graphmesh.extraction.agent

import com.graphmesh.llm.ChatCompletionService
import com.graphmesh.llm.ChatMessage
import com.graphmesh.llm.ChatRole
import com.graphmesh.mcp.McpServer
import com.graphmesh.mcp.McpTool

/**
 * ReAct-Style Extraction Agent.
 *
 * Der Agent durchlaeuft iterativ den ReAct-Zyklus:
 * 1. Thought: Was muss ich als naechstes tun?
 * 2. Action: Welches Tool verwende ich mit welchen Parametern?
 * 3. Observation: Was ist das Ergebnis des Tool-Aufrufs?
 *
 * Der Agent hat Zugriff auf MCP-Tools (Feature 17):
 * - graph-query: Abfrage des bestehenden Knowledge Graphs
 * - context-expand: Kontext-Erweiterung um benachbarte Chunks
 * - validate-entity: Pruefung, ob eine Entitaet bereits existiert
 *
 * Nach maximal N Iterationen gibt der Agent sein Endergebnis
 * im JSONL-Format aus.
 */
class ExtractionAgent(
    private val chatService: ChatCompletionService,
    private val mcpServer: McpServer,
    private val strategy: ExtractionStrategy
) {

    /**
     * Fuehrt den ReAct-Extraktionszyklus fuer einen Textchunk aus.
     */
    suspend fun extract(chunkText: String): AgentRunResult {
        val conversationHistory = mutableListOf<ChatMessage>()
        val iterations = mutableListOf<AgentIteration>()
        var toolCallCount = 0

        // System-Prompt mit Strategie und verfuegbaren Tools
        val availableTools = mcpServer.listTools()
            .filter { strategy.tools.isEmpty() || it.name in strategy.tools }
        val toolDescriptions = formatToolDescriptions(availableTools)

        conversationHistory.add(ChatMessage(
            role = ChatRole.SYSTEM,
            content = buildSystemPrompt(strategy, toolDescriptions)
        ))

        conversationHistory.add(ChatMessage(
            role = ChatRole.USER,
            content = "Extrahiere Wissen aus folgendem Text:\n\n$chunkText"
        ))

        // ReAct-Loop
        for (i in 1..strategy.maxIterations) {
            val response = chatService.complete(conversationHistory)
            val parsed = parseAgentResponse(response.content)

            if (parsed.isFinal) {
                iterations.add(parsed.copy(iterationNumber = i))
                break
            }

            // Tool-Aufruf ausfuehren
            val action = parsed.action
            if (action != null) {
                toolCallCount++
                val tool = availableTools.firstOrNull { it.name == action.toolName }
                val observation = if (tool != null) {
                    try {
                        mcpServer.executeTool(action.toolName, action.parameters)
                    } catch (e: Exception) {
                        "Fehler: ${e.message}"
                    }
                } else {
                    "Tool '${action.toolName}' nicht verfuegbar"
                }

                iterations.add(parsed.copy(
                    iterationNumber = i,
                    observation = observation
                ))

                // Observation zurueck in die Konversation
                conversationHistory.add(ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = response.content
                ))
                conversationHistory.add(ChatMessage(
                    role = ChatRole.USER,
                    content = "Observation: $observation"
                ))
            } else {
                iterations.add(parsed.copy(iterationNumber = i, isFinal = true))
                break
            }
        }

        // Finales Ergebnis aus der letzten Iteration extrahieren
        val extractedItems = parseFinalOutput(
            conversationHistory.last().content,
            strategy.outputTypes
        )

        return AgentRunResult(
            iterations = iterations,
            extractedItems = extractedItems,
            toolCallCount = toolCallCount
        )
    }

    private fun buildSystemPrompt(
        strategy: ExtractionStrategy,
        toolDescriptions: String
    ): String = """
        ${strategy.systemPrompt}

        Du bist ein Wissensextraktions-Agent. Verwende das ReAct-Pattern:
        - Thought: Ueberlege, was du als naechstes tun musst
        - Action: Waehle ein Tool und dessen Parameter
        - Observation: Erhalte das Ergebnis

        Verfuegbare Tools:
        $toolDescriptions

        Wenn du fertig bist, antworte mit:
        Final Answer:
        Dann gib die extrahierten Ergebnisse im JSONL-Format aus:
        {"type": "relationship", "subject": "...", "predicate": "...", "object": "...", "object-entity": true}
        {"type": "definition", "entity": "...", "definition": "..."}

        Maximale Iterationen: ${strategy.maxIterations}
    """.trimIndent()

    private fun formatToolDescriptions(tools: List<McpTool>): String =
        tools.joinToString("\n") { tool ->
            "- ${tool.name}: ${tool.description}\n  Parameter: ${tool.parameters.joinToString(", ") { "${it.name} (${it.type})" }}"
        }

    internal fun parseAgentResponse(response: String): AgentIteration {
        val lines = response.lines()
        var thought = ""
        var action: AgentAction? = null
        var isFinal = false

        for (line in lines) {
            when {
                line.startsWith("Thought:") -> thought = line.removePrefix("Thought:").trim()
                line.startsWith("Action:") -> {
                    val actionStr = line.removePrefix("Action:").trim()
                    action = parseAction(actionStr)
                }
                line.startsWith("Final Answer:") -> isFinal = true
            }
        }

        return AgentIteration(
            iterationNumber = 0,
            thought = thought,
            action = action,
            observation = null,
            isFinal = isFinal
        )
    }

    private fun parseAction(actionStr: String): AgentAction {
        // Format: tool_name(param1="value1", param2="value2")
        val toolName = actionStr.substringBefore("(").trim()
        val paramsStr = actionStr.substringAfter("(").substringBefore(")").trim()
        val params = if (paramsStr.isNotBlank()) {
            paramsStr.split(",").associate { param ->
                val (key, value) = param.split("=", limit = 2).map { it.trim().removeSurrounding("\"") }
                key to value
            }
        } else emptyMap()

        return AgentAction(toolName = toolName, parameters = params)
    }

    internal fun parseFinalOutput(
        response: String,
        outputTypes: List<OutputType>
    ): List<ExtractedItem> {
        val finalSection = response.substringAfter("Final Answer:", "")
        if (finalSection.isBlank()) return emptyList()

        return finalSection.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val json = kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
                    when (json["type"]?.jsonPrimitive?.content) {
                        "definition" -> ExtractedItem.Definition(
                            entity = json["entity"]!!.jsonPrimitive.content,
                            definition = json["definition"]!!.jsonPrimitive.content
                        )
                        "relationship" -> ExtractedItem.Relationship(
                            subject = json["subject"]!!.jsonPrimitive.content,
                            predicate = json["predicate"]!!.jsonPrimitive.content,
                            objectValue = json["object"]!!.jsonPrimitive.content,
                            objectIsEntity = json["object-entity"]?.jsonPrimitive?.boolean ?: true
                        )
                        "entity" -> ExtractedItem.Entity(
                            name = json["entity"]!!.jsonPrimitive.content,
                            entityType = json["entity_type"]?.jsonPrimitive?.content
                        )
                        "attribute" -> ExtractedItem.Attribute(
                            entity = json["entity"]!!.jsonPrimitive.content,
                            attribute = json["attribute"]!!.jsonPrimitive.content,
                            value = json["value"]!!.jsonPrimitive.content
                        )
                        else -> null
                    }
                } catch (e: Exception) {
                    null
                }
            }
    }
}

data class AgentRunResult(
    val iterations: List<AgentIteration>,
    val extractedItems: List<ExtractedItem>,
    val toolCallCount: Int
)
```

### AgentExtractorService

```kotlin
package com.graphmesh.extraction.agent

import com.graphmesh.llm.ChatCompletionService
import com.graphmesh.mcp.McpServer
import com.graphmesh.rdf.*
import com.graphmesh.storage.cassandra.QuadStore
import com.graphmesh.librarian.LibrarianService
import java.util.UUID

/**
 * Service fuer Agent-basierte Wissensextraktion.
 * Erstellt einen ExtractionAgent mit der konfigurierten Strategie
 * und konvertiert die Ergebnisse in RDF-Quads.
 */
class AgentExtractorService(
    private val chatService: ChatCompletionService,
    private val mcpServer: McpServer,
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService,
    private val defaultStrategy: ExtractionStrategy = DEFAULT_STRATEGY
) {

    companion object {
        val DEFAULT_STRATEGY = ExtractionStrategy(
            name = "default-extraction",
            description = "Standard-Extraktionsstrategie mit Graph-Abgleich",
            systemPrompt = """
                Extrahiere Wissen aus dem Text. Verwende die verfuegbaren Tools, um:
                1. Pruefen, ob Entitaeten bereits im Graph existieren
                2. Existierende Beziehungen zu konsultieren
                3. Den Kontext bei Bedarf zu erweitern
                Ziel: Hochqualitative, nicht-redundante Triples.
            """.trimIndent(),
            maxIterations = 5,
            tools = listOf("graph-query", "validate-entity", "context-expand"),
            outputTypes = listOf(OutputType.DEFINITION, OutputType.RELATIONSHIP)
        )
    }

    /**
     * Extrahiert Wissen aus einem Chunk mittels Agent.
     */
    suspend fun extract(
        chunkId: String,
        collectionId: UUID,
        strategy: ExtractionStrategy = defaultStrategy
    ): AgentExtractionResult {
        val content = librarianService.getContent(chunkId)
        val chunkText = content.toString(Charsets.UTF_8)

        val agent = ExtractionAgent(chatService, mcpServer, strategy)
        val result = agent.extract(chunkText)

        // Extrahierte Items in Quads konvertieren
        val quads = result.extractedItems.flatMap { item ->
            convertToQuads(item, chunkId)
        }

        if (quads.isNotEmpty()) {
            quadStore.saveAll(collectionId.toString(), quads)
        }

        return AgentExtractionResult(
            chunkId = chunkId,
            iterations = result.iterations.size,
            extractedItems = result.extractedItems,
            toolCallCount = result.toolCallCount,
            strategy = strategy.name
        )
    }

    private fun convertToQuads(item: ExtractedItem, chunkId: String): List<Quad> {
        return when (item) {
            is ExtractedItem.Definition -> listOf(
                Quad(
                    subject = EntityIdGenerator.generate(item.entity),
                    predicate = RdfTerm.Uri("http://www.w3.org/2000/01/rdf-schema#comment"),
                    objectTerm = RdfTerm.Literal(item.definition),
                    graph = NamedGraph.DEFAULT
                ),
                Quad(
                    subject = EntityIdGenerator.generate(item.entity),
                    predicate = RdfTerm.Uri("http://www.w3.org/2000/01/rdf-schema#label"),
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
                        predicate = RdfTerm.Uri("http://graphmesh.io/ontology/${item.predicate}"),
                        objectTerm = objectTerm,
                        graph = NamedGraph.DEFAULT
                    )
                )
            }
            is ExtractedItem.Entity -> listOf(
                Quad(
                    subject = EntityIdGenerator.generate(item.name),
                    predicate = RdfTerm.Uri("http://www.w3.org/2000/01/rdf-schema#label"),
                    objectTerm = RdfTerm.Literal(item.name),
                    graph = NamedGraph.DEFAULT
                )
            )
            is ExtractedItem.Attribute -> listOf(
                Quad(
                    subject = EntityIdGenerator.generate(item.entity),
                    predicate = RdfTerm.Uri("http://graphmesh.io/ontology/${item.attribute}"),
                    objectTerm = RdfTerm.Literal(item.value),
                    graph = NamedGraph.DEFAULT
                )
            )
        }
    }
}
```

### AgentExtractorConsumer

```kotlin
package com.graphmesh.extraction.agent

import com.graphmesh.extraction.chunker.ChunkCreatedEvent
import com.graphmesh.messaging.MessageConsumer

/**
 * Kafka-Consumer fuer Agent-basierte Extraktion.
 * Lauscht auf chunk.created Events und delegiert an den AgentExtractorService.
 */
class AgentExtractorConsumer(
    private val consumer: MessageConsumer<ChunkCreatedEvent>,
    private val extractorService: AgentExtractorService
) {
    fun start() {
        consumer.subscribe { message ->
            val event = message.payload
            extractorService.extract(
                chunkId = event.chunkId,
                collectionId = event.collectionId
            )
        }
    }
}
```

### Kafka-Topics

| Topic                     | Richtung  | Schema              |
|---------------------------|-----------|---------------------|
| `graphmesh.chunk.created` | Eingehend | `ChunkCreatedEvent` |

## Betroffene Dateien

### Backend

| Datei                                                                                 | Aenderung                                                                               |
|---------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| `extraction/src/main/kotlin/com/graphmesh/extraction/agent/AgentExtractorService.kt`  | NEU - Haupt-Service fuer Agent-basierte Extraktion                                      |
| `extraction/src/main/kotlin/com/graphmesh/extraction/agent/AgentExtractorConsumer.kt` | NEU - Kafka-Consumer fuer chunk.created Events                                          |
| `extraction/src/main/kotlin/com/graphmesh/extraction/agent/ExtractionAgent.kt`        | NEU - ReAct-Agent mit Tool-Ausfuehrung                                                  |
| `extraction/src/main/kotlin/com/graphmesh/extraction/agent/ExtractionStrategy.kt`     | NEU - Konfigurierbare Extraktionsstrategien                                             |
| `extraction/src/main/kotlin/com/graphmesh/extraction/agent/AgentIteration.kt`         | NEU - ReAct-Zyklus-Datenmodell                                                          |
| `extraction/src/main/kotlin/com/graphmesh/extraction/agent/ExtractedItem.kt`          | NEU - Sealed Class fuer extrahierte Items (Definition, Relationship, Entity, Attribute) |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                    | Aenderung                                       |
|------------------------------------------------------------------------------------------|-------------------------------------------------|
| `extraction/src/test/kotlin/com/graphmesh/extraction/agent/ExtractionAgentTest.kt`       | NEU - ReAct-Loop mit Mock-Tools, max-Iterations |
| `extraction/src/test/kotlin/com/graphmesh/extraction/agent/AgentExtractorServiceTest.kt` | NEU - End-to-End-Extraktion, Quad-Konvertierung |
| `extraction/src/test/kotlin/com/graphmesh/extraction/agent/AgentResponseParsingTest.kt`  | NEU - Thought/Action/Observation-Parsing        |
| `extraction/src/test/kotlin/com/graphmesh/extraction/agent/FinalOutputParsingTest.kt`    | NEU - JSONL-Parsing des Final Answer            |
| `extraction/src/test/kotlin/com/graphmesh/extraction/agent/ExtractionStrategyTest.kt`    | NEU - Verschiedene Strategien und Tool-Filter   |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                            |
|-------------------|-------------|--------------------------------------------------|
| Spring Boot (JVM) | Ja          | LLM-Client, Kafka-Consumer, MCP-Server           |
| KMP Library       | Nein        | Abhaengigkeit zu JVM-spezifischen Clients        |
| Ktor/Wasm         | Nein        | LLM-, Kafka- und MCP-Clients sind JVM-spezifisch |

## Akzeptanzkriterien

- [ ] Agent durchlaeuft den ReAct-Zyklus (Thought -> Action -> Observation) iterativ
- [ ] Agent nutzt MCP-Tools (`graph-query`, `validate-entity`, `context-expand`) zur Wissensanreicherung
- [ ] Maximale Iterationen sind konfigurierbar und werden eingehalten
- [ ] Extraktionsstrategie ist ueber Prompts und Tool-Listen konfigurierbar
- [ ] Agent gibt Ergebnisse im JSONL-Format aus (truncation-resilient)
- [ ] JSONL-Output unterstuetzt discriminated unions (`type`-Feld: definition, relationship, entity, attribute)
- [ ] Extrahierte Items werden korrekt in RDF-Quads konvertiert und gespeichert
- [ ] Entity-IDs werden deterministisch via `EntityIdGenerator` erzeugt
- [ ] Relationship-Objects koennen sowohl Entitaeten (URI) als auch Literale sein (`object-entity` Flag)
- [ ] Agent-Parsing ist robust gegen unerwartete LLM-Antwortformate
- [ ] Tool-Aufruf-Fehler werden als Observation zurueckgegeben, nicht als Abbruch
- [ ] `AgentExtractionResult` enthaelt Iterationen, Tool-Call-Zaehler, Strategie-Name und extrahierte Items
