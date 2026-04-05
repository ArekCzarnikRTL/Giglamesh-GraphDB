# Feature 25: Agent System — Design Spec

## Zusammenfassung

Allgemeiner ReAct-Query-Agent basierend auf Koog AIAgent + reActStrategy. Beantwortet komplexe User-Fragen durch iteratives Recherchieren über GraphRAG und DocumentRAG. Exponiert als eigenständiger GraphQL-Endpoint parallel zu NlpQueryService.

## Entscheidungen

| Entscheidung | Gewählt | Grund |
|---|---|---|
| Agent-Framework | Koog AIAgent + reActStrategy | Bewährt in Feature 24, eingebauter ReAct-Loop |
| Verhältnis zu NlpQueryService | Eigenständiger Endpoint | Agent ist teurer, User soll bewusst wählen |
| State Machine | Weggelassen | Koog managed den State intern |
| Custom AgentService Interface | Weggelassen | Direkte @Service Implementierung, YAGNI |
| Custom AgentTool Interface | Weggelassen | Koog SimpleTool reicht |

## Architektur

```
GraphQL: askAgent(input: AgentQueryInput!) → AgentQueryResult
         agentTools → [ToolInfo!]!
       │
       ▼
AgentController (@Controller)
       │
       ▼
AgentService (@Service)
       │
       ├─► AIAgent erstellen mit reActStrategy + ToolRegistry
       │
       ├─► runBlocking { agent.run(question) } → String
       │     ├── knowledge_query Tool → GraphRagService.query()
       │     └── document_query Tool → DocumentRagService.query()
       │
       └─► AgentQueryResult(answer, durationMs)
```

## Komponenten

### Models.kt (`com.agentwork.graphmesh.agent`)

```kotlin
data class AgentQueryConfig(
    val maxIterations: Int = 10,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """
            Du bist ein Wissensassistent. Beantworte die Frage des Benutzers.
            Verwende die verfuegbaren Tools um Informationen zu sammeln:
            - knowledge_query: Frage den Knowledge Graph ab
            - document_query: Suche in Dokumenten
            Wenn du genug Informationen hast, gib eine ausfuehrliche Antwort.
        """
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

### AgentQueryTools.kt

2 Koog `SimpleTool<Args>` — gleiche Pattern wie Feature 24:

**`KnowledgeQueryTool(graphRagService, collectionId)`**
- Name: `knowledge_query`
- Description: "Query the knowledge graph for entities and relationships"
- Input: `Args(question: String)`
- Output: GraphRagResult.answer + Sources

**`DocumentQueryTool(documentRagService, collectionId)`**
- Name: `document_query`
- Description: "Search documents for relevant text passages"
- Input: `Args(question: String)`
- Output: DocumentRagResult.answer + Sources

### AgentService.kt (@Service)

**Dependencies:** PromptExecutor, GraphRagService, DocumentRagService, modelName

**Methode:** `fun query(question: String, collectionId: String, config: AgentQueryConfig = AgentQueryConfig()): AgentQueryResult`

**Ablauf:**
1. ToolRegistry mit KnowledgeQueryTool + DocumentQueryTool erstellen
2. AIAgent mit reActStrategy erstellen
3. `runBlocking { agent.run(question) }` → Antwort-String
4. Timing messen, AgentQueryResult zurückgeben

**Methode:** `fun getAvailableTools(): List<ToolInfo>`
- Gibt statische Liste der 2 Tools mit Name + Description zurück

### agent.graphqls

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

### AgentController.kt (`com.agentwork.graphmesh.api`)

```kotlin
@Controller
class AgentController(private val agentService: AgentService) {

    @MutationMapping
    fun askAgent(@Argument input: AgentQueryInput): AgentQueryResult

    @QueryMapping
    fun agentTools(): List<ToolInfo>
}

data class AgentQueryInput(
    val question: String,
    val collectionId: String,
    val maxIterations: Int?
)
```

## Tests

| Test | Fokus |
|------|-------|
| `AgentQueryToolsTest` | KnowledgeQueryTool mit gemocktem GraphRagService, DocumentQueryTool mit gemocktem DocumentRagService |
| `AgentServiceTest` | query() — E2E-Ablauf testen ist schwer (AIAgent), daher fokussiert auf getAvailableTools() + Input-Validierung |
| `AgentControllerTest` | Controller delegiert korrekt an AgentService |

## Abweichungen vom Feature-Doc

1. Koog `AIAgent` + `reActStrategy` statt manuellem DefaultAgentService mit ReAct-Loop
2. Kein AgentState Enum — Koog managed den State intern
3. Kein AgentContext/AgentIteration — Koog gibt den Iterationsverlauf nicht direkt zurück
4. Kein custom AgentTool Interface — Koog SimpleTool
5. Kein custom AgentService Interface — direkte @Service Implementierung
6. 2 Tools (knowledge_query, document_query) statt 3 (kein McpToolAdapter — MCP-Tools sind via GraphMeshMcpTools separat exponiert)
7. AgentQueryResult vereinfacht: nur answer + durationMs (kein Iterationsverlauf)
8. Package `com.agentwork.graphmesh.agent` statt `com.graphmesh.agent`
9. Eigenständiger Endpoint parallel zu NlpQueryService (kein Ersetzen)
