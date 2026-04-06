# Feature 25: Agent System (ReAct Loop)

## Problem

Benutzer stellen komplexe Fragen, die mehrere Wissensquellen und Reasoning-Schritte erfordern. Eine einzelne RAG-Abfrage
reicht nicht aus, wenn die Antwort Informationen aus verschiedenen Subgraphen, Dokumenten oder externen Tools
kombinieren muss. Ohne einen agentenbasierten Ansatz gibt es keine Moeglichkeit, iterativ zu recherchieren,
Zwischenergebnisse zu bewerten und die Suchstrategie dynamisch anzupassen.

## Ziel

Implementierung eines ReAct-Agenten (Reason + Act), der iterativ denkt, Tools aufruft und Ergebnisse bewertet, bis eine
zufriedenstellende Antwort vorliegt oder ein Abbruchkriterium greift.

1. **ReAct-Loop** -- Think -> Act -> Observe Zyklus mit konfigurierbarem Iterationslimit
2. **Tool-Selektion** -- Dynamische Auswahl registrierter Tools (GraphRAG, DocRAG, MCP-Tools) pro Iteration
3. **Kontext-Management** -- Verwaltung von Gespraechshistorie, Tool-Ergebnissen und Zwischenzustaenden
4. **State Machine** -- Explizite Zustandsmaschine (THINKING, ACTING, OBSERVING, DONE, FAILED)
5. **Stop-Conditions** -- Konfigurierbare Abbruchbedingungen (max Iterationen, Confidence-Schwelle, Timeout)
6. **GraphQL-Exponierung** -- Agent-Queries und -Mutations ueber die bestehende GraphQL-API

## Voraussetzungen

| Abhaengigkeit                                                             | Status     | Blocker? |
|---------------------------------------------------------------------------|------------|----------|
| Feature 05: LLM Provider Abstraction (LlmProvider, ChatCompletionService) | Geplant    | Ja       |
| Feature 15: Graph RAG (GraphRagService, SubgraphRetriever)                | Geplant    | Ja       |
| Feature 16: Document RAG (DocumentRagService, ChunkRetriever)             | Geplant    | Ja       |
| Feature 17: MCP Tool Interface (McpServer, McpTool, McpToolDefinition)    | Geplant    | Ja       |
| Feature 14: GraphQL API (Schema-first, Controller-Infrastruktur)          | Geplant    | Ja       |
| Spring Boot 4.x                                                           | Verfuegbar | Nein     |

## Architektur

### Agent State Machine

```kotlin
package com.graphmesh.agent

/**
 * Zustaende des Agenten waehrend der Ausfuehrung.
 */
enum class AgentState {
    /** Agent ueberlegt den naechsten Schritt. */
    THINKING,
    /** Agent fuehrt ein Tool aus. */
    ACTING,
    /** Agent wertet das Tool-Ergebnis aus. */
    OBSERVING,
    /** Agent hat eine Antwort gefunden. */
    DONE,
    /** Agent ist fehlgeschlagen (Timeout, max Iterationen, Fehler). */
    FAILED
}
```

### AgentConfig

```kotlin
package com.graphmesh.agent

/**
 * Konfiguration fuer eine Agent-Ausfuehrung.
 */
data class AgentConfig(
    /** Maximale Anzahl an ReAct-Iterationen. */
    val maxIterations: Int = 10,
    /** Timeout fuer die gesamte Ausfuehrung in Millisekunden. */
    val timeoutMs: Long = 120_000,
    /** System-Prompt, der das Agentenverhalten steuert. */
    val systemPrompt: String = DEFAULT_AGENT_SYSTEM_PROMPT,
    /** LLM-Temperatur fuer die Reasoning-Phase. */
    val temperature: Double = 0.3,
    /** Ob der Agent bei Fehler eines Tools weitermachen soll. */
    val continueOnToolError: Boolean = true
) {
    companion object {
        const val DEFAULT_AGENT_SYSTEM_PROMPT = """
            You are a reasoning agent. For each step:
            1. Think about what information you need
            2. Choose the most appropriate tool
            3. Observe the result and decide next steps
            When you have enough information, provide a final answer.
        """
    }
}
```

### AgentContext und AgentIteration

```kotlin
package com.graphmesh.agent

import java.time.Instant
import java.util.UUID

/**
 * Kontext, der ueber alle Iterationen hinweg erhalten bleibt.
 */
data class AgentContext(
    /** Eindeutige Session-ID. */
    val sessionId: UUID = UUID.randomUUID(),
    /** Die urspruengliche Benutzerfrage. */
    val question: String,
    /** Collection, in der gearbeitet wird. */
    val collectionId: UUID,
    /** Konfiguration des Agenten. */
    val config: AgentConfig = AgentConfig(),
    /** Bisherige Iterationen. */
    val iterations: MutableList<AgentIteration> = mutableListOf(),
    /** Aktueller Zustand. */
    var state: AgentState = AgentState.THINKING,
    /** Startzeitpunkt. */
    val startedAt: Instant = Instant.now()
)

/**
 * Eine einzelne ReAct-Iteration.
 */
data class AgentIteration(
    /** Laufende Nummer der Iteration (1-basiert). */
    val number: Int,
    /** Gedanke des Agenten (Think-Phase). */
    val thought: String,
    /** Gewaehlte Aktion / Tool (Act-Phase). Null bei finaler Antwort. */
    val action: AgentAction?,
    /** Beobachtung / Tool-Ergebnis (Observe-Phase). */
    val observation: String?,
    /** Dauer dieser Iteration in Millisekunden. */
    val durationMs: Long,
    /** Zeitstempel. */
    val timestamp: Instant = Instant.now()
)

/**
 * Eine Aktion, die der Agent ausfuehren moechte.
 */
data class AgentAction(
    /** Name des gewaehlten Tools. */
    val toolName: String,
    /** Argumente fuer das Tool als Key-Value-Paare. */
    val arguments: Map<String, String>
)
```

### AgentTool Interface

```kotlin
package com.graphmesh.agent

/**
 * Schnittstelle fuer Tools, die dem Agenten zur Verfuegung stehen.
 * Jedes registrierte Tool muss dieses Interface implementieren.
 */
interface AgentTool {
    /** Eindeutiger Name des Tools. */
    val name: String
    /** Beschreibung fuer das LLM (wird im System-Prompt verwendet). */
    val description: String
    /** JSON-Schema der erwarteten Argumente. */
    val argumentSchema: String

    /**
     * Fuehrt das Tool mit den gegebenen Argumenten aus.
     *
     * @param arguments Die Argumente als Key-Value-Map.
     * @param context Der aktuelle Agent-Kontext.
     * @return Das Ergebnis als String (Observation).
     */
    suspend fun invoke(arguments: Map<String, String>, context: AgentContext): String
}
```

### AgentService

```kotlin
package com.graphmesh.agent

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Hauptservice fuer den ReAct-Agenten.
 * Orchestriert den Think-Act-Observe-Loop.
 */
interface AgentService {

    /**
     * Fuehrt eine Agent-Session blockierend aus.
     *
     * @param question Die Benutzerfrage.
     * @param collectionId Die Ziel-Collection.
     * @param config Optionale Konfiguration.
     * @return Das Ergebnis mit Antwort und Iterationsverlauf.
     */
    suspend fun run(
        question: String,
        collectionId: UUID,
        config: AgentConfig = AgentConfig()
    ): AgentResult

    /**
     * Registriert ein Tool beim Agenten.
     */
    fun registerTool(tool: AgentTool)

    /**
     * Gibt alle registrierten Tools zurueck.
     */
    fun getRegisteredTools(): List<AgentTool>
}

/**
 * Ergebnis einer Agent-Ausfuehrung.
 */
data class AgentResult(
    /** Die finale Antwort. */
    val answer: String,
    /** Alle Iterationen der Session. */
    val iterations: List<AgentIteration>,
    /** Endzustand des Agenten. */
    val finalState: AgentState,
    /** Gesamtdauer in Millisekunden. */
    val totalDurationMs: Long,
    /** Session-ID fuer Provenance-Tracking. */
    val sessionId: UUID
)
```

### ReAct-Loop Implementierung

```kotlin
package com.graphmesh.agent

import com.graphmesh.llm.ChatCompletionService
import org.slf4j.LoggerFactory

/**
 * Default-Implementierung des AgentService mit ReAct-Pattern.
 */
class DefaultAgentService(
    private val chatService: ChatCompletionService,
    private val tools: MutableList<AgentTool> = mutableListOf()
) : AgentService {

    private val logger = LoggerFactory.getLogger(DefaultAgentService::class.java)

    override suspend fun run(
        question: String,
        collectionId: UUID,
        config: AgentConfig
    ): AgentResult {
        val context = AgentContext(
            question = question,
            collectionId = collectionId,
            config = config
        )

        while (context.state != AgentState.DONE && context.state != AgentState.FAILED) {
            if (context.iterations.size >= config.maxIterations) {
                context.state = AgentState.FAILED
                break
            }

            // Think: LLM entscheidet naechsten Schritt
            context.state = AgentState.THINKING
            val thinkResult = think(context)

            if (thinkResult.isFinalAnswer) {
                context.state = AgentState.DONE
                context.iterations.add(/* finale Iteration */)
                break
            }

            // Act: Tool ausfuehren
            context.state = AgentState.ACTING
            val action = thinkResult.action!!
            val observation = executeTool(action, context)

            // Observe: Ergebnis aufnehmen
            context.state = AgentState.OBSERVING
            context.iterations.add(
                AgentIteration(
                    number = context.iterations.size + 1,
                    thought = thinkResult.thought,
                    action = action,
                    observation = observation,
                    durationMs = /* gemessen */
                )
            )
        }

        return AgentResult(
            answer = extractFinalAnswer(context),
            iterations = context.iterations,
            finalState = context.state,
            totalDurationMs = /* berechnet */,
            sessionId = context.sessionId
        )
    }

    private suspend fun executeTool(
        action: AgentAction,
        context: AgentContext
    ): String {
        val tool = tools.find { it.name == action.toolName }
            ?: return "Error: Tool '${action.toolName}' not found."
        return try {
            tool.invoke(action.arguments, context)
        } catch (e: Exception) {
            logger.error("Tool execution failed: ${action.toolName}", e)
            if (context.config.continueOnToolError) {
                "Error: ${e.message}"
            } else {
                context.state = AgentState.FAILED
                throw e
            }
        }
    }
}
```

### Built-in Tool-Adapter

```kotlin
package com.graphmesh.agent

import com.graphmesh.query.graphrag.GraphRagService
import com.graphmesh.query.graphrag.GraphRagQuery

/**
 * Adapter, der GraphRagService als AgentTool bereitstellt.
 */
class GraphRagTool(
    private val graphRagService: GraphRagService
) : AgentTool {
    override val name = "knowledge-query"
    override val description = "Query the knowledge graph for entities and relationships."
    override val argumentSchema = """{"question": {"type": "string"}}"""

    override suspend fun invoke(
        arguments: Map<String, String>,
        context: AgentContext
    ): String {
        val query = GraphRagQuery(
            question = arguments["question"] ?: context.question,
            collectionId = context.collectionId
        )
        val result = graphRagService.query(query)
        return result.answer
    }
}
```

### GraphQL-Schema

```graphql
type AgentResult {
    answer: String!
    iterations: [AgentIteration!]!
    finalState: AgentState!
    totalDurationMs: Long!
    sessionId: ID!
}

type AgentIteration {
    number: Int!
    thought: String!
    action: AgentAction
    observation: String
    durationMs: Long!
}

type AgentAction {
    toolName: String!
    arguments: JSON!
}

enum AgentState {
    THINKING
    ACTING
    OBSERVING
    DONE
    FAILED
}

type Query {
    agentTools: [ToolInfo!]!
}

type Mutation {
    askAgent(
        question: String!
        collectionId: ID!
        maxIterations: Int
        timeoutMs: Long
    ): AgentResult!
}
```

## Betroffene Dateien

### Backend

| Datei                                                              | Aenderung                            |
|--------------------------------------------------------------------|--------------------------------------|
| `agent/src/main/kotlin/com/graphmesh/agent/AgentState.kt`          | Enum fuer Agentenzustaende           |
| `agent/src/main/kotlin/com/graphmesh/agent/AgentConfig.kt`         | Konfigurationsklasse                 |
| `agent/src/main/kotlin/com/graphmesh/agent/AgentContext.kt`        | Kontextklasse mit Iterations-History |
| `agent/src/main/kotlin/com/graphmesh/agent/AgentIteration.kt`      | Datenklasse fuer eine Iteration      |
| `agent/src/main/kotlin/com/graphmesh/agent/AgentTool.kt`           | Interface fuer registrierbare Tools  |
| `agent/src/main/kotlin/com/graphmesh/agent/AgentService.kt`        | Hauptservice-Interface               |
| `agent/src/main/kotlin/com/graphmesh/agent/DefaultAgentService.kt` | ReAct-Loop Implementierung           |
| `agent/src/main/kotlin/com/graphmesh/agent/GraphRagTool.kt`        | Adapter fuer GraphRagService         |
| `agent/src/main/kotlin/com/graphmesh/agent/DocRagTool.kt`          | Adapter fuer DocumentRagService      |
| `agent/src/main/kotlin/com/graphmesh/agent/McpToolAdapter.kt`      | Adapter fuer MCP-Tools               |
| `graphql/src/main/resources/graphql/agent.graphqls`                | GraphQL-Schema-Erweiterung           |
| `graphql/src/main/kotlin/com/graphmesh/graphql/AgentController.kt` | GraphQL-Controller                   |

### Frontend

Nicht direkt betroffen. Die Frontend-Integration erfolgt ueber die GraphQL-API.

### Tests

| Datei                                                                  | Aenderung                      |
|------------------------------------------------------------------------|--------------------------------|
| `agent/src/test/kotlin/com/graphmesh/agent/DefaultAgentServiceTest.kt` | Unit-Tests fuer ReAct-Loop     |
| `agent/src/test/kotlin/com/graphmesh/agent/AgentToolTest.kt`           | Tests fuer Tool-Adapter        |
| `agent/src/test/kotlin/com/graphmesh/agent/AgentStateMachineTest.kt`   | Tests fuer Zustandsuebergaenge |
| `graphql/src/test/kotlin/com/graphmesh/graphql/AgentControllerTest.kt` | Integration-Tests fuer GraphQL |

## Platform-Einschraenkungen

| Plattform         | Unterstuetzt | Anmerkung                                          |
|-------------------|--------------|----------------------------------------------------|
| Spring Boot (JVM) | Ja           | Primaere Zielplattform                             |
| KMP Library       | Nein         | Abhaengig von Spring-Kontext und Coroutines-Server |
| Ktor/Wasm         | Nein         | Kein Server-Side-Agent im Browser                  |

## Akzeptanzkriterien

- [ ] Agent fuehrt mindestens 3 ReAct-Iterationen mit Tool-Aufrufen durch und liefert eine Antwort
- [ ] State Machine durchlaeuft korrekt THINKING -> ACTING -> OBSERVING -> DONE
- [ ] Bei Ueberschreitung von `maxIterations` wechselt der Zustand zu FAILED
- [ ] GraphRagTool, DocRagTool und McpToolAdapter sind als AgentTools registrierbar
- [ ] GraphQL-Mutation `askAgent` gibt AgentResult mit Iterationsverlauf zurueck
- [ ] Tool-Fehler werden bei `continueOnToolError=true` als Observation aufgenommen
- [ ] Agent-Timeout fuehrt zu FAILED-Zustand mit Teilergebnis
- [ ] Alle registrierten Tools sind ueber `agentTools`-Query abrufbar
