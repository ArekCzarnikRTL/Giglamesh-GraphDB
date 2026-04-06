# Feature 30: Query-Time Explainability — Design Spec

## Problem & Goal

Users receive answers from GraphRAG, DocRAG, and the Agent, but cannot trace
how those answers were derived. This feature records the derivation path of
every answer (question → exploration → focus → synthesis/conclusion) and makes
it queryable via GraphQL.

## Architecture

Three layers, communicating via Kafka:

```
┌────────────────────┐    publish    ┌──────────────────────┐
│ GraphRagService    │──────┐        │                      │
│ DocumentRagService │──────┼───────▶│ graphmesh.query      │
│ AgentService       │──────┘        │   .explained (Kafka) │
└────────────────────┘               └──────────┬───────────┘
                                                │ consume
                                                ▼
                                     ┌──────────────────────┐
                                     │ Explainability       │
                                     │ EventConsumer        │
                                     │  → builds PROV-O     │
                                     │  → writes QuadStore  │
                                     │    (RETRIEVAL graph) │
                                     └──────────┬───────────┘
                                                │
                                                ▼
                                     ┌──────────────────────┐
                                     │ Explainability       │
                                     │ Controller (GraphQL) │
                                     │  reads QuadStore     │
                                     └──────────────────────┘
```

The three services publish events synchronously after producing their answer.
A consumer translates events to PROV-O quads and persists them in
`NamedGraph.RETRIEVAL`, scoped to the queried collection. A GraphQL controller
reads back chains for drill-down.

## Package Layout

All under `com.agentwork.graphmesh` (no submodules — single Gradle module).

```
provenance/query/
├── ExplainabilityModels.kt         — Question, Exploration, Focus, Synthesis,
│                                     Analysis, Conclusion, ExplanationChain,
│                                     QueryMechanism, SelectedEdgeExplanation,
│                                     AgentIterationRecord
├── ExplainabilityUris.kt           — URI generators
├── ExplainabilityRecorder.kt       — builds PROV-O Quads from models
└── ExplainabilityNamespaces.kt     — additional PROV-O / TG namespace constants

messaging/
├── ExplainabilityEventProducer.kt  — Avro-based Kafka producer
└── ExplainabilityEventConsumer.kt  — consumes, builds quads via Recorder,
                                      writes to QuadStore

api/
└── ExplainabilityController.kt     — @QueryMapping handlers

resources/
├── avro/query-explained.avsc       — single discriminated schema
└── graphql/explainability.graphqls — schema fragment
```

Existing files modified:
- `query/graphrag/GraphRagService.kt` — call producer after synthesis
- `query/docrag/DocumentRagService.kt` — call producer after synthesis
- `agent/AgentService.kt` — install Koog Tracing, collect iterations,
  call producer after `agent.run()`
- `messaging/KafkaTopicConfig.kt` — add `graphmesh.query.explained` topic bean
- `storage/QuadQuery.kt` — add optional `graph: String?` field
- `storage/QuadStore.kt` (Cassandra impl) — honor `graph` filter in `query()`

## Data Model

### Core Entities (`provenance/query/ExplainabilityModels.kt`)

```kotlin
package com.agentwork.graphmesh.provenance.query

import java.time.Instant

enum class QueryMechanism { GRAPH_RAG, DOC_RAG, AGENT }

data class Question(
    val uri: String,
    val queryText: String,
    val timestamp: Instant,
    val mechanism: QueryMechanism,
)

data class Exploration(
    val uri: String,
    val edgeCount: Int,
    val questionUri: String,
)

data class SelectedEdgeExplanation(
    val subject: String,
    val predicate: String,
    val objectValue: String,
    val reasoning: String,
)

data class Focus(
    val uri: String,
    val selectedEdges: List<SelectedEdgeExplanation>,
    val explorationUri: String,
)

data class Synthesis(
    val uri: String,
    val answerText: String,
    val derivedFromUri: String,
)

data class AgentIterationRecord(
    val thought: String,
    val action: String?,
    val arguments: Map<String, String>?,
    val observation: String?,
)

data class Analysis(
    val uri: String,
    val thought: String,
    val action: String?,
    val arguments: Map<String, String>?,
    val observation: String?,
    val parentUri: String,
)

data class Conclusion(
    val uri: String,
    val answerText: String,
    val parentUri: String,
)

data class ExplanationChain(
    val question: Question,
    val exploration: Exploration?,
    val focus: Focus?,
    val analyses: List<Analysis>?,
    val synthesis: Synthesis?,
    val conclusion: Conclusion?,
    val mechanism: QueryMechanism,
)
```

### URI Conventions (`ExplainabilityUris.kt`)

```kotlin
object ExplainabilityUris {
    private const val BASE = "urn:graphmesh"

    fun question(sessionId: UUID, mechanism: QueryMechanism): String =
        when (mechanism) {
            QueryMechanism.GRAPH_RAG -> "$BASE:question:$sessionId"
            QueryMechanism.DOC_RAG   -> "$BASE:docrag:$sessionId"
            QueryMechanism.AGENT     -> "$BASE:agent:$sessionId"
        }

    fun exploration(sessionId: UUID) = "$BASE:prov:retrieval:$sessionId"
    fun focus(sessionId: UUID)       = "$BASE:prov:selection:$sessionId"
    fun synthesis(sessionId: UUID)   = "$BASE:prov:answer:$sessionId"
    fun analysis(sessionId: UUID, n: Int) = "$BASE:agent:$sessionId/i$n"
    fun conclusion(sessionId: UUID)  = "$BASE:agent:$sessionId/final"
}
```

### Namespaces (`ExplainabilityNamespaces.kt`)

Additions to the existing `ProvenanceNamespaces` (kept separate to avoid
mixing extraction-time and query-time vocabulary):

```kotlin
object ExplainabilityNamespaces {
    const val TG = "http://graphmesh.io/ontology/"
    const val TG_QUESTION    = "${TG}Question"
    const val TG_EXPLORATION = "${TG}Exploration"
    const val TG_FOCUS       = "${TG}Focus"
    const val TG_SYNTHESIS   = "${TG}Synthesis"
    const val TG_ANALYSIS    = "${TG}Analysis"
    const val TG_CONCLUSION  = "${TG}Conclusion"
    const val TG_QUERY_TEXT  = "${TG}queryText"
    const val TG_REASONING   = "${TG}reasoning"
    const val TG_THOUGHT     = "${TG}thought"
    const val TG_ACTION      = "${TG}action"
    const val TG_OBSERVATION = "${TG}observation"
    const val TG_ANSWER_TEXT = "${TG}answerText"
    const val TG_EDGE_COUNT  = "${TG}edgeCount"
    const val TG_MECHANISM   = "${TG}mechanism"
}
```

## ExplainabilityRecorder

Pure function class — no Spring beans, no I/O. Builds quads only.

```kotlin
@Component
class ExplainabilityRecorder {
    fun questionQuads(q: Question): List<Quad>
    fun explorationQuads(e: Exploration): List<Quad>
    fun focusQuads(f: Focus): List<Quad>
    fun synthesisQuads(s: Synthesis): List<Quad>
    fun analysisQuads(a: Analysis): List<Quad>
    fun conclusionQuads(c: Conclusion): List<Quad>

    /** Convenience: builds the full quad set for a session. */
    fun graphRagSessionQuads(
        question: Question,
        exploration: Exploration,
        focus: Focus,
        synthesis: Synthesis,
    ): List<Quad>

    fun docRagSessionQuads(
        question: Question,
        exploration: Exploration,
        synthesis: Synthesis,
    ): List<Quad>

    fun agentSessionQuads(
        question: Question,
        analyses: List<Analysis>,
        conclusion: Conclusion,
    ): List<Quad>
}
```

All quads emitted with `graph = NamedGraph.RETRIEVAL`.

For each `SelectedEdgeExplanation` in a Focus, emit a `tg:reasoning` literal
attached to a `RdfTerm.QuotedTriple` of the selected edge — keeping the
PROV-O / RDF-star pattern already used in extraction-time provenance.

## Kafka Integration

### Avro Schema (`avro/query-explained.avsc`)

```json
{
  "type": "record",
  "name": "QueryExplained",
  "namespace": "com.agentwork.graphmesh.messaging.avro",
  "fields": [
    { "name": "sessionId",          "type": "string" },
    { "name": "collectionId",       "type": "string" },
    { "name": "mechanism",          "type": { "type": "enum", "name": "QueryMechanism",
                                              "symbols": ["GRAPH_RAG","DOC_RAG","AGENT"] } },
    { "name": "queryText",          "type": "string" },
    { "name": "timestamp",          "type": "string" },
    { "name": "answerText",         "type": "string" },

    { "name": "retrievedEdgeCount", "type": ["null","int"], "default": null },
    { "name": "selectedEdges",      "type": ["null", { "type": "array",
        "items": { "type": "record", "name": "SelectedEdgeRecord", "fields": [
            { "name": "subject",   "type": "string" },
            { "name": "predicate", "type": "string" },
            { "name": "objectValue","type": "string" },
            { "name": "reasoning", "type": "string" }
        ]}}], "default": null },

    { "name": "retrievedChunkCount","type": ["null","int"], "default": null },
    { "name": "selectedChunkIds",   "type": ["null", { "type": "array", "items": "string" }],
                                    "default": null },

    { "name": "iterations",         "type": ["null", { "type": "array",
        "items": { "type": "record", "name": "AgentIterationRecord", "fields": [
            { "name": "thought",    "type": "string" },
            { "name": "action",     "type": ["null","string"], "default": null },
            { "name": "arguments",  "type": ["null", { "type": "map", "values": "string" }],
                                    "default": null },
            { "name": "observation","type": ["null","string"], "default": null }
        ]}}], "default": null }
  ]
}
```

### Topic

`graphmesh.query.explained` — 3 partitions, 1 replica (matches existing
convention). Bean added in `KafkaTopicConfig`.

### Producer

```kotlin
@Service
class ExplainabilityEventProducer(
    private val kafka: KafkaTemplate<String, GenericRecord>,
) {
    fun sendGraphRagEvent(
        sessionId: UUID, collectionId: String, queryText: String,
        retrievedEdgeCount: Int, selectedEdges: List<SelectedEdgeExplanation>,
        answerText: String,
    )
    fun sendDocRagEvent(...)
    fun sendAgentEvent(...)
}
```

Producer is **fire-and-forget** — failures are logged via the existing
`whenComplete` pattern but never thrown to the caller. Query latency is never
affected by Kafka availability.

CloudEvent headers populated via existing `CloudEventHeaders.build()`,
with `subject = sessionId`, `type = "graphmesh.query.explained.v1"`.

### Consumer

```kotlin
@Component
class ExplainabilityEventConsumer(
    private val recorder: ExplainabilityRecorder,
    private val quadStore: QuadStore,
) {
    @KafkaListener(topics = ["graphmesh.query.explained"], groupId = "graphmesh")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        // 1. Decode Avro → mechanism-specific model
        // 2. Build Question + mechanism-specific entities
        // 3. recorder.{mechanism}SessionQuads(...)
        // 4. quadStore.insertBatch(collectionId, quads.map { it.toStoredQuad() })
    }
}
```

## Agent Iteration Tracking via Koog Tracing

Koog 0.7.x exposes a `Tracing` feature with `FeatureMessageProcessor`.
We implement a **session-scoped** processor that captures the
think/act/observe pattern from `LLMCallCompletedEvent` and
`ToolExecutionStartingEvent` / `ToolExecutionCompletedEvent`.

```kotlin
class AgentIterationCollector : FeatureMessageProcessor() {
    private val _isOpen = MutableStateFlow(false)
    override val isOpen: StateFlow<Boolean> get() = _isOpen.asStateFlow()

    private val iterations = mutableListOf<AgentIterationRecord>()
    private var pendingThought: String? = null
    private var pendingAction: String? = null
    private var pendingArgs: Map<String, String>? = null

    override suspend fun processMessage(message: FeatureMessage) {
        when (message) {
            is LLMCallCompletedEvent ->
                pendingThought = message.responseText
            is ToolExecutionStartingEvent -> {
                pendingAction = message.toolName
                pendingArgs = message.argsAsMap()
            }
            is ToolExecutionCompletedEvent -> {
                iterations.add(AgentIterationRecord(
                    thought = pendingThought ?: "",
                    action = pendingAction,
                    arguments = pendingArgs,
                    observation = message.result,
                ))
                pendingThought = null; pendingAction = null; pendingArgs = null
            }
        }
    }

    fun snapshot(): List<AgentIterationRecord> {
        // Flush any trailing thought without a tool call (final reasoning)
        if (pendingThought != null) {
            iterations.add(AgentIterationRecord(pendingThought!!, null, null, null))
        }
        return iterations.toList()
    }

    override suspend fun close() { _isOpen.value = false }
}
```

`AgentService.query()` is modified:

```kotlin
val collector = AgentIterationCollector()
val agent = AIAgent(
    promptExecutor = promptExecutor,
    llmModel = LLModel(LLMProvider.OpenAI, modelName),
    strategy = reActStrategy(reasoningInterval = 1, name = "query_agent"),
    toolRegistry = toolRegistry,
    systemPrompt = config.systemPrompt,
) {
    install(Tracing) { addMessageProcessor(collector) }
}

val sessionId = UUID.randomUUID()
val answer = runBlocking { agent.run(question) }
explainabilityProducer.sendAgentEvent(
    sessionId, collectionId, question, collector.snapshot(), answer
)
```

Note: Exact Koog event class names (`LLMCallCompletedEvent`,
`ToolExecutionStartingEvent`, `ToolExecutionCompletedEvent`) and field shapes
will be verified against the actual Koog 0.7.3 API during implementation.
If a particular field doesn't match (e.g., `responseText`, `argsAsMap`),
the collector will be adapted — the event flow above is the contract,
the field names are best-effort placeholders.

## QuadStore Extension

`QuadQuery` gets an optional `graph` field; the Cassandra implementation
filters on it. This is the only existing API touched.

```kotlin
data class QuadQuery(
    val subject: String? = null,
    val predicate: String? = null,
    val objectValue: String? = null,
    val graph: String? = null,    // NEW
)
```

## GraphQL Schema (`graphql/explainability.graphqls`)

```graphql
extend type Query {
    explanationChain(collectionId: ID!, sessionUri: ID!): ExplanationChain
    explanationSessions(
        collectionId: ID!,
        mechanism: QueryMechanism,
        limit: Int = 50
    ): [QuestionExplanation!]!
}

enum QueryMechanism { GRAPH_RAG DOC_RAG AGENT }

type ExplanationChain {
    question: QuestionExplanation!
    exploration: ExplorationExplanation
    focus: FocusExplanation
    analyses: [AnalysisExplanation!]
    synthesis: SynthesisExplanation
    conclusion: ConclusionExplanation
    mechanism: QueryMechanism!
}

type QuestionExplanation {
    uri: ID!
    queryText: String!
    timestamp: String!
    mechanism: QueryMechanism!
}

type ExplorationExplanation {
    uri: ID!
    edgeCount: Int!
}

type FocusExplanation {
    uri: ID!
    selectedEdges: [SelectedEdgeDetail!]!
}

type SelectedEdgeDetail {
    subject: String!
    predicate: String!
    objectValue: String!
    reasoning: String!
}

type AnalysisExplanation {
    uri: ID!
    thought: String!
    action: String
    arguments: [ArgumentEntry!]
    observation: String
}

type ArgumentEntry { key: String! value: String! }

type SynthesisExplanation { uri: ID! answerText: String! }
type ConclusionExplanation { uri: ID! answerText: String! }
```

`collectionId` is required on all queries because explainability data is
collection-scoped. `arguments` is modeled as a list of entries rather than a
JSON scalar (no JSON scalar registered in the project today).

## Controller

```kotlin
@Controller
class ExplainabilityController(
    private val quadStore: QuadStore,
    private val chainLoader: ExplanationChainLoader,
) {
    @QueryMapping
    fun explanationChain(
        @Argument collectionId: String,
        @Argument sessionUri: String,
    ): ExplanationChain? = chainLoader.load(collectionId, sessionUri)

    @QueryMapping
    fun explanationSessions(
        @Argument collectionId: String,
        @Argument mechanism: QueryMechanism?,
        @Argument limit: Int,
    ): List<Question> = chainLoader.listSessions(collectionId, mechanism, limit)
}
```

`ExplanationChainLoader` is a small reader that walks the quads in
`NamedGraph.RETRIEVAL` for the given session URI and reconstructs the
`ExplanationChain`. It uses the new `QuadQuery.graph` filter plus subject
prefix matching.

## Testing Strategy

| Test                                       | What it covers                                  |
|--------------------------------------------|-------------------------------------------------|
| `ExplainabilityRecorderTest`               | Quad shapes for each entity / each mechanism    |
| `ExplainabilityUrisTest`                   | URI determinism                                 |
| `ExplainabilityEventProducerTest`          | Avro encoding, header population                |
| `ExplainabilityEventConsumerTest`          | Avro → quad → QuadStore (in-memory store)       |
| `ExplanationChainLoaderTest`               | Reconstruction from quads                       |
| `AgentIterationCollectorTest`              | Event sequence → iteration list                 |
| `ExplainabilityControllerTest` (optional)  | GraphQL wiring                                  |

All tests instantiate classes directly — no Mockito, no Testcontainers,
in line with the existing project conventions (memory rule:
`feedback_architecture_patterns`).

## Acceptance Criteria

- [ ] GraphRAG session produces Question → Exploration → Focus → Synthesis
      quads in `urn:graph:retrieval`
- [ ] DocRAG session produces Question → Exploration → Synthesis (no Focus)
- [ ] Agent session produces Question → Analysis₁ → … → Analysisₙ → Conclusion
- [ ] Each Analysis carries thought, action, arguments, observation when available
- [ ] Agent analyses are linearly chained via `prov:wasDerivedFrom`
- [ ] Selected edges in Focus carry the LLM reasoning literal
- [ ] GraphQL `explanationChain` returns the full chain for a session
- [ ] GraphQL `explanationSessions` lists sessions filterable by mechanism
- [ ] All explainability quads use `NamedGraph.RETRIEVAL`
- [ ] Producer failure does **not** affect query latency or correctness
- [ ] Consumer is idempotent at session granularity (replay safe)

## Open Questions / Risks

1. **Koog event field names** — verified during implementation; collector
   contract is fixed but the exact event class members may need adjustment.
2. **Idempotency** — first cut: re-consuming a session simply re-inserts the
   quads. Cassandra UPSERT semantics make this safe (same primary key,
   identical values). No de-dup logic needed unless replay storms appear.
3. **Drill-down to Feature 29** — the `SelectedEdgeDetail` carries the raw
   subject/predicate/object so a UI can query the extraction-time provenance
   in `urn:graph:source` separately. No cross-graph join required at this
   stage.
