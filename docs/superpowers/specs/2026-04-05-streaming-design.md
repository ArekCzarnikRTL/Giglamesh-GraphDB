# Feature 27: Streaming — Design Spec

## Zusammenfassung

Echtes Token-Streaming für Agent-Queries über GraphQL Subscriptions. Baut einen manuellen ReAct-Loop mit Koog's `MultiLLMPromptExecutor.executeStreaming()` der `Flow<StreamFrame>` liefert. Jede Phase (THOUGHT, ACTION, OBSERVATION, ANSWER) wird als `StreamToken` über eine GraphQL Subscription gestreamt.

## Entscheidungen

| Entscheidung | Gewählt | Grund |
|---|---|---|
| Streaming-Methode | `MultiLLMPromptExecutor.executeStreaming()` | Liefert `Flow<StreamFrame>`, Spring-injected Bean ist `MultiLLMPromptExecutor` |
| Agent-Implementierung | Manueller ReAct-Loop (nicht `AIAgent.run()`) | `AIAgent.run()` ist Black Box, gibt nur String zurück |
| Scope | Nur Agent-Streaming | RAG-Streaming hat weniger Wert (kurze Antwortzeiten) |
| Transport | GraphQL Subscription via WebSocket | Standard Spring GraphQL Pattern |

## Technische Basis

**Koog Streaming API:**
```kotlin
// MultiLLMPromptExecutor (das Spring-injected PromptExecutor Bean)
fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame>

// StreamFrame Typen:
StreamFrame.TextDelta(text: String)        // Inkrementeller Text
StreamFrame.ToolCallComplete(name, content) // Tool-Aufruf erkannt
StreamFrame.End(finishReason)              // Stream beendet
```

**Spring GraphQL Subscription:**
```kotlin
@SubscriptionMapping
fun agentStream(@Argument input: AgentStreamInput): Flow<StreamToken>
```

## Architektur

```
GraphQL Subscription: agentStream(input)
       │
       ▼
StreamingController (@SubscriptionMapping) → Flow<StreamToken>
       │
       ▼
StreamingAgentService.queryStreaming(question, collectionId, config)
       │
       ├─► Manueller ReAct-Loop:
       │     for (iteration in 1..maxIterations):
       │       1. promptExecutor.executeStreaming(prompt, model, toolDescriptors)
       │          → Flow<StreamFrame> collected:
       │          - TextDelta → emit THOUGHT StreamToken
       │          - ToolCallComplete → emit ACTION StreamToken
       │       2. Tool ausführen → emit OBSERVATION StreamToken
       │       3. Wenn kein Tool-Call → Text ist ANSWER → emit ANSWER + endOfStream
       │
       └─► Flow<StreamToken>
```

## Komponenten

### StreamToken.kt (`com.agentwork.graphmesh.streaming`)

```kotlin
data class StreamToken(
    val content: String,
    val type: StreamTokenType,
    val endOfMessage: Boolean = false,
    val endOfStream: Boolean = false
)

enum class StreamTokenType { TEXT, THOUGHT, ACTION, OBSERVATION, ANSWER, ERROR }
```

### StreamingAgentService.kt (@Service)

**Dependencies:** PromptExecutor (cast zu MultiLLMPromptExecutor), GraphRagService, DocumentRagService, ToolGroupRegistry, modelName

**Methode:** `fun queryStreaming(question, collectionId, config, allowedGroups): Flow<StreamToken>`

**ReAct-Loop:**
1. System-Prompt + Tool-Beschreibungen aufbauen
2. `executeStreaming(prompt, model, toolDescriptors)` → `Flow<StreamFrame>` collecten
3. `TextDelta` → emit `THOUGHT` token
4. `ToolCallComplete` → emit `ACTION` token, Tool ausführen, emit `OBSERVATION` token
5. Wenn `End` ohne Tool-Call → vorheriger Text war die Antwort → emit `ANSWER` + `endOfStream`
6. Loop mit erweitertem Prompt (Conversation-History)

**Tool-Ausführung:** Bestehende Koog SimpleTool-Instanzen (KnowledgeQueryTool, DocumentQueryTool) direkt aufrufen via `tool.execute(args)`.

### streaming.graphqls

```graphql
type Subscription {
    agentStream(input: AgentStreamInput!): StreamToken!
}

input AgentStreamInput {
    question: String!
    collectionId: ID!
    maxIterations: Int = 10
    allowedGroups: [String!]
}

type StreamToken {
    content: String!
    type: StreamTokenType!
    endOfMessage: Boolean!
    endOfStream: Boolean!
}

enum StreamTokenType { TEXT, THOUGHT, ACTION, OBSERVATION, ANSWER, ERROR }
```

### StreamingController.kt (`com.agentwork.graphmesh.api`)

```kotlin
@Controller
class StreamingController(private val streamingAgentService: StreamingAgentService) {
    @SubscriptionMapping
    fun agentStream(@Argument input: AgentStreamInput): Flow<StreamToken>
}
```

### build.gradle.kts — Dependency hinzufügen

```kotlin
implementation("org.springframework.boot:spring-boot-starter-websocket")
```

## Tests

| Test | Fokus |
|------|-------|
| `StreamTokenTest` | StreamToken + StreamTokenType model (trivial) |
| `StreamingAgentServiceTest` | Manuelles ReAct-Loop-Verhalten: TextDelta→THOUGHT, ToolCallComplete→ACTION+OBSERVATION, End→ANSWER |
| `StreamingControllerTest` | Controller delegiert korrekt |

## Abweichungen vom Feature-Doc

1. Nur Agent-Streaming (kein graphRagStream, docRagStream)
2. Kein StreamingLlmProvider Interface — direkte Nutzung von MultiLLMPromptExecutor.executeStreaming()
3. Manueller ReAct-Loop statt AIAgent-basiert
4. Kein Backward-Compatibility-Test nötig — bestehende APIs werden nicht geändert
5. Package `com.agentwork.graphmesh.streaming` statt `com.graphmesh.streaming`
