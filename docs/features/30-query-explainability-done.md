# Feature 30: Query-Time Explainability — DONE

Implemented on 2026-04-06.

## Summary

Query-time explainability is recorded as PROV-O quads in the named graph
`urn:graph:retrieval`, scoped to the queried collection. Three mechanisms are
supported:

- **GraphRAG**: Question → Exploration → Focus → Synthesis
- **DocRAG**:  Question → Exploration → Synthesis (no Focus)
- **Agent**:   Question → Analysis₁ → … → Analysisₙ → Conclusion

Recording is **fire-and-forget** via Kafka. `GraphRagService`,
`DocumentRagService`, and `AgentService` publish a `graphmesh.query.explained`
event after producing an answer. `ExplainabilityEventConsumer` decodes the
Avro payload, builds the PROV-O quads via `ExplainabilityRecorder`, and writes
them to `QuadStore` in batch. A GraphQL controller (`explanationChain`,
`explanationSessions`) reads them back via `ExplanationChainLoader`.

## Architecture

```
GraphRagService \
DocumentRagService > ExplainabilityEventProducer → Kafka (query.explained)
AgentService      /                                          ↓
                                        ExplainabilityEventConsumer
                                                 ↓
                                        ExplainabilityRecorder
                                                 ↓
                                        QuadStore (NamedGraph.RETRIEVAL)
                                                 ↑
                                        ExplanationChainLoader
                                                 ↑
                                        ExplainabilityController (GraphQL)
```

## Files created

### Backend — main

- `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityModels.kt`
- `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityUris.kt`
- `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityNamespaces.kt`
- `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityRecorder.kt`
- `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplanationChainLoader.kt`
- `src/main/kotlin/com/agentwork/graphmesh/provenance/query/AgentIterationCollector.kt`
- `src/main/kotlin/com/agentwork/graphmesh/agent/KoogAgentTracingBridge.kt`
- `src/main/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventProducer.kt`
- `src/main/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventConsumer.kt`
- `src/main/kotlin/com/agentwork/graphmesh/api/ExplainabilityController.kt`
- `src/main/resources/avro/query-explained.avsc`
- `src/main/resources/graphql/explainability.graphqls`

### Backend — tests

- `src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityUrisTest.kt` (8 tests)
- `src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityRecorderTest.kt` (10 tests)
- `src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplanationChainLoaderTest.kt` (6 tests, incl. multi-session isolation regression)
- `src/test/kotlin/com/agentwork/graphmesh/provenance/query/AgentIterationCollectorTest.kt` (3 tests)
- `src/test/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventProducerTest.kt` (4 tests)
- `src/test/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventConsumerTest.kt` (3 tests)

**Total: 34 new unit tests, all passing.**

### Files modified

- `src/main/kotlin/com/agentwork/graphmesh/messaging/KafkaTopicConfig.kt` — added `queryExplainedTopic` bean
- `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt` — emits events; added `sessionId` to result
- `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagModels.kt` — `sessionId: UUID`
- `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt` — emits events
- `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagModels.kt` — `sessionId: UUID`
- `src/main/kotlin/com/agentwork/graphmesh/agent/AgentService.kt` — emits events via bridge+collector
- `src/main/kotlin/com/agentwork/graphmesh/agent/Models.kt` — `AgentQueryResult.sessionId: UUID`
- `src/main/resources/graphql/graph-rag.graphqls` — `sessionId: ID!`
- `src/main/resources/graphql/document-rag.graphqls` — `sessionId: ID!`
- `src/main/resources/graphql/agent.graphqls` — `sessionId: ID!`
- Several unrelated existing tests updated to pass the new `sessionId`/producer constructor arguments (AgentControllerTest, AgentQueryToolsTest, GraphMeshMcpToolsTest, ExtractionToolsTest, NlpQueryServiceOrchestrationTest)

## Deviations from the original feature doc

- **Package root**: the feature doc used `com.graphmesh`; the actual package is
  `com.agentwork.graphmesh`. All files follow the real package.
- **No submodule layout**: all code lives in a single Gradle module under
  `src/main/kotlin/`. The feature doc's references to `provenance/src/main/...`
  paths were ignored in favor of the existing flat layout.
- **`QuadQuery.dataset` already exists**: no schema change to `QuadQuery` was
  needed. The Cassandra store already supports filtering by named graph via
  query pattern 15.
- **All explainability data classes in one file** (`ExplainabilityModels.kt`)
  rather than one file per data class, matching the existing codebase style
  (e.g. `GraphRagModels.kt`, `DocumentRagModels.kt`).
- **Arguments exposed as `[ArgumentEntry!]`**, not a JSON scalar — no JSON
  scalar is registered in the project's GraphQL schema.
- **Focus needs an explicit link quad to its selected edges** — during code
  review we discovered that querying `tg:reasoning` quads without session
  scoping would return the union of all selected edges across sessions in the
  same collection. Fixed by adding `tg:hasSelectedEdge` quads linking the
  focus URI to each `QuotedTriple`, and scoping the loader's focus read to
  that link. Regression test added.
- **Koog Tracing installation deferred**: the `KoogAgentTracingBridge` exists
  and is manually testable, but the actual `install(Tracing) { ... }` call on
  the Koog `AIAgent` is not yet wired — the Koog 0.7.3 event API needs to be
  verified (`FeatureMessageProcessor` / event class names). As explicitly
  allowed by the plan, agent events currently ship with `iterations = []`.
  The rest of the agent chain (Question + Conclusion) is still recorded.

## Open items / tech debt

1. **Koog Tracing wiring** — locate `FeatureMessageProcessor` / `install(Tracing)`
   in `koog-agents-jvm:0.7.3`, add the installer block to `AgentService.query()`,
   and remove the `@Suppress("UNUSED_VARIABLE")` / NOTE comment. The bridge's
   `handle(Any)` is the integration point.

2. **`argKey`/`argValue` encoding** — the recorder uses `"$k=$v"` in the
   `tg:argValue` literal to let the loader round-trip the map. This works for
   simple tool-argument values but is fragile for values containing newlines
   or `=`-prefixed substrings. Follow-up: replace with a single JSON-encoded
   quad (`tg:argumentsJson`) once Feature 30 has real production traffic.
   The separate `tg:argKey` quads are currently emitted but never read by the
   loader — they can be removed when the encoding is revisited.

3. **`loadAgentChain` performance** — O(S·K²) in the number of sessions × average
   iterations per session, because the loader scans every `TG_ANALYSIS` node
   in the collection and walks `parentOf` per analysis. For collections with
   many agent sessions this will need an index (e.g. `<analysis> tg:sessionUri <question>`)
   or a forward walk from the question. Track as a performance follow-up.

4. **`listSessions` N+1 reads** — issues one query per session URI. Acceptable
   for <1k sessions; add an index if it grows.

5. **Drill-down to extraction-time provenance (Feature 29)** — selected edges
   in a Focus expose the raw `subject`/`predicate`/`objectValue` so a client
   can separately query the `urn:graph:source` graph for who extracted them.
   No server-side join is provided.

6. **No `xsd:dateTime` datatype on timestamp literals** — `tg:timestamp` is
   stored as a plain string literal (`Instant.toString()`). Range queries in
   SPARQL would need the datatype. Track if/when SPARQL queries land.

## Commits on main

1. `f549d53` feat(explainability): add query-time provenance data models and URIs
2. `c56ab6d` feat(explainability): add ExplainabilityRecorder with PROV-O quad builders
3. `40d19f3` feat(explainability): add ExplanationChainLoader for drill-down reads
4. `8cd09b1` fix(explainability): scope focus selected-edge reads to the parent focus URI
5. `e89bfe7` feat(explainability): add query-explained Avro schema and Kafka topic
6. `f61dd41` feat(explainability): add ExplainabilityEventProducer with Avro encoding
7. `a06a1f5` feat(explainability): add ExplainabilityEventConsumer that persists PROV-O quads
8. `0ee2ed9` feat(explainability): add AgentIterationCollector for think/act/observe capture
9. `9f46273` feat(explainability): emit explainability events from GraphRAG and DocRAG services
10. `7d8ff99` feat(explainability): emit explainability events from AgentService with Koog tracing bridge
11. `b38da27` feat(explainability): add GraphQL schema and controller for explanation chains
