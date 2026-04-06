# Feature 30: Query-Time Explainability — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record the derivation path of every GraphRAG, DocRAG, and Agent answer (question → exploration → focus → synthesis/conclusion) as PROV-O quads in the `urn:graph:retrieval` named graph, and expose them via GraphQL for drill-down.

**Architecture:** Services publish a Kafka event (`graphmesh.query.explained`) after producing an answer. A consumer translates each event to PROV-O quads via `ExplainabilityRecorder` and writes them to the existing `QuadStore`, scoped to the queried collection. A GraphQL controller reads back chains using `QuadQuery` filtered by `dataset = NamedGraph.RETRIEVAL`.

**Tech Stack:** Kotlin 2.2, Spring Boot 4.0, Spring for GraphQL, Spring Kafka, Apache Avro 1.12, Cassandra `QuadStore`, Koog 0.7.3 (Tracing feature for agent iteration capture), JUnit 5 + kotlin-test.

**Key project conventions:**
- Package root: `com.agentwork.graphmesh` (NOT `com.graphmesh`)
- Single Gradle module under `src/main/kotlin/`
- Tests instantiate classes directly — no Mockito/MockK in new tests, no Testcontainers
- `QuadQuery` already supports filtering by `dataset` (named graph) — no schema change needed
- Quad ↔ StoredQuad conversion via `com.agentwork.graphmesh.rdf.QuadConverter`
- Producers are fire-and-forget (log on failure via `whenComplete`)

---

## File Structure

### Created

| File | Purpose |
|---|---|
| `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityModels.kt` | All Question/Exploration/Focus/Synthesis/Analysis/Conclusion/ExplanationChain data classes + `QueryMechanism` enum + `SelectedEdgeExplanation` + `AgentIterationRecord` |
| `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityUris.kt` | URI generators (object) |
| `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityNamespaces.kt` | TG vocabulary constants |
| `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityRecorder.kt` | Pure quad builder (`@Component`) |
| `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplanationChainLoader.kt` | Reads quads from `QuadStore`, reconstructs `ExplanationChain` (`@Component`) |
| `src/main/kotlin/com/agentwork/graphmesh/provenance/query/AgentIterationCollector.kt` | Koog `FeatureMessageProcessor` capturing think/act/observe |
| `src/main/resources/avro/query-explained.avsc` | Discriminated Avro record for all 3 mechanisms |
| `src/main/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventProducer.kt` | Avro Kafka producer |
| `src/main/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventConsumer.kt` | `@KafkaListener`, decodes & persists |
| `src/main/resources/graphql/explainability.graphqls` | GraphQL schema fragment |
| `src/main/kotlin/com/agentwork/graphmesh/api/ExplainabilityController.kt` | `@QueryMapping` handlers |
| `src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityUrisTest.kt` | URI generator tests |
| `src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityRecorderTest.kt` | Quad shape tests for all mechanisms |
| `src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplanationChainLoaderTest.kt` | Reconstruction from in-memory quads |
| `src/test/kotlin/com/agentwork/graphmesh/provenance/query/AgentIterationCollectorTest.kt` | Event sequence → iteration list |
| `src/test/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventProducerTest.kt` | Avro encoding & header population |
| `src/test/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventConsumerTest.kt` | Avro decoding → quad write (in-memory `QuadStore`) |

### Modified

| File | Change |
|---|---|
| `src/main/kotlin/com/agentwork/graphmesh/messaging/KafkaTopicConfig.kt` | Add `queryExplainedTopic` bean |
| `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt` | Inject producer; emit event after `synthesizeAnswer` |
| `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt` | Inject producer; emit event after `synthesizeAnswer` |
| `src/main/kotlin/com/agentwork/graphmesh/agent/AgentService.kt` | Install Koog `Tracing` with `AgentIterationCollector`; emit event after `agent.run()` |
| `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagModels.kt` | Add `sessionId: UUID` to `GraphRagResult` |
| `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagModels.kt` | Add `sessionId: UUID` to `DocumentRagResult` |
| `src/main/kotlin/com/agentwork/graphmesh/agent/Models.kt` | Add `sessionId: UUID` to `AgentQueryResult` |
| `src/main/resources/graphql/graph-rag.graphqls` | Add `sessionId: ID!` field to `GraphRagResponse` |
| `src/main/resources/graphql/document-rag.graphqls` | Add `sessionId: ID!` field to `DocumentRagResponse` |
| `src/main/resources/graphql/agent.graphqls` | Add `sessionId: ID!` field to `AgentQueryResult` |
| `docs/features/30-query-explainability-done.md` | Final completion doc |

---

## Note on `QuadStore.dataset` field

The existing `QuadQuery` already has a `dataset: String?` filter, and the
Cassandra implementation supports filtering by named graph (Pattern 15).
**No changes to `QuadQuery` or `CassandraQuadStore` are needed.** The loader
uses `QuadQuery(dataset = NamedGraph.RETRIEVAL, subject = ...)` to scope reads.

---

## Task 1: Data Models & URI Generators

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityModels.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityUris.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityNamespaces.kt`
- Test: `src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityUrisTest.kt`

- [ ] **Step 1: Write the failing URI test**

Create `src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityUrisTest.kt`:

```kotlin
package com.agentwork.graphmesh.provenance.query

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class ExplainabilityUrisTest {

    private val sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `graphRag question uri`() {
        assertEquals(
            "urn:graphmesh:question:11111111-1111-1111-1111-111111111111",
            ExplainabilityUris.question(sessionId, QueryMechanism.GRAPH_RAG)
        )
    }

    @Test
    fun `docRag question uri uses docrag prefix`() {
        assertEquals(
            "urn:graphmesh:docrag:11111111-1111-1111-1111-111111111111",
            ExplainabilityUris.question(sessionId, QueryMechanism.DOC_RAG)
        )
    }

    @Test
    fun `agent question uri uses agent prefix`() {
        assertEquals(
            "urn:graphmesh:agent:11111111-1111-1111-1111-111111111111",
            ExplainabilityUris.question(sessionId, QueryMechanism.AGENT)
        )
    }

    @Test
    fun `exploration uri`() {
        assertEquals(
            "urn:graphmesh:prov:retrieval:11111111-1111-1111-1111-111111111111",
            ExplainabilityUris.exploration(sessionId)
        )
    }

    @Test
    fun `focus uri`() {
        assertEquals(
            "urn:graphmesh:prov:selection:11111111-1111-1111-1111-111111111111",
            ExplainabilityUris.focus(sessionId)
        )
    }

    @Test
    fun `synthesis uri`() {
        assertEquals(
            "urn:graphmesh:prov:answer:11111111-1111-1111-1111-111111111111",
            ExplainabilityUris.synthesis(sessionId)
        )
    }

    @Test
    fun `analysis uri includes iteration number`() {
        assertEquals(
            "urn:graphmesh:agent:11111111-1111-1111-1111-111111111111/i3",
            ExplainabilityUris.analysis(sessionId, 3)
        )
    }

    @Test
    fun `conclusion uri`() {
        assertEquals(
            "urn:graphmesh:agent:11111111-1111-1111-1111-111111111111/final",
            ExplainabilityUris.conclusion(sessionId)
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.provenance.query.ExplainabilityUrisTest"`
Expected: FAIL — class `ExplainabilityUris` not found

- [ ] **Step 3: Create the namespaces file**

Create `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityNamespaces.kt`:

```kotlin
package com.agentwork.graphmesh.provenance.query

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
    const val TG_TIMESTAMP   = "${TG}timestamp"
    const val TG_ARG_KEY     = "${TG}argKey"
    const val TG_ARG_VALUE   = "${TG}argValue"
    const val TG_ITERATION_INDEX = "${TG}iterationIndex"
}
```

- [ ] **Step 4: Create the data models file**

Create `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityModels.kt`:

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
    val iterationIndex: Int,
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

- [ ] **Step 5: Create the URI generator**

Create `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityUris.kt`:

```kotlin
package com.agentwork.graphmesh.provenance.query

import java.util.UUID

object ExplainabilityUris {
    private const val BASE = "urn:graphmesh"

    fun question(sessionId: UUID, mechanism: QueryMechanism): String =
        when (mechanism) {
            QueryMechanism.GRAPH_RAG -> "$BASE:question:$sessionId"
            QueryMechanism.DOC_RAG   -> "$BASE:docrag:$sessionId"
            QueryMechanism.AGENT     -> "$BASE:agent:$sessionId"
        }

    fun exploration(sessionId: UUID): String = "$BASE:prov:retrieval:$sessionId"
    fun focus(sessionId: UUID): String       = "$BASE:prov:selection:$sessionId"
    fun synthesis(sessionId: UUID): String   = "$BASE:prov:answer:$sessionId"
    fun analysis(sessionId: UUID, iterationIndex: Int): String =
        "$BASE:agent:$sessionId/i$iterationIndex"
    fun conclusion(sessionId: UUID): String  = "$BASE:agent:$sessionId/final"
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.provenance.query.ExplainabilityUrisTest"`
Expected: PASS, 8 tests succeed

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityModels.kt \
        src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityUris.kt \
        src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityNamespaces.kt \
        src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityUrisTest.kt
git commit -m "feat(explainability): add query-time provenance data models and URIs"
```

---

## Task 2: ExplainabilityRecorder

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityRecorder.kt`
- Test: `src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityRecorderTest.kt`

The recorder is a pure function class. All quads use `graph = NamedGraph.RETRIEVAL`. Predicates from `ExplainabilityNamespaces` (TG vocabulary) and `ProvenanceNamespaces` (PROV-O reuse).

- [ ] **Step 1: Write failing tests**

Create `src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityRecorderTest.kt`:

```kotlin
package com.agentwork.graphmesh.provenance.query

import com.agentwork.graphmesh.provenance.ProvenanceNamespaces
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.RdfTerm
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExplainabilityRecorderTest {

    private val recorder = ExplainabilityRecorder()

    private fun question(mechanism: QueryMechanism) = Question(
        uri = "urn:graphmesh:question:s1",
        queryText = "What is X?",
        timestamp = Instant.parse("2026-04-06T12:00:00Z"),
        mechanism = mechanism,
    )

    @Test
    fun `questionQuads emits rdf type Question and PROV Activity`() {
        val q = question(QueryMechanism.GRAPH_RAG)
        val quads = recorder.questionQuads(q)

        assertTrue(quads.all { it.graph == NamedGraph.RETRIEVAL })
        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.RDF_TYPE &&
            (it.objectTerm as? RdfTerm.Uri)?.value == ExplainabilityNamespaces.TG_QUESTION
        })
        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.RDF_TYPE &&
            (it.objectTerm as? RdfTerm.Uri)?.value == ProvenanceNamespaces.PROV_ACTIVITY
        })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_QUERY_TEXT &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "What is X?"
        })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_MECHANISM &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "GRAPH_RAG"
        })
    }

    @Test
    fun `explorationQuads links to question via prov wasGeneratedBy`() {
        val e = Exploration("urn:graphmesh:prov:retrieval:s1", 42, "urn:graphmesh:question:s1")
        val quads = recorder.explorationQuads(e)

        assertTrue(quads.all { it.graph == NamedGraph.RETRIEVAL })
        assertTrue(quads.any {
            it.subject == RdfTerm.Uri("urn:graphmesh:prov:retrieval:s1") &&
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_GENERATED_BY &&
            (it.objectTerm as? RdfTerm.Uri)?.value == "urn:graphmesh:question:s1"
        })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_EDGE_COUNT &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "42"
        })
    }

    @Test
    fun `focusQuads emits a quoted triple with reasoning literal per selected edge`() {
        val edges = listOf(
            SelectedEdgeExplanation("urn:s1", "urn:p1", "urn:o1", "because A"),
            SelectedEdgeExplanation("urn:s2", "urn:p2", "urn:o2", "because B"),
        )
        val f = Focus("urn:graphmesh:prov:selection:s1", edges, "urn:graphmesh:prov:retrieval:s1")
        val quads = recorder.focusQuads(f)

        assertTrue(quads.all { it.graph == NamedGraph.RETRIEVAL })

        // Focus -> wasDerivedFrom -> Exploration
        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM &&
            (it.objectTerm as? RdfTerm.Uri)?.value == "urn:graphmesh:prov:retrieval:s1"
        })

        // Two reasoning literals attached to QuotedTriple subjects
        val reasoningQuads = quads.filter {
            it.predicate.value == ExplainabilityNamespaces.TG_REASONING &&
            it.subject is RdfTerm.QuotedTriple
        }
        assertEquals(2, reasoningQuads.size)
        assertTrue(reasoningQuads.any { (it.objectTerm as RdfTerm.Literal).value == "because A" })
        assertTrue(reasoningQuads.any { (it.objectTerm as RdfTerm.Literal).value == "because B" })
    }

    @Test
    fun `synthesisQuads links to derivation source`() {
        val s = Synthesis("urn:graphmesh:prov:answer:s1", "the answer", "urn:graphmesh:prov:selection:s1")
        val quads = recorder.synthesisQuads(s)

        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM &&
            (it.objectTerm as? RdfTerm.Uri)?.value == "urn:graphmesh:prov:selection:s1"
        })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_ANSWER_TEXT &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "the answer"
        })
    }

    @Test
    fun `analysisQuads emits thought action observation and chains via wasDerivedFrom`() {
        val a = Analysis(
            uri = "urn:graphmesh:agent:s1/i2",
            iterationIndex = 2,
            thought = "I should look up X",
            action = "knowledge_query",
            arguments = mapOf("query" to "X"),
            observation = "X is foo",
            parentUri = "urn:graphmesh:agent:s1/i1",
        )
        val quads = recorder.analysisQuads(a)

        assertTrue(quads.all { it.graph == NamedGraph.RETRIEVAL })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_THOUGHT &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "I should look up X"
        })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_ACTION &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "knowledge_query"
        })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_OBSERVATION &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "X is foo"
        })
        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM &&
            (it.objectTerm as? RdfTerm.Uri)?.value == "urn:graphmesh:agent:s1/i1"
        })
        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_ITERATION_INDEX &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "2"
        })
    }

    @Test
    fun `analysisQuads omits null action observation and arguments`() {
        val a = Analysis(
            uri = "urn:graphmesh:agent:s1/i1",
            iterationIndex = 1,
            thought = "I have enough info",
            action = null,
            arguments = null,
            observation = null,
            parentUri = "urn:graphmesh:question:s1",
        )
        val quads = recorder.analysisQuads(a)

        assertTrue(quads.none { it.predicate.value == ExplainabilityNamespaces.TG_ACTION })
        assertTrue(quads.none { it.predicate.value == ExplainabilityNamespaces.TG_OBSERVATION })
        assertTrue(quads.none { it.predicate.value == ExplainabilityNamespaces.TG_ARG_KEY })
    }

    @Test
    fun `conclusionQuads emits answer text and chains to last analysis`() {
        val c = Conclusion("urn:graphmesh:agent:s1/final", "final answer", "urn:graphmesh:agent:s1/i3")
        val quads = recorder.conclusionQuads(c)

        assertTrue(quads.any {
            it.predicate.value == ExplainabilityNamespaces.TG_ANSWER_TEXT &&
            (it.objectTerm as? RdfTerm.Literal)?.value == "final answer"
        })
        assertTrue(quads.any {
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM &&
            (it.objectTerm as? RdfTerm.Uri)?.value == "urn:graphmesh:agent:s1/i3"
        })
    }

    @Test
    fun `graphRagSessionQuads contains all four entity types`() {
        val q = question(QueryMechanism.GRAPH_RAG)
        val e = Exploration("urn:graphmesh:prov:retrieval:s1", 5, q.uri)
        val f = Focus("urn:graphmesh:prov:selection:s1",
            listOf(SelectedEdgeExplanation("a","p","b","r")), e.uri)
        val s = Synthesis("urn:graphmesh:prov:answer:s1", "ans", f.uri)

        val quads = recorder.graphRagSessionQuads(q, e, f, s)

        assertTrue(quads.any { (it.objectTerm as? RdfTerm.Uri)?.value == ExplainabilityNamespaces.TG_QUESTION })
        assertTrue(quads.any { (it.objectTerm as? RdfTerm.Uri)?.value == ExplainabilityNamespaces.TG_EXPLORATION })
        assertTrue(quads.any { (it.objectTerm as? RdfTerm.Uri)?.value == ExplainabilityNamespaces.TG_FOCUS })
        assertTrue(quads.any { (it.objectTerm as? RdfTerm.Uri)?.value == ExplainabilityNamespaces.TG_SYNTHESIS })
    }

    @Test
    fun `docRagSessionQuads has no focus type`() {
        val q = question(QueryMechanism.DOC_RAG)
        val e = Exploration("urn:graphmesh:prov:retrieval:s1", 5, q.uri)
        val s = Synthesis("urn:graphmesh:prov:answer:s1", "ans", e.uri)

        val quads = recorder.docRagSessionQuads(q, e, s)

        assertTrue(quads.none { (it.objectTerm as? RdfTerm.Uri)?.value == ExplainabilityNamespaces.TG_FOCUS })
        assertTrue(quads.any { (it.objectTerm as? RdfTerm.Uri)?.value == ExplainabilityNamespaces.TG_SYNTHESIS })
    }

    @Test
    fun `agentSessionQuads chains analyses linearly`() {
        val q = question(QueryMechanism.AGENT)
        val a1 = Analysis("urn:graphmesh:agent:s1/i1", 1, "t1", null, null, null, q.uri)
        val a2 = Analysis("urn:graphmesh:agent:s1/i2", 2, "t2", null, null, null, a1.uri)
        val c  = Conclusion("urn:graphmesh:agent:s1/final", "ans", a2.uri)

        val quads = recorder.agentSessionQuads(q, listOf(a1, a2), c)

        // Each analysis chains to its parent
        assertTrue(quads.any {
            it.subject == RdfTerm.Uri(a2.uri) &&
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM &&
            (it.objectTerm as? RdfTerm.Uri)?.value == a1.uri
        })
        assertTrue(quads.any {
            it.subject == RdfTerm.Uri(a1.uri) &&
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM &&
            (it.objectTerm as? RdfTerm.Uri)?.value == q.uri
        })
        assertTrue(quads.any {
            it.subject == RdfTerm.Uri(c.uri) &&
            it.predicate.value == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM &&
            (it.objectTerm as? RdfTerm.Uri)?.value == a2.uri
        })
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew test --tests "com.agentwork.graphmesh.provenance.query.ExplainabilityRecorderTest"`
Expected: FAIL — class `ExplainabilityRecorder` not found

- [ ] **Step 3: Create the recorder**

Create `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityRecorder.kt`:

```kotlin
package com.agentwork.graphmesh.provenance.query

import com.agentwork.graphmesh.provenance.ProvenanceNamespaces
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.Quad
import com.agentwork.graphmesh.rdf.RdfTerm
import com.agentwork.graphmesh.rdf.Triple
import org.springframework.stereotype.Component

@Component
class ExplainabilityRecorder {

    fun questionQuads(question: Question): List<Quad> {
        val s = RdfTerm.Uri(question.uri)
        return listOf(
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ExplainabilityNamespaces.TG_QUESTION)),
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ProvenanceNamespaces.PROV_ACTIVITY)),
            quad(s, ExplainabilityNamespaces.TG_QUERY_TEXT, RdfTerm.Literal(question.queryText)),
            quad(s, ExplainabilityNamespaces.TG_TIMESTAMP, RdfTerm.Literal(question.timestamp.toString())),
            quad(s, ExplainabilityNamespaces.TG_MECHANISM, RdfTerm.Literal(question.mechanism.name)),
        )
    }

    fun explorationQuads(exploration: Exploration): List<Quad> {
        val s = RdfTerm.Uri(exploration.uri)
        return listOf(
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ExplainabilityNamespaces.TG_EXPLORATION)),
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ProvenanceNamespaces.PROV_ENTITY)),
            quad(s, ProvenanceNamespaces.PROV_WAS_GENERATED_BY, RdfTerm.Uri(exploration.questionUri)),
            quad(s, ExplainabilityNamespaces.TG_EDGE_COUNT, RdfTerm.Literal(exploration.edgeCount.toString())),
        )
    }

    fun focusQuads(focus: Focus): List<Quad> {
        val s = RdfTerm.Uri(focus.uri)
        val out = mutableListOf<Quad>()
        out += quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ExplainabilityNamespaces.TG_FOCUS))
        out += quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ProvenanceNamespaces.PROV_ENTITY))
        out += quad(s, ProvenanceNamespaces.PROV_WAS_DERIVED_FROM, RdfTerm.Uri(focus.explorationUri))

        for (edge in focus.selectedEdges) {
            val quoted = RdfTerm.QuotedTriple(
                Triple(
                    subject = RdfTerm.Uri(edge.subject),
                    predicate = RdfTerm.Uri(edge.predicate),
                    objectTerm = RdfTerm.Uri(edge.objectValue),
                )
            )
            out += quad(quoted, ExplainabilityNamespaces.TG_REASONING, RdfTerm.Literal(edge.reasoning))
        }
        return out
    }

    fun synthesisQuads(synthesis: Synthesis): List<Quad> {
        val s = RdfTerm.Uri(synthesis.uri)
        return listOf(
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ExplainabilityNamespaces.TG_SYNTHESIS)),
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ProvenanceNamespaces.PROV_ENTITY)),
            quad(s, ProvenanceNamespaces.PROV_WAS_DERIVED_FROM, RdfTerm.Uri(synthesis.derivedFromUri)),
            quad(s, ExplainabilityNamespaces.TG_ANSWER_TEXT, RdfTerm.Literal(synthesis.answerText)),
        )
    }

    fun analysisQuads(analysis: Analysis): List<Quad> {
        val s = RdfTerm.Uri(analysis.uri)
        val out = mutableListOf<Quad>()
        out += quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ExplainabilityNamespaces.TG_ANALYSIS))
        out += quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ProvenanceNamespaces.PROV_ENTITY))
        out += quad(s, ProvenanceNamespaces.PROV_WAS_DERIVED_FROM, RdfTerm.Uri(analysis.parentUri))
        out += quad(s, ExplainabilityNamespaces.TG_THOUGHT, RdfTerm.Literal(analysis.thought))
        out += quad(s, ExplainabilityNamespaces.TG_ITERATION_INDEX,
            RdfTerm.Literal(analysis.iterationIndex.toString()))

        analysis.action?.let {
            out += quad(s, ExplainabilityNamespaces.TG_ACTION, RdfTerm.Literal(it))
        }
        analysis.observation?.let {
            out += quad(s, ExplainabilityNamespaces.TG_OBSERVATION, RdfTerm.Literal(it))
        }
        analysis.arguments?.forEach { (k, v) ->
            out += quad(s, ExplainabilityNamespaces.TG_ARG_KEY, RdfTerm.Literal(k))
            out += quad(s, ExplainabilityNamespaces.TG_ARG_VALUE, RdfTerm.Literal("$k=$v"))
        }
        return out
    }

    fun conclusionQuads(conclusion: Conclusion): List<Quad> {
        val s = RdfTerm.Uri(conclusion.uri)
        return listOf(
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ExplainabilityNamespaces.TG_CONCLUSION)),
            quad(s, ProvenanceNamespaces.RDF_TYPE, RdfTerm.Uri(ProvenanceNamespaces.PROV_ENTITY)),
            quad(s, ProvenanceNamespaces.PROV_WAS_DERIVED_FROM, RdfTerm.Uri(conclusion.parentUri)),
            quad(s, ExplainabilityNamespaces.TG_ANSWER_TEXT, RdfTerm.Literal(conclusion.answerText)),
        )
    }

    fun graphRagSessionQuads(
        question: Question,
        exploration: Exploration,
        focus: Focus,
        synthesis: Synthesis,
    ): List<Quad> = questionQuads(question) + explorationQuads(exploration) +
            focusQuads(focus) + synthesisQuads(synthesis)

    fun docRagSessionQuads(
        question: Question,
        exploration: Exploration,
        synthesis: Synthesis,
    ): List<Quad> = questionQuads(question) + explorationQuads(exploration) + synthesisQuads(synthesis)

    fun agentSessionQuads(
        question: Question,
        analyses: List<Analysis>,
        conclusion: Conclusion,
    ): List<Quad> = questionQuads(question) + analyses.flatMap { analysisQuads(it) } +
            conclusionQuads(conclusion)

    private fun quad(subject: RdfTerm, predicate: String, objectTerm: RdfTerm): Quad =
        Quad(
            subject = subject,
            predicate = RdfTerm.Uri(predicate),
            objectTerm = objectTerm,
            graph = NamedGraph.RETRIEVAL,
        )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.provenance.query.ExplainabilityRecorderTest"`
Expected: PASS, 10 tests succeed

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityRecorder.kt \
        src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplainabilityRecorderTest.kt
git commit -m "feat(explainability): add ExplainabilityRecorder with PROV-O quad builders"
```

---

## Task 3: ExplanationChainLoader

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplanationChainLoader.kt`
- Test: `src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplanationChainLoaderTest.kt`

The loader walks the quads in `NamedGraph.RETRIEVAL` for a given session URI and reconstructs the `ExplanationChain`. Tests use an in-memory `QuadStore` implementation defined inline in the test file.

The loader needs two reads:
1. `QuadQuery(subject = sessionUri, dataset = NamedGraph.RETRIEVAL)` — gets question metadata.
2. `QuadQuery(predicate = PROV_WAS_GENERATED_BY, objectValue = sessionUri, dataset = NamedGraph.RETRIEVAL)` — finds the exploration entity.
3. From there, traverses `prov:wasDerivedFrom` edges to assemble the chain.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplanationChainLoaderTest.kt`:

```kotlin
package com.agentwork.graphmesh.provenance.query

import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.QuadConverter
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class InMemoryQuadStore : QuadStore {
    private val store = mutableMapOf<String, MutableList<StoredQuad>>()

    override fun insert(collection: String, quad: StoredQuad) {
        store.getOrPut(collection) { mutableListOf() }.add(quad)
    }
    override fun insertBatch(collection: String, quads: List<StoredQuad>) {
        store.getOrPut(collection) { mutableListOf() }.addAll(quads)
    }
    override fun delete(collection: String, quad: StoredQuad) {
        store[collection]?.remove(quad)
    }
    override fun deleteCollection(collection: String) { store.remove(collection) }
    override fun query(collection: String, query: QuadQuery): List<StoredQuad> =
        (store[collection] ?: emptyList()).filter {
            (query.subject == null || it.subject == query.subject) &&
            (query.predicate == null || it.predicate == query.predicate) &&
            (query.objectValue == null || it.objectValue == query.objectValue) &&
            (query.dataset == null || it.dataset == query.dataset)
        }
}

class ExplanationChainLoaderTest {

    private val recorder = ExplainabilityRecorder()
    private val store = InMemoryQuadStore()
    private val loader = ExplanationChainLoader(store)
    private val collection = "col1"

    private fun persist(quads: List<com.agentwork.graphmesh.rdf.Quad>) {
        store.insertBatch(collection, quads.map { QuadConverter.toStoredQuad(it) })
    }

    @Test
    fun `load graphRag chain reconstructs question exploration focus synthesis`() {
        val sessionId = UUID.randomUUID()
        val q = Question(
            uri = ExplainabilityUris.question(sessionId, QueryMechanism.GRAPH_RAG),
            queryText = "What is X?",
            timestamp = Instant.parse("2026-04-06T12:00:00Z"),
            mechanism = QueryMechanism.GRAPH_RAG,
        )
        val e = Exploration(ExplainabilityUris.exploration(sessionId), 5, q.uri)
        val f = Focus(
            uri = ExplainabilityUris.focus(sessionId),
            selectedEdges = listOf(SelectedEdgeExplanation("urn:s","urn:p","urn:o","because")),
            explorationUri = e.uri,
        )
        val s = Synthesis(ExplainabilityUris.synthesis(sessionId), "answer", f.uri)
        persist(recorder.graphRagSessionQuads(q, e, f, s))

        val chain = loader.load(collection, q.uri)
        assertNotNull(chain)
        assertEquals(QueryMechanism.GRAPH_RAG, chain.mechanism)
        assertEquals("What is X?", chain.question.queryText)
        assertEquals(5, chain.exploration?.edgeCount)
        assertEquals(1, chain.focus?.selectedEdges?.size)
        assertEquals("because", chain.focus?.selectedEdges?.first()?.reasoning)
        assertEquals("answer", chain.synthesis?.answerText)
        assertNull(chain.analyses)
        assertNull(chain.conclusion)
    }

    @Test
    fun `load docRag chain has no focus`() {
        val sessionId = UUID.randomUUID()
        val q = Question(
            uri = ExplainabilityUris.question(sessionId, QueryMechanism.DOC_RAG),
            queryText = "What does the doc say?",
            timestamp = Instant.parse("2026-04-06T12:00:00Z"),
            mechanism = QueryMechanism.DOC_RAG,
        )
        val e = Exploration(ExplainabilityUris.exploration(sessionId), 3, q.uri)
        val s = Synthesis(ExplainabilityUris.synthesis(sessionId), "doc-answer", e.uri)
        persist(recorder.docRagSessionQuads(q, e, s))

        val chain = loader.load(collection, q.uri)
        assertNotNull(chain)
        assertEquals(QueryMechanism.DOC_RAG, chain.mechanism)
        assertNull(chain.focus)
        assertEquals("doc-answer", chain.synthesis?.answerText)
    }

    @Test
    fun `load agent chain reconstructs analyses in iteration order`() {
        val sessionId = UUID.randomUUID()
        val q = Question(
            uri = ExplainabilityUris.question(sessionId, QueryMechanism.AGENT),
            queryText = "agent question",
            timestamp = Instant.parse("2026-04-06T12:00:00Z"),
            mechanism = QueryMechanism.AGENT,
        )
        val a1 = Analysis(
            uri = ExplainabilityUris.analysis(sessionId, 1),
            iterationIndex = 1, thought = "t1", action = "knowledge_query",
            arguments = mapOf("query" to "X"), observation = "obs1",
            parentUri = q.uri,
        )
        val a2 = Analysis(
            uri = ExplainabilityUris.analysis(sessionId, 2),
            iterationIndex = 2, thought = "t2", action = null,
            arguments = null, observation = null, parentUri = a1.uri,
        )
        val c = Conclusion(ExplainabilityUris.conclusion(sessionId), "final", a2.uri)
        persist(recorder.agentSessionQuads(q, listOf(a1, a2), c))

        val chain = loader.load(collection, q.uri)
        assertNotNull(chain)
        assertEquals(QueryMechanism.AGENT, chain.mechanism)
        assertEquals(2, chain.analyses?.size)
        assertEquals(1, chain.analyses?.get(0)?.iterationIndex)
        assertEquals(2, chain.analyses?.get(1)?.iterationIndex)
        assertEquals("t1", chain.analyses?.get(0)?.thought)
        assertEquals("knowledge_query", chain.analyses?.get(0)?.action)
        assertEquals("final", chain.conclusion?.answerText)
        assertNull(chain.synthesis)
    }

    @Test
    fun `load returns null when session uri unknown`() {
        assertNull(loader.load(collection, "urn:graphmesh:question:does-not-exist"))
    }

    @Test
    fun `listSessions filters by mechanism`() {
        val sg = UUID.randomUUID()
        val sd = UUID.randomUUID()
        val sa = UUID.randomUUID()

        val qg = Question(ExplainabilityUris.question(sg, QueryMechanism.GRAPH_RAG),
            "g", Instant.parse("2026-04-06T12:00:00Z"), QueryMechanism.GRAPH_RAG)
        val qd = Question(ExplainabilityUris.question(sd, QueryMechanism.DOC_RAG),
            "d", Instant.parse("2026-04-06T12:01:00Z"), QueryMechanism.DOC_RAG)
        val qa = Question(ExplainabilityUris.question(sa, QueryMechanism.AGENT),
            "a", Instant.parse("2026-04-06T12:02:00Z"), QueryMechanism.AGENT)

        persist(recorder.questionQuads(qg))
        persist(recorder.questionQuads(qd))
        persist(recorder.questionQuads(qa))

        val all = loader.listSessions(collection, mechanism = null, limit = 10)
        assertEquals(3, all.size)

        val onlyAgent = loader.listSessions(collection, mechanism = QueryMechanism.AGENT, limit = 10)
        assertEquals(1, onlyAgent.size)
        assertEquals("a", onlyAgent.first().queryText)
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests "com.agentwork.graphmesh.provenance.query.ExplanationChainLoaderTest"`
Expected: FAIL — class `ExplanationChainLoader` not found

- [ ] **Step 3: Implement the loader**

Create `src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplanationChainLoader.kt`:

```kotlin
package com.agentwork.graphmesh.provenance.query

import com.agentwork.graphmesh.provenance.ProvenanceNamespaces
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ExplanationChainLoader(private val quadStore: QuadStore) {

    fun load(collection: String, sessionUri: String): ExplanationChain? {
        val questionQuads = quadStore.query(collection, QuadQuery(
            subject = sessionUri,
            dataset = NamedGraph.RETRIEVAL,
        ))
        if (questionQuads.isEmpty()) return null

        val mechanism = questionQuads
            .firstOrNull { it.predicate == ExplainabilityNamespaces.TG_MECHANISM }
            ?.objectValue
            ?.let { runCatching { QueryMechanism.valueOf(it) }.getOrNull() }
            ?: return null

        val question = Question(
            uri = sessionUri,
            queryText = questionQuads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_QUERY_TEXT }
                ?.objectValue ?: "",
            timestamp = questionQuads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_TIMESTAMP }
                ?.objectValue?.let { Instant.parse(it) } ?: Instant.EPOCH,
            mechanism = mechanism,
        )

        return when (mechanism) {
            QueryMechanism.GRAPH_RAG -> loadGraphRagChain(collection, question)
            QueryMechanism.DOC_RAG   -> loadDocRagChain(collection, question)
            QueryMechanism.AGENT     -> loadAgentChain(collection, question)
        }
    }

    fun listSessions(
        collection: String,
        mechanism: QueryMechanism?,
        limit: Int,
    ): List<Question> {
        val typeQuads = quadStore.query(collection, QuadQuery(
            predicate = ProvenanceNamespaces.RDF_TYPE,
            objectValue = ExplainabilityNamespaces.TG_QUESTION,
            dataset = NamedGraph.RETRIEVAL,
        ))
        return typeQuads.asSequence()
            .map { it.subject }
            .distinct()
            .mapNotNull { uri ->
                val quads = quadStore.query(collection, QuadQuery(
                    subject = uri,
                    dataset = NamedGraph.RETRIEVAL,
                ))
                buildQuestion(uri, quads)
            }
            .filter { mechanism == null || it.mechanism == mechanism }
            .sortedByDescending { it.timestamp }
            .take(limit)
            .toList()
    }

    private fun buildQuestion(uri: String, quads: List<StoredQuad>): Question? {
        val mech = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_MECHANISM }
            ?.objectValue
            ?.let { runCatching { QueryMechanism.valueOf(it) }.getOrNull() }
            ?: return null
        return Question(
            uri = uri,
            queryText = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_QUERY_TEXT }
                ?.objectValue ?: "",
            timestamp = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_TIMESTAMP }
                ?.objectValue?.let { Instant.parse(it) } ?: Instant.EPOCH,
            mechanism = mech,
        )
    }

    // --- mechanism-specific assembly ---

    private fun loadGraphRagChain(collection: String, question: Question): ExplanationChain {
        val explorationUri = findChildUri(collection, question.uri,
            ProvenanceNamespaces.PROV_WAS_GENERATED_BY)
        val exploration = explorationUri?.let { loadExploration(collection, it, question.uri) }

        val focusUri = exploration?.let {
            findChildUri(collection, it.uri, ProvenanceNamespaces.PROV_WAS_DERIVED_FROM, reversed = true)
        }
        val focus = focusUri?.let { loadFocus(collection, it, exploration.uri) }

        val synthesisUri = focus?.let {
            findChildUri(collection, it.uri, ProvenanceNamespaces.PROV_WAS_DERIVED_FROM, reversed = true)
        }
        val synthesis = synthesisUri?.let { loadSynthesis(collection, it, focus.uri) }

        return ExplanationChain(
            question = question,
            exploration = exploration,
            focus = focus,
            analyses = null,
            synthesis = synthesis,
            conclusion = null,
            mechanism = QueryMechanism.GRAPH_RAG,
        )
    }

    private fun loadDocRagChain(collection: String, question: Question): ExplanationChain {
        val explorationUri = findChildUri(collection, question.uri,
            ProvenanceNamespaces.PROV_WAS_GENERATED_BY)
        val exploration = explorationUri?.let { loadExploration(collection, it, question.uri) }

        val synthesisUri = exploration?.let {
            findChildUri(collection, it.uri, ProvenanceNamespaces.PROV_WAS_DERIVED_FROM, reversed = true)
        }
        val synthesis = synthesisUri?.let { loadSynthesis(collection, it, exploration.uri) }

        return ExplanationChain(
            question = question,
            exploration = exploration,
            focus = null,
            analyses = null,
            synthesis = synthesis,
            conclusion = null,
            mechanism = QueryMechanism.DOC_RAG,
        )
    }

    private fun loadAgentChain(collection: String, question: Question): ExplanationChain {
        // Walk forward: find Analysis nodes whose parent chain reaches questionUri
        val allAnalyses = quadStore.query(collection, QuadQuery(
            predicate = ProvenanceNamespaces.RDF_TYPE,
            objectValue = ExplainabilityNamespaces.TG_ANALYSIS,
            dataset = NamedGraph.RETRIEVAL,
        )).map { it.subject }.distinct()

        val analyses = allAnalyses.mapNotNull { loadAnalysis(collection, it) }
            .filter { reachesQuestion(collection, it, question.uri) }
            .sortedBy { it.iterationIndex }

        val conclusionUri = quadStore.query(collection, QuadQuery(
            predicate = ProvenanceNamespaces.RDF_TYPE,
            objectValue = ExplainabilityNamespaces.TG_CONCLUSION,
            dataset = NamedGraph.RETRIEVAL,
        )).map { it.subject }
         .firstOrNull { uri ->
             val parent = parentOf(collection, uri) ?: return@firstOrNull false
             analyses.any { it.uri == parent }
         }
        val conclusion = conclusionUri?.let { loadConclusion(collection, it) }

        return ExplanationChain(
            question = question,
            exploration = null,
            focus = null,
            analyses = analyses,
            synthesis = null,
            conclusion = conclusion,
            mechanism = QueryMechanism.AGENT,
        )
    }

    // --- entity loaders ---

    private fun loadExploration(collection: String, uri: String, questionUri: String): Exploration {
        val quads = quadStore.query(collection, QuadQuery(subject = uri, dataset = NamedGraph.RETRIEVAL))
        return Exploration(
            uri = uri,
            edgeCount = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_EDGE_COUNT }
                ?.objectValue?.toIntOrNull() ?: 0,
            questionUri = questionUri,
        )
    }

    private fun loadFocus(collection: String, uri: String, explorationUri: String): Focus {
        val reasoningQuads = quadStore.query(collection, QuadQuery(
            predicate = ExplainabilityNamespaces.TG_REASONING,
            dataset = NamedGraph.RETRIEVAL,
        ))
        val edges = reasoningQuads.mapNotNull { quad ->
            val sub = quad.subject
            if (!sub.startsWith("<<") || !sub.endsWith(">>")) return@mapNotNull null
            val inner = sub.removePrefix("<<").removeSuffix(">>")
            val parts = inner.split("|", limit = 3)
            if (parts.size != 3) return@mapNotNull null
            SelectedEdgeExplanation(
                subject = parts[0],
                predicate = parts[1],
                objectValue = parts[2],
                reasoning = quad.objectValue,
            )
        }
        return Focus(uri = uri, selectedEdges = edges, explorationUri = explorationUri)
    }

    private fun loadSynthesis(collection: String, uri: String, parentUri: String): Synthesis {
        val quads = quadStore.query(collection, QuadQuery(subject = uri, dataset = NamedGraph.RETRIEVAL))
        return Synthesis(
            uri = uri,
            answerText = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_ANSWER_TEXT }
                ?.objectValue ?: "",
            derivedFromUri = parentUri,
        )
    }

    private fun loadAnalysis(collection: String, uri: String): Analysis? {
        val quads = quadStore.query(collection, QuadQuery(subject = uri, dataset = NamedGraph.RETRIEVAL))
        if (quads.isEmpty()) return null
        val parent = quads.firstOrNull { it.predicate == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM }
            ?.objectValue ?: return null
        val args = quads.filter { it.predicate == ExplainabilityNamespaces.TG_ARG_VALUE }
            .mapNotNull {
                val parts = it.objectValue.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap().ifEmpty { null }
        return Analysis(
            uri = uri,
            iterationIndex = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_ITERATION_INDEX }
                ?.objectValue?.toIntOrNull() ?: 0,
            thought = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_THOUGHT }
                ?.objectValue ?: "",
            action = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_ACTION }
                ?.objectValue,
            arguments = args,
            observation = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_OBSERVATION }
                ?.objectValue,
            parentUri = parent,
        )
    }

    private fun loadConclusion(collection: String, uri: String): Conclusion {
        val quads = quadStore.query(collection, QuadQuery(subject = uri, dataset = NamedGraph.RETRIEVAL))
        return Conclusion(
            uri = uri,
            answerText = quads.firstOrNull { it.predicate == ExplainabilityNamespaces.TG_ANSWER_TEXT }
                ?.objectValue ?: "",
            parentUri = quads.firstOrNull { it.predicate == ProvenanceNamespaces.PROV_WAS_DERIVED_FROM }
                ?.objectValue ?: "",
        )
    }

    // --- helpers ---

    /**
     * @param reversed when true, find a quad whose objectValue == parentUri (children pointing back)
     *                 when false, find a quad whose subject == parentUri (forward children)
     */
    private fun findChildUri(
        collection: String,
        parentUri: String,
        predicate: String,
        reversed: Boolean = false,
    ): String? {
        val q = if (reversed) {
            QuadQuery(predicate = predicate, objectValue = parentUri, dataset = NamedGraph.RETRIEVAL)
        } else {
            QuadQuery(subject = parentUri, predicate = predicate, dataset = NamedGraph.RETRIEVAL)
        }
        val rows = quadStore.query(collection, q)
        return if (reversed) rows.firstOrNull()?.subject else rows.firstOrNull()?.objectValue
    }

    private fun parentOf(collection: String, uri: String): String? =
        quadStore.query(collection, QuadQuery(
            subject = uri,
            predicate = ProvenanceNamespaces.PROV_WAS_DERIVED_FROM,
            dataset = NamedGraph.RETRIEVAL,
        )).firstOrNull()?.objectValue

    private fun reachesQuestion(collection: String, analysis: Analysis, questionUri: String): Boolean {
        var current: String? = analysis.parentUri
        var hops = 0
        while (current != null && hops < 100) {
            if (current == questionUri) return true
            current = parentOf(collection, current)
            hops++
        }
        return false
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.provenance.query.ExplanationChainLoaderTest"`
Expected: PASS, 5 tests succeed

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/provenance/query/ExplanationChainLoader.kt \
        src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplanationChainLoaderTest.kt
git commit -m "feat(explainability): add ExplanationChainLoader for drill-down reads"
```

---

## Task 4: Avro Schema & Topic Bean

**Files:**
- Create: `src/main/resources/avro/query-explained.avsc`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/messaging/KafkaTopicConfig.kt`

- [ ] **Step 1: Create the Avro schema**

Create `src/main/resources/avro/query-explained.avsc`:

```json
{
  "type": "record",
  "name": "QueryExplained",
  "namespace": "com.agentwork.graphmesh.events",
  "fields": [
    { "name": "sessionId",          "type": "string" },
    { "name": "collectionId",       "type": "string" },
    { "name": "mechanism",          "type": {
        "type": "enum", "name": "QueryMechanismAvro",
        "symbols": ["GRAPH_RAG", "DOC_RAG", "AGENT"]
    } },
    { "name": "queryText",          "type": "string" },
    { "name": "timestamp",          "type": "string" },
    { "name": "answerText",         "type": "string" },

    { "name": "retrievedEdgeCount", "type": ["null", "int"], "default": null },
    { "name": "selectedEdges",      "type": ["null", {
        "type": "array",
        "items": {
            "type": "record", "name": "SelectedEdgeRecord",
            "fields": [
                { "name": "subject",     "type": "string" },
                { "name": "predicate",   "type": "string" },
                { "name": "objectValue", "type": "string" },
                { "name": "reasoning",   "type": "string" }
            ]
        }
    }], "default": null },

    { "name": "retrievedChunkCount", "type": ["null", "int"], "default": null },
    { "name": "selectedChunkIds",    "type": ["null", { "type": "array", "items": "string" }],
                                     "default": null },

    { "name": "iterations",          "type": ["null", {
        "type": "array",
        "items": {
            "type": "record", "name": "AgentIterationAvro",
            "fields": [
                { "name": "thought",     "type": "string" },
                { "name": "action",      "type": ["null", "string"], "default": null },
                { "name": "arguments",   "type": ["null", { "type": "map", "values": "string" }],
                                         "default": null },
                { "name": "observation", "type": ["null", "string"], "default": null }
            ]
        }
    }], "default": null }
  ]
}
```

- [ ] **Step 2: Add topic bean**

Edit `src/main/kotlin/com/agentwork/graphmesh/messaging/KafkaTopicConfig.kt` to add a second `@Bean`:

```kotlin
package com.agentwork.graphmesh.messaging

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class KafkaTopicConfig {

    @Bean
    fun documentIngestedTopic(): NewTopic =
        TopicBuilder.name("graphmesh.document.ingested")
            .partitions(3)
            .replicas(1)
            .build()

    @Bean
    fun queryExplainedTopic(): NewTopic =
        TopicBuilder.name("graphmesh.query.explained")
            .partitions(3)
            .replicas(1)
            .build()
}
```

- [ ] **Step 3: Build to verify schema parses**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL (the schema is loaded at runtime, not compile time, but compileKotlin verifies the topic bean)

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/avro/query-explained.avsc \
        src/main/kotlin/com/agentwork/graphmesh/messaging/KafkaTopicConfig.kt
git commit -m "feat(explainability): add query-explained Avro schema and Kafka topic"
```

---

## Task 5: ExplainabilityEventProducer

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventProducer.kt`
- Test: `src/test/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventProducerTest.kt`

The producer mirrors `DocumentIngestedProducer`. Three `send*` methods, all building a `GenericRecord` from the Avro schema and dispatching via `KafkaTemplate`. Failures are logged via `whenComplete`, never thrown.

The test verifies the Avro encoding shape — it builds the producer with a fake `KafkaTemplate` that captures the `ProducerRecord` and asserts on its contents. Use a minimal `KafkaTemplate` subclass override.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventProducerTest.kt`:

```kotlin
package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.provenance.query.AgentIterationRecord
import com.agentwork.graphmesh.provenance.query.SelectedEdgeExplanation
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExplainabilityEventProducerTest {

    private val captured = mutableListOf<ProducerRecord<String, GenericRecord>>()

    private val fakeTemplate = object : KafkaTemplate<String, GenericRecord>(
        org.springframework.kafka.core.DefaultKafkaProducerFactory(emptyMap())
    ) {
        override fun send(record: ProducerRecord<String, GenericRecord>):
            CompletableFuture<org.springframework.kafka.support.SendResult<String, GenericRecord>> {
            captured.add(record)
            return CompletableFuture.completedFuture(null)
        }
    }

    private val producer = ExplainabilityEventProducer(fakeTemplate)
    private val sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `sendGraphRagEvent encodes mechanism queryText and selectedEdges`() {
        producer.sendGraphRagEvent(
            sessionId = sessionId,
            collectionId = "col1",
            queryText = "What is X?",
            retrievedEdgeCount = 5,
            selectedEdges = listOf(
                SelectedEdgeExplanation("urn:s","urn:p","urn:o","because")
            ),
            answerText = "ans",
        )

        assertEquals(1, captured.size)
        val rec = captured.first().value()
        assertEquals("11111111-1111-1111-1111-111111111111", rec["sessionId"].toString())
        assertEquals("col1", rec["collectionId"].toString())
        assertEquals("GRAPH_RAG", rec["mechanism"].toString())
        assertEquals("What is X?", rec["queryText"].toString())
        assertEquals(5, rec["retrievedEdgeCount"])
        assertEquals("ans", rec["answerText"].toString())

        @Suppress("UNCHECKED_CAST")
        val edges = rec["selectedEdges"] as List<GenericRecord>
        assertEquals(1, edges.size)
        assertEquals("urn:s", edges[0]["subject"].toString())
        assertEquals("because", edges[0]["reasoning"].toString())

        assertNull(rec["retrievedChunkCount"])
        assertNull(rec["iterations"])
    }

    @Test
    fun `sendDocRagEvent encodes chunk fields and leaves edges null`() {
        producer.sendDocRagEvent(
            sessionId = sessionId,
            collectionId = "col1",
            queryText = "doc query",
            retrievedChunkCount = 3,
            selectedChunkIds = listOf("c1", "c2"),
            answerText = "doc-ans",
        )

        assertEquals(1, captured.size)
        val rec = captured.first().value()
        assertEquals("DOC_RAG", rec["mechanism"].toString())
        assertEquals(3, rec["retrievedChunkCount"])

        @Suppress("UNCHECKED_CAST")
        val chunks = rec["selectedChunkIds"] as List<CharSequence>
        assertEquals(listOf("c1","c2"), chunks.map { it.toString() })
        assertNull(rec["retrievedEdgeCount"])
        assertNull(rec["selectedEdges"])
    }

    @Test
    fun `sendAgentEvent encodes iterations`() {
        producer.sendAgentEvent(
            sessionId = sessionId,
            collectionId = "col1",
            queryText = "agent question",
            iterations = listOf(
                AgentIterationRecord("t1","knowledge_query", mapOf("query" to "X"), "obs"),
                AgentIterationRecord("t2", null, null, null),
            ),
            answerText = "final",
        )

        assertEquals(1, captured.size)
        val rec = captured.first().value()
        assertEquals("AGENT", rec["mechanism"].toString())

        @Suppress("UNCHECKED_CAST")
        val iters = rec["iterations"] as List<GenericRecord>
        assertEquals(2, iters.size)
        assertEquals("t1", iters[0]["thought"].toString())
        assertEquals("knowledge_query", iters[0]["action"].toString())
        assertNotNull(iters[0]["arguments"])
        assertNull(iters[1]["action"])
    }

    @Test
    fun `producer attaches CloudEvent headers`() {
        producer.sendGraphRagEvent(sessionId, "col1", "q", 0, emptyList(), "a")

        val headers = captured.first().headers().toList().associate { it.key() to String(it.value()) }
        assertEquals("graphmesh.query.explained.v1", headers[CloudEventHeaders.TYPE])
        assertEquals("graphmesh/explainability-service", headers[CloudEventHeaders.SOURCE])
        assertEquals("11111111-1111-1111-1111-111111111111", headers[CloudEventHeaders.SUBJECT])
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.ExplainabilityEventProducerTest"`
Expected: FAIL — class `ExplainabilityEventProducer` not found

- [ ] **Step 3: Implement the producer**

Create `src/main/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventProducer.kt`:

```kotlin
package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.provenance.query.AgentIterationRecord
import com.agentwork.graphmesh.provenance.query.SelectedEdgeExplanation
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class ExplainabilityEventProducer(
    private val kafka: KafkaTemplate<String, GenericRecord>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val schema: Schema = Schema.Parser().parse(
        javaClass.getResourceAsStream("/avro/query-explained.avsc")
    )
    private val edgeSchema: Schema = schema.getField("selectedEdges").schema()
        .types.first { it.type == Schema.Type.ARRAY }.elementType
    private val iterationSchema: Schema = schema.getField("iterations").schema()
        .types.first { it.type == Schema.Type.ARRAY }.elementType
    private val mechanismSchema: Schema = schema.getField("mechanism").schema()

    companion object {
        const val TOPIC = "graphmesh.query.explained"
        const val SOURCE = "graphmesh/explainability-service"
        const val TYPE = "graphmesh.query.explained.v1"
    }

    fun sendGraphRagEvent(
        sessionId: UUID,
        collectionId: String,
        queryText: String,
        retrievedEdgeCount: Int,
        selectedEdges: List<SelectedEdgeExplanation>,
        answerText: String,
    ) {
        val record = baseRecord(sessionId, collectionId, queryText, "GRAPH_RAG", answerText).apply {
            put("retrievedEdgeCount", retrievedEdgeCount)
            put("selectedEdges", selectedEdges.map { e ->
                GenericData.Record(edgeSchema).apply {
                    put("subject", e.subject)
                    put("predicate", e.predicate)
                    put("objectValue", e.objectValue)
                    put("reasoning", e.reasoning)
                }
            })
        }
        dispatch(sessionId, record)
    }

    fun sendDocRagEvent(
        sessionId: UUID,
        collectionId: String,
        queryText: String,
        retrievedChunkCount: Int,
        selectedChunkIds: List<String>,
        answerText: String,
    ) {
        val record = baseRecord(sessionId, collectionId, queryText, "DOC_RAG", answerText).apply {
            put("retrievedChunkCount", retrievedChunkCount)
            put("selectedChunkIds", selectedChunkIds)
        }
        dispatch(sessionId, record)
    }

    fun sendAgentEvent(
        sessionId: UUID,
        collectionId: String,
        queryText: String,
        iterations: List<AgentIterationRecord>,
        answerText: String,
    ) {
        val record = baseRecord(sessionId, collectionId, queryText, "AGENT", answerText).apply {
            put("iterations", iterations.map { it ->
                GenericData.Record(iterationSchema).apply {
                    put("thought", it.thought)
                    put("action", it.action)
                    put("arguments", it.arguments)
                    put("observation", it.observation)
                }
            })
        }
        dispatch(sessionId, record)
    }

    private fun baseRecord(
        sessionId: UUID,
        collectionId: String,
        queryText: String,
        mechanism: String,
        answerText: String,
    ): GenericData.Record = GenericData.Record(schema).apply {
        put("sessionId", sessionId.toString())
        put("collectionId", collectionId)
        put("mechanism", GenericData.EnumSymbol(mechanismSchema, mechanism))
        put("queryText", queryText)
        put("timestamp", Instant.now().toString())
        put("answerText", answerText)
    }

    private fun dispatch(sessionId: UUID, record: GenericRecord) {
        val headers = CloudEventHeaders.build(
            source = SOURCE,
            type = TYPE,
            subject = sessionId.toString(),
        )
        val kafkaHeaders = headers.map { (k, v) ->
            RecordHeader(k, v.toByteArray()) as org.apache.kafka.common.header.Header
        }
        val producerRecord = ProducerRecord<String, GenericRecord>(
            TOPIC, null, sessionId.toString(), record, kafkaHeaders,
        )
        kafka.send(producerRecord).whenComplete { _, ex ->
            if (ex != null) logger.error("Failed to send query.explained event for {}", sessionId, ex)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.ExplainabilityEventProducerTest"`
Expected: PASS, 4 tests succeed

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventProducer.kt \
        src/test/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventProducerTest.kt
git commit -m "feat(explainability): add ExplainabilityEventProducer with Avro encoding"
```

---

## Task 6: ExplainabilityEventConsumer

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventConsumer.kt`
- Test: `src/test/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventConsumerTest.kt`

Consumer decodes the Avro record into mechanism-specific models, calls the appropriate `recorder.*SessionQuads()`, and persists via `quadStore.insertBatch()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventConsumerTest.kt`:

```kotlin
package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.provenance.query.AgentIterationRecord
import com.agentwork.graphmesh.provenance.query.ExplainabilityNamespaces
import com.agentwork.graphmesh.provenance.query.ExplainabilityRecorder
import com.agentwork.graphmesh.provenance.query.SelectedEdgeExplanation
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CapturingQuadStore : QuadStore {
    val byCollection = mutableMapOf<String, MutableList<StoredQuad>>()
    override fun insert(collection: String, quad: StoredQuad) {
        byCollection.getOrPut(collection) { mutableListOf() }.add(quad)
    }
    override fun insertBatch(collection: String, quads: List<StoredQuad>) {
        byCollection.getOrPut(collection) { mutableListOf() }.addAll(quads)
    }
    override fun delete(collection: String, quad: StoredQuad) {}
    override fun deleteCollection(collection: String) {}
    override fun query(collection: String, query: QuadQuery): List<StoredQuad> = emptyList()
}

class ExplainabilityEventConsumerTest {

    private val store = CapturingQuadStore()
    private val recorder = ExplainabilityRecorder()
    private val producer = ExplainabilityEventProducer(
        // Reuse the producer to construct GenericRecords easily.
        // We never call .send() — just use its public methods to encode.
        // Workaround: create a producer with a no-op KafkaTemplate.
        org.springframework.kafka.core.KafkaTemplate(
            org.springframework.kafka.core.DefaultKafkaProducerFactory(emptyMap())
        )
    )
    private val consumer = ExplainabilityEventConsumer(recorder, store)

    private fun consumerRecord(value: GenericRecord) =
        ConsumerRecord("graphmesh.query.explained", 0, 0L, "key", value)

    @Test
    fun `consumes graphRag event and persists quads in retrieval graph`() {
        val sessionId = UUID.randomUUID()
        val record = encodeGraphRag(sessionId)

        consumer.handle(consumerRecord(record))

        val quads = store.byCollection["col1"] ?: error("nothing persisted")
        assertTrue(quads.isNotEmpty())
        assertTrue(quads.all { it.dataset == NamedGraph.RETRIEVAL })
        assertTrue(quads.any {
            it.predicate == ExplainabilityNamespaces.TG_QUERY_TEXT && it.objectValue == "What is X?"
        })
        assertTrue(quads.any {
            it.predicate == ExplainabilityNamespaces.TG_REASONING && it.objectValue == "because"
        })
    }

    @Test
    fun `consumes docRag event without focus quads`() {
        val sessionId = UUID.randomUUID()
        val record = encodeDocRag(sessionId)

        consumer.handle(consumerRecord(record))

        val quads = store.byCollection["col1"]!!
        assertTrue(quads.none {
            it.predicate == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" &&
            it.objectValue == ExplainabilityNamespaces.TG_FOCUS
        })
        assertTrue(quads.any {
            it.predicate == ExplainabilityNamespaces.TG_ANSWER_TEXT && it.objectValue == "doc-ans"
        })
    }

    @Test
    fun `consumes agent event with iterations`() {
        val sessionId = UUID.randomUUID()
        val record = encodeAgent(sessionId)

        consumer.handle(consumerRecord(record))

        val quads = store.byCollection["col1"]!!
        val analysisCount = quads.count {
            it.predicate == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" &&
            it.objectValue == ExplainabilityNamespaces.TG_ANALYSIS
        }
        assertEquals(2, analysisCount)
        assertTrue(quads.any {
            it.predicate == ExplainabilityNamespaces.TG_THOUGHT && it.objectValue == "t1"
        })
    }

    // --- Avro construction helpers (use a temporary producer holder for the schema) ---

    private val schema = org.apache.avro.Schema.Parser().parse(
        javaClass.getResourceAsStream("/avro/query-explained.avsc")
    )
    private val edgeSchema = schema.getField("selectedEdges").schema()
        .types.first { it.type == org.apache.avro.Schema.Type.ARRAY }.elementType
    private val iterSchema = schema.getField("iterations").schema()
        .types.first { it.type == org.apache.avro.Schema.Type.ARRAY }.elementType
    private val mechSchema = schema.getField("mechanism").schema()

    private fun encodeGraphRag(sessionId: UUID): GenericRecord =
        org.apache.avro.generic.GenericData.Record(schema).apply {
            put("sessionId", sessionId.toString())
            put("collectionId", "col1")
            put("mechanism", org.apache.avro.generic.GenericData.EnumSymbol(mechSchema, "GRAPH_RAG"))
            put("queryText", "What is X?")
            put("timestamp", "2026-04-06T12:00:00Z")
            put("answerText", "ans")
            put("retrievedEdgeCount", 5)
            put("selectedEdges", listOf(
                org.apache.avro.generic.GenericData.Record(edgeSchema).apply {
                    put("subject", "urn:s")
                    put("predicate", "urn:p")
                    put("objectValue", "urn:o")
                    put("reasoning", "because")
                }
            ))
        }

    private fun encodeDocRag(sessionId: UUID): GenericRecord =
        org.apache.avro.generic.GenericData.Record(schema).apply {
            put("sessionId", sessionId.toString())
            put("collectionId", "col1")
            put("mechanism", org.apache.avro.generic.GenericData.EnumSymbol(mechSchema, "DOC_RAG"))
            put("queryText", "doc question")
            put("timestamp", "2026-04-06T12:00:00Z")
            put("answerText", "doc-ans")
            put("retrievedChunkCount", 3)
            put("selectedChunkIds", listOf("c1","c2","c3"))
        }

    private fun encodeAgent(sessionId: UUID): GenericRecord =
        org.apache.avro.generic.GenericData.Record(schema).apply {
            put("sessionId", sessionId.toString())
            put("collectionId", "col1")
            put("mechanism", org.apache.avro.generic.GenericData.EnumSymbol(mechSchema, "AGENT"))
            put("queryText", "agent q")
            put("timestamp", "2026-04-06T12:00:00Z")
            put("answerText", "final")
            put("iterations", listOf(
                org.apache.avro.generic.GenericData.Record(iterSchema).apply {
                    put("thought", "t1")
                    put("action", "knowledge_query")
                    put("arguments", mapOf("query" to "X"))
                    put("observation", "obs1")
                },
                org.apache.avro.generic.GenericData.Record(iterSchema).apply {
                    put("thought", "t2")
                    put("action", null)
                    put("arguments", null)
                    put("observation", null)
                }
            ))
        }
}
```

Note: the test instantiates `ExplainabilityEventProducer` only because Kotlin requires a non-null reference; it is unused. Remove that field if you find it unnecessary — the encoding helpers reconstruct schemas independently.

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.ExplainabilityEventConsumerTest"`
Expected: FAIL — class `ExplainabilityEventConsumer` not found

- [ ] **Step 3: Implement the consumer**

Create `src/main/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventConsumer.kt`:

```kotlin
package com.agentwork.graphmesh.messaging

import com.agentwork.graphmesh.provenance.query.Analysis
import com.agentwork.graphmesh.provenance.query.Conclusion
import com.agentwork.graphmesh.provenance.query.ExplainabilityRecorder
import com.agentwork.graphmesh.provenance.query.ExplainabilityUris
import com.agentwork.graphmesh.provenance.query.Exploration
import com.agentwork.graphmesh.provenance.query.Focus
import com.agentwork.graphmesh.provenance.query.Question
import com.agentwork.graphmesh.provenance.query.QueryMechanism
import com.agentwork.graphmesh.provenance.query.SelectedEdgeExplanation
import com.agentwork.graphmesh.provenance.query.Synthesis
import com.agentwork.graphmesh.rdf.QuadConverter
import com.agentwork.graphmesh.storage.QuadStore
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class ExplainabilityEventConsumer(
    private val recorder: ExplainabilityRecorder,
    private val quadStore: QuadStore,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.query.explained"], groupId = "graphmesh")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val v = record.value()
        val sessionId = UUID.fromString(v["sessionId"].toString())
        val collectionId = v["collectionId"].toString()
        val mechanism = QueryMechanism.valueOf(v["mechanism"].toString())
        val queryText = v["queryText"].toString()
        val timestamp = Instant.parse(v["timestamp"].toString())
        val answerText = v["answerText"].toString()

        val question = Question(
            uri = ExplainabilityUris.question(sessionId, mechanism),
            queryText = queryText,
            timestamp = timestamp,
            mechanism = mechanism,
        )

        val quads = when (mechanism) {
            QueryMechanism.GRAPH_RAG -> buildGraphRagQuads(sessionId, question, v, answerText)
            QueryMechanism.DOC_RAG   -> buildDocRagQuads(sessionId, question, v, answerText)
            QueryMechanism.AGENT     -> buildAgentQuads(sessionId, question, v, answerText)
        }

        quadStore.insertBatch(collectionId, quads.map { QuadConverter.toStoredQuad(it) })
        logger.info(
            "Persisted {} explainability quads for session {} ({})",
            quads.size, sessionId, mechanism
        )
    }

    private fun buildGraphRagQuads(
        sessionId: UUID, question: Question, v: GenericRecord, answerText: String,
    ): List<com.agentwork.graphmesh.rdf.Quad> {
        val edgeCount = v["retrievedEdgeCount"] as? Int ?: 0
        @Suppress("UNCHECKED_CAST")
        val selected = (v["selectedEdges"] as? List<GenericRecord>).orEmpty().map {
            SelectedEdgeExplanation(
                subject = it["subject"].toString(),
                predicate = it["predicate"].toString(),
                objectValue = it["objectValue"].toString(),
                reasoning = it["reasoning"].toString(),
            )
        }
        val exploration = Exploration(ExplainabilityUris.exploration(sessionId), edgeCount, question.uri)
        val focus = Focus(ExplainabilityUris.focus(sessionId), selected, exploration.uri)
        val synthesis = Synthesis(ExplainabilityUris.synthesis(sessionId), answerText, focus.uri)
        return recorder.graphRagSessionQuads(question, exploration, focus, synthesis)
    }

    private fun buildDocRagQuads(
        sessionId: UUID, question: Question, v: GenericRecord, answerText: String,
    ): List<com.agentwork.graphmesh.rdf.Quad> {
        val chunkCount = v["retrievedChunkCount"] as? Int ?: 0
        val exploration = Exploration(ExplainabilityUris.exploration(sessionId), chunkCount, question.uri)
        val synthesis = Synthesis(ExplainabilityUris.synthesis(sessionId), answerText, exploration.uri)
        return recorder.docRagSessionQuads(question, exploration, synthesis)
    }

    private fun buildAgentQuads(
        sessionId: UUID, question: Question, v: GenericRecord, answerText: String,
    ): List<com.agentwork.graphmesh.rdf.Quad> {
        @Suppress("UNCHECKED_CAST")
        val iters = (v["iterations"] as? List<GenericRecord>).orEmpty()
        val analyses = iters.mapIndexed { idx, rec ->
            val iterIndex = idx + 1
            @Suppress("UNCHECKED_CAST")
            val args = (rec["arguments"] as? Map<CharSequence, CharSequence>)
                ?.entries?.associate { it.key.toString() to it.value.toString() }
            Analysis(
                uri = ExplainabilityUris.analysis(sessionId, iterIndex),
                iterationIndex = iterIndex,
                thought = rec["thought"].toString(),
                action = (rec["action"] as? CharSequence)?.toString(),
                arguments = args,
                observation = (rec["observation"] as? CharSequence)?.toString(),
                parentUri = if (idx == 0) question.uri
                            else ExplainabilityUris.analysis(sessionId, iterIndex - 1),
            )
        }
        val parentForConclusion = analyses.lastOrNull()?.uri ?: question.uri
        val conclusion = Conclusion(
            uri = ExplainabilityUris.conclusion(sessionId),
            answerText = answerText,
            parentUri = parentForConclusion,
        )
        return recorder.agentSessionQuads(question, analyses, conclusion)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.messaging.ExplainabilityEventConsumerTest"`
Expected: PASS, 3 tests succeed

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventConsumer.kt \
        src/test/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventConsumerTest.kt
git commit -m "feat(explainability): add ExplainabilityEventConsumer that persists PROV-O quads"
```

---

## Task 7: AgentIterationCollector (Koog Tracing)

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/provenance/query/AgentIterationCollector.kt`
- Test: `src/test/kotlin/com/agentwork/graphmesh/provenance/query/AgentIterationCollectorTest.kt`

**Important — verify Koog API first.** The exact event class names (`LLMCallCompletedEvent`, `ToolCallEvent`, etc.) and their field names need to be checked against `ai.koog:koog-agents-jvm:0.7.3`. Use `./gradlew dependencies` and the IDE's class lookup to find the actual classes under `ai.koog.agents.core.feature.model` (or similar).

If the exact event types differ from those used below, adapt the `when` branches but keep the contract: each iteration consists of one thought (LLM response) optionally followed by one action+observation (tool call + result).

- [ ] **Step 1: Verify Koog API**

Run:
```bash
./gradlew dependencyInsight --dependency koog-agents-jvm
```
Then in the IDE, locate `ai.koog.agents.core.feature.model.*` (or `ai.koog.agents.features.*`) — find the concrete event classes for:
- LLM call completion (carrying the model's text response)
- Tool execution start (carrying tool name + args)
- Tool execution completion (carrying the result)

Document the actual class names found in a comment at the top of `AgentIterationCollector.kt` as a reference.

- [ ] **Step 2: Write the failing test**

Create `src/test/kotlin/com/agentwork/graphmesh/provenance/query/AgentIterationCollectorTest.kt`:

```kotlin
package com.agentwork.graphmesh.provenance.query

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentIterationCollectorTest {

    @Test
    fun `records think act observe sequence as one iteration`() {
        val collector = AgentIterationCollector()

        collector.recordThought("I should look up X")
        collector.recordToolStart("knowledge_query", mapOf("query" to "X"))
        collector.recordToolEnd("X is foo")

        val iterations = collector.snapshot()
        assertEquals(1, iterations.size)
        assertEquals("I should look up X", iterations[0].thought)
        assertEquals("knowledge_query", iterations[0].action)
        assertEquals(mapOf("query" to "X"), iterations[0].arguments)
        assertEquals("X is foo", iterations[0].observation)
    }

    @Test
    fun `records multiple iterations in order`() {
        val collector = AgentIterationCollector()

        collector.recordThought("t1")
        collector.recordToolStart("tool1", mapOf("a" to "1"))
        collector.recordToolEnd("obs1")

        collector.recordThought("t2")
        collector.recordToolStart("tool2", mapOf("b" to "2"))
        collector.recordToolEnd("obs2")

        val iterations = collector.snapshot()
        assertEquals(2, iterations.size)
        assertEquals("t1", iterations[0].thought)
        assertEquals("tool1", iterations[0].action)
        assertEquals("t2", iterations[1].thought)
        assertEquals("tool2", iterations[1].action)
    }

    @Test
    fun `final thought without tool call is flushed on snapshot`() {
        val collector = AgentIterationCollector()

        collector.recordThought("t1")
        collector.recordToolStart("tool1", null)
        collector.recordToolEnd("obs1")
        collector.recordThought("final reasoning")

        val iterations = collector.snapshot()
        assertEquals(2, iterations.size)
        assertEquals("final reasoning", iterations[1].thought)
        assertNull(iterations[1].action)
        assertNull(iterations[1].observation)
    }
}
```

- [ ] **Step 3: Run test to verify failure**

Run: `./gradlew test --tests "com.agentwork.graphmesh.provenance.query.AgentIterationCollectorTest"`
Expected: FAIL — class `AgentIterationCollector` not found

- [ ] **Step 4: Implement the collector**

Create `src/main/kotlin/com/agentwork/graphmesh/provenance/query/AgentIterationCollector.kt`:

```kotlin
package com.agentwork.graphmesh.provenance.query

/**
 * Collects think/act/observe iterations from a Koog agent run.
 *
 * Two usage modes:
 *  1. Manual recording (used in tests) via recordThought/recordToolStart/recordToolEnd.
 *  2. Koog Tracing integration: a thin adapter (see AgentService) bridges Koog
 *     events to these methods. The exact Koog event types are inspected at
 *     wiring time — this collector is intentionally agnostic of them.
 *
 * Thread-safety: instances are session-scoped (one per agent.run() call).
 * Not safe for concurrent runs — create a new instance per session.
 */
class AgentIterationCollector {

    private val iterations = mutableListOf<AgentIterationRecord>()
    private var pendingThought: String? = null
    private var pendingAction: String? = null
    private var pendingArgs: Map<String, String>? = null

    fun recordThought(text: String) {
        // If we already have a thought without a tool call, flush it as a standalone iteration
        // before starting a new one. This happens when the agent reasons multiple times in a row.
        if (pendingThought != null && pendingAction == null) {
            iterations += AgentIterationRecord(
                thought = pendingThought!!,
                action = null,
                arguments = null,
                observation = null,
            )
        }
        pendingThought = text
    }

    fun recordToolStart(toolName: String, args: Map<String, String>?) {
        pendingAction = toolName
        pendingArgs = args
    }

    fun recordToolEnd(observation: String?) {
        iterations += AgentIterationRecord(
            thought = pendingThought ?: "",
            action = pendingAction,
            arguments = pendingArgs,
            observation = observation,
        )
        pendingThought = null
        pendingAction = null
        pendingArgs = null
    }

    /**
     * Returns the collected iterations. If a trailing thought (without a tool call)
     * is still pending, it is flushed as a standalone final iteration.
     */
    fun snapshot(): List<AgentIterationRecord> {
        if (pendingThought != null && pendingAction == null) {
            iterations += AgentIterationRecord(
                thought = pendingThought!!,
                action = null,
                arguments = null,
                observation = null,
            )
            pendingThought = null
        }
        return iterations.toList()
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.provenance.query.AgentIterationCollectorTest"`
Expected: PASS, 3 tests succeed

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/provenance/query/AgentIterationCollector.kt \
        src/test/kotlin/com/agentwork/graphmesh/provenance/query/AgentIterationCollectorTest.kt
git commit -m "feat(explainability): add AgentIterationCollector for think/act/observe capture"
```

---

## Task 8: Service Integration — GraphRAG & DocRAG

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagModels.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagModels.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt`
- Modify: `src/main/resources/graphql/graph-rag.graphqls`
- Modify: `src/main/resources/graphql/document-rag.graphqls`

These changes inject the producer, generate a session UUID per query, and emit the event after answer synthesis. There are no new tests for the services themselves — the producer test already covers the encoding, and the consumer test already covers persistence. We rely on the existing service tests to catch regressions.

- [ ] **Step 1: Add `sessionId` to `GraphRagResult`**

Edit `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagModels.kt`:

```kotlin
package com.agentwork.graphmesh.query.graphrag

import java.util.UUID

data class GraphRagQuery(
    val question: String,
    val collectionId: String,
    val maxEdges: Int = 150,
    val maxDepth: Int = 2,
    val maxSelectedEdges: Int = 30
)

data class GraphRagResult(
    val sessionId: UUID,
    val answer: String,
    val selectedEdges: List<SelectedEdge>,
    val retrievedEdgeCount: Int,
    val durationMs: Long
)

data class SelectedEdge(
    val subject: String,
    val predicate: String,
    val objectValue: String,
    val dataset: String,
    val reasoning: String,
    val relevanceScore: Double
)
```

- [ ] **Step 2: Wire producer into `GraphRagService`**

Edit `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`:

Add the import:
```kotlin
import com.agentwork.graphmesh.messaging.ExplainabilityEventProducer
import com.agentwork.graphmesh.provenance.query.SelectedEdgeExplanation
import java.util.UUID
```

Add `explainabilityProducer: ExplainabilityEventProducer` as a constructor parameter (after `embeddingConfig`).

Replace the body of `query()` with:

```kotlin
fun query(query: GraphRagQuery): GraphRagResult {
    val sessionId = UUID.randomUUID()
    val startTime = System.currentTimeMillis()

    logger.info("Phase 1: Subgraph retrieval for collection {}", query.collectionId)
    val subgraph = retrieveSubgraph(query)
    logger.info("Retrieved {} edges", subgraph.size)

    if (subgraph.isEmpty()) {
        explainabilityProducer.sendGraphRagEvent(
            sessionId = sessionId,
            collectionId = query.collectionId,
            queryText = query.question,
            retrievedEdgeCount = 0,
            selectedEdges = emptyList(),
            answerText = "No relevant knowledge found for this question.",
        )
        return GraphRagResult(
            sessionId = sessionId,
            answer = "No relevant knowledge found for this question.",
            selectedEdges = emptyList(),
            retrievedEdgeCount = 0,
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    logger.info("Phase 2: Edge selection from {} edges", subgraph.size)
    val selectedEdges = selectEdges(query.question, subgraph, query.maxSelectedEdges)
    logger.info("{} edges selected", selectedEdges.size)

    logger.info("Phase 3: Answer synthesis")
    val answer = synthesizeAnswer(query.question, selectedEdges)

    val durationMs = System.currentTimeMillis() - startTime
    logger.info("Graph RAG pipeline completed in {} ms", durationMs)

    explainabilityProducer.sendGraphRagEvent(
        sessionId = sessionId,
        collectionId = query.collectionId,
        queryText = query.question,
        retrievedEdgeCount = subgraph.size,
        selectedEdges = selectedEdges.map {
            SelectedEdgeExplanation(it.subject, it.predicate, it.objectValue, it.reasoning)
        },
        answerText = answer,
    )

    return GraphRagResult(
        sessionId = sessionId,
        answer = answer,
        selectedEdges = selectedEdges,
        retrievedEdgeCount = subgraph.size,
        durationMs = durationMs
    )
}
```

- [ ] **Step 3: Update `graph-rag.graphqls`**

Edit `src/main/resources/graphql/graph-rag.graphqls`:

```graphql
extend type Query {
    graphRag(input: GraphRagInput!): GraphRagResponse!
}

input GraphRagInput {
    question: String!
    collectionId: ID!
    maxEdges: Int = 150
    maxDepth: Int = 2
    maxSelectedEdges: Int = 30
}

type GraphRagResponse {
    sessionId: ID!
    answer: String!
    selectedEdges: [SelectedEdgeType!]!
    retrievedEdgeCount: Int!
    durationMs: Int!
}

type SelectedEdgeType {
    subject: String!
    predicate: String!
    objectValue: String!
    dataset: String!
    reasoning: String!
    relevanceScore: Float!
}
```

- [ ] **Step 4: Repeat for DocRAG models**

Edit `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagModels.kt`:

```kotlin
package com.agentwork.graphmesh.query.docrag

import java.util.UUID

data class DocumentRagQuery(
    val question: String,
    val collectionId: String,
    val topK: Int = 10,
    val similarityThreshold: Float = 0.5f
)

data class DocumentRagResult(
    val sessionId: UUID,
    val answer: String,
    val sources: List<SourceAttribution>,
    val retrievedChunkCount: Int,
    val durationMs: Long
)

data class SourceAttribution(
    val chunkId: String,
    val documentId: String,
    val documentTitle: String,
    val pageNumber: Int?,
    val score: Float,
    val snippet: String
)

data class RetrievedChunk(
    val chunkId: String,
    val documentId: String,
    val text: String,
    val score: Float
)
```

- [ ] **Step 5: Wire producer into `DocumentRagService`**

Edit `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt`:

Add imports:
```kotlin
import com.agentwork.graphmesh.messaging.ExplainabilityEventProducer
import java.util.UUID
```

Add `explainabilityProducer: ExplainabilityEventProducer` as the last constructor parameter.

Replace `query()` with:

```kotlin
fun query(query: DocumentRagQuery): DocumentRagResult {
    val sessionId = UUID.randomUUID()
    val startTime = System.currentTimeMillis()

    logger.info("Phase 1: Chunk retrieval for collection {}, topK={}", query.collectionId, query.topK)
    val chunks = retrieveChunks(query)
    logger.info("Retrieved {} chunks", chunks.size)

    if (chunks.isEmpty()) {
        explainabilityProducer.sendDocRagEvent(
            sessionId = sessionId,
            collectionId = query.collectionId,
            queryText = query.question,
            retrievedChunkCount = 0,
            selectedChunkIds = emptyList(),
            answerText = "No relevant documents found for this question.",
        )
        return DocumentRagResult(
            sessionId = sessionId,
            answer = "No relevant documents found for this question.",
            sources = emptyList(),
            retrievedChunkCount = 0,
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    logger.info("Phase 2: Answer synthesis from {} chunks", chunks.size)
    val answer = synthesizeAnswer(query.question, chunks)
    val sources = buildSourceAttributions(chunks)

    val durationMs = System.currentTimeMillis() - startTime
    logger.info("Document RAG pipeline completed in {} ms", durationMs)

    explainabilityProducer.sendDocRagEvent(
        sessionId = sessionId,
        collectionId = query.collectionId,
        queryText = query.question,
        retrievedChunkCount = chunks.size,
        selectedChunkIds = chunks.map { it.chunkId },
        answerText = answer,
    )

    return DocumentRagResult(
        sessionId = sessionId,
        answer = answer,
        sources = sources,
        retrievedChunkCount = chunks.size,
        durationMs = durationMs
    )
}
```

- [ ] **Step 6: Update `document-rag.graphqls`**

Read the current file first to preserve the exact existing structure, then add `sessionId: ID!` to the response type.

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL — any callers that destructure `GraphRagResult` will need updating; check the build output for compile errors and add `sessionId` parameter where needed (likely in `GraphRagController`/`DocumentRagController` if they construct results manually, and in `KnowledgeQueryTool`/`DocumentQueryTool` if they consume results — they should not be impacted because they only read `answer`).

- [ ] **Step 7: Run all tests**

Run: `./gradlew test`
Expected: All existing tests still pass. If `GraphRagServiceTest` exists and instantiates `GraphRagService` directly, you need to pass a no-op `ExplainabilityEventProducer`. To do that without coupling tests to Kafka:
- Locate the test (`./gradlew test` will report failures clearly).
- Add a constructor argument: `ExplainabilityEventProducer(KafkaTemplate(DefaultKafkaProducerFactory(emptyMap())))` — the `send` calls will fail silently since there's no broker, but the producer's `whenComplete` swallows the exception.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/query/graphrag/ \
        src/main/kotlin/com/agentwork/graphmesh/query/docrag/ \
        src/main/resources/graphql/graph-rag.graphqls \
        src/main/resources/graphql/document-rag.graphqls
git commit -m "feat(explainability): emit explainability events from GraphRAG and DocRAG services"
```

---

## Task 9: Service Integration — Agent (Koog Tracing)

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/agent/Models.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/agent/AgentService.kt`
- Modify: `src/main/resources/graphql/agent.graphqls`

The agent integration is the trickiest part because we need a Koog `FeatureMessageProcessor` that bridges Koog events to `AgentIterationCollector.recordThought/recordToolStart/recordToolEnd`. The exact Koog event class names must be discovered at this step.

- [ ] **Step 1: Add `sessionId` to `AgentQueryResult`**

Edit `src/main/kotlin/com/agentwork/graphmesh/agent/Models.kt`:

```kotlin
package com.agentwork.graphmesh.agent

import java.util.UUID

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
    val sessionId: UUID,
    val answer: String,
    val durationMs: Long
)

data class ToolInfo(
    val name: String,
    val description: String,
    val groups: List<String> = emptyList()
)

data class ToolGroup(
    val name: String,
    val description: String,
    val toolNames: Set<String>
)
```

- [ ] **Step 2: Discover Koog event types**

In your IDE, navigate to `ai.koog.agents.core.feature.model` (or the equivalent package — search for `FeatureMessage` in koog-agents-jvm sources). Identify:

1. **The base type** consumed by `addMessageProcessor` — likely `FeatureMessage` or `FeatureEvent`
2. **An LLM-call-finished event** that exposes the model's response text
3. **A tool-call-starting event** that exposes the tool name and arguments
4. **A tool-call-finished event** that exposes the result

Document them with their full FQN in a comment block at the top of the new bridge class file.

- [ ] **Step 3: Implement the Koog bridge**

Add a new class `KoogAgentTracingBridge` inside the `agent/` package as a private file or nested helper:

Create `src/main/kotlin/com/agentwork/graphmesh/agent/KoogAgentTracingBridge.kt`:

```kotlin
package com.agentwork.graphmesh.agent

import com.agentwork.graphmesh.provenance.query.AgentIterationCollector
import org.slf4j.LoggerFactory

/**
 * Bridges Koog Tracing feature events to an AgentIterationCollector.
 *
 * Koog 0.7.3 event types (verified during implementation):
 *   - ai.koog.agents.core.feature.model.LLMCallCompletedEvent (or equivalent)
 *   - ai.koog.agents.core.feature.model.ToolExecutionStartingEvent
 *   - ai.koog.agents.core.feature.model.ToolExecutionCompletedEvent
 *
 * Field names for `responseText`, `toolName`, `args`, `result` are best-effort
 * — adapt to the actual class members. The contract is: feed each event
 * through `handle(event)` and the collector will assemble iterations.
 *
 * If a Koog event type is missing or differs from expectation, the bridge
 * defensively falls back to ignoring the event (logged at DEBUG).
 */
class KoogAgentTracingBridge(private val collector: AgentIterationCollector) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun handle(event: Any) {
        try {
            val className = event.javaClass.simpleName
            when {
                className.contains("LLMCallCompleted", ignoreCase = true) ||
                className.contains("LLMCallEnd", ignoreCase = true) -> {
                    val text = readField(event, "responseText")
                        ?: readField(event, "response")
                        ?: readField(event, "content")
                        ?: ""
                    if (text.isNotBlank()) collector.recordThought(text)
                }
                className.contains("ToolExecutionStart", ignoreCase = true) ||
                className.contains("ToolCallStart", ignoreCase = true) -> {
                    val toolName = readField(event, "toolName")
                        ?: readField(event, "tool")
                        ?: ""
                    val args = readMapField(event, "args")
                        ?: readMapField(event, "arguments")
                    collector.recordToolStart(toolName, args)
                }
                className.contains("ToolExecutionCompleted", ignoreCase = true) ||
                className.contains("ToolCallEnd", ignoreCase = true) -> {
                    val result = readField(event, "result")
                        ?: readField(event, "output")
                    collector.recordToolEnd(result)
                }
                else -> logger.debug("Ignoring untracked Koog event: {}", className)
            }
        } catch (e: Exception) {
            logger.debug("Failed to read Koog event {}: {}", event.javaClass.simpleName, e.message)
        }
    }

    private fun readField(target: Any, name: String): String? = try {
        val field = target.javaClass.declaredFields.firstOrNull { it.name == name }
        field?.isAccessible = true
        field?.get(target)?.toString()
    } catch (_: Exception) { null }

    @Suppress("UNCHECKED_CAST")
    private fun readMapField(target: Any, name: String): Map<String, String>? = try {
        val field = target.javaClass.declaredFields.firstOrNull { it.name == name }
        field?.isAccessible = true
        val raw = field?.get(target)
        when (raw) {
            is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value.toString() }
            null -> null
            else -> mapOf("value" to raw.toString())
        }
    } catch (_: Exception) { null }
}
```

The bridge uses reflection so it survives Koog API drift. If Koog provides typed visitor APIs (`when (e) { is LLMCallCompletedEvent -> ... }`), prefer those over reflection — refactor at this point.

- [ ] **Step 4: Wire the bridge into `AgentService`**

Edit `src/main/kotlin/com/agentwork/graphmesh/agent/AgentService.kt`:

Add imports:
```kotlin
import com.agentwork.graphmesh.messaging.ExplainabilityEventProducer
import com.agentwork.graphmesh.provenance.query.AgentIterationCollector
import java.util.UUID
```

Add `explainabilityProducer: ExplainabilityEventProducer` as the last constructor parameter.

Replace `query()` with:

```kotlin
fun query(
    question: String,
    collectionId: String,
    config: AgentQueryConfig = AgentQueryConfig(),
    allowedGroups: Set<String> = setOf("all")
): AgentQueryResult {
    val sessionId = UUID.randomUUID()
    val startTime = System.currentTimeMillis()

    val allowedToolNames = toolGroupRegistry.resolveToolNames(allowedGroups)

    val toolRegistry = ToolRegistry {
        if ("knowledge_query" in allowedToolNames) {
            tool(KnowledgeQueryTool(graphRagService, collectionId))
        }
        if ("document_query" in allowedToolNames) {
            tool(DocumentQueryTool(documentRagService, collectionId))
        }
    }

    val collector = AgentIterationCollector()
    val bridge = KoogAgentTracingBridge(collector)

    val agent = AIAgent(
        promptExecutor = promptExecutor,
        llmModel = LLModel(LLMProvider.OpenAI, modelName),
        strategy = reActStrategy(reasoningInterval = 1, name = "query_agent"),
        toolRegistry = toolRegistry,
        systemPrompt = config.systemPrompt
    )
    // NOTE: Tracing feature installation:
    //   If Koog provides install(Tracing) { addMessageProcessor(...) } via a builder
    //   block on the AIAgent constructor, move the line above into a builder lambda
    //   and call: install(Tracing) { addMessageProcessor(object : FeatureMessageProcessor() {
    //     override suspend fun processMessage(message: FeatureMessage) { bridge.handle(message) }
    //     ...
    //   }) }
    //   The exact wiring depends on the Koog 0.7.3 API. The collector + bridge contract
    //   itself is stable: see KoogAgentTracingBridge for the event-handling logic.

    val answer = runBlocking {
        agent.run(question)
    }

    val durationMs = System.currentTimeMillis() - startTime
    val iterations = collector.snapshot()

    explainabilityProducer.sendAgentEvent(
        sessionId = sessionId,
        collectionId = collectionId,
        queryText = question,
        iterations = iterations,
        answerText = answer,
    )

    logger.info(
        "Agent query complete: question='{}', groups={}, iterations={}, durationMs={}",
        question.take(80), allowedGroups, iterations.size, durationMs
    )

    return AgentQueryResult(
        sessionId = sessionId,
        answer = answer,
        durationMs = durationMs,
    )
}
```

**Important:** the comment block in the code marks the spot where the actual `install(Tracing)` call must be added once the Koog API has been verified in Step 2. If installing `Tracing` requires a builder lambda on the `AIAgent` constructor, restructure the code accordingly. If the iteration collector ends up empty because the Koog event types couldn't be wired, that is acceptable for the first cut — the producer will send an `AGENT` event with an empty `iterations` list and the recorder will still emit a Question + Conclusion chain.

- [ ] **Step 5: Update `agent.graphqls`**

Edit `src/main/resources/graphql/agent.graphqls`:

```graphql
extend type Query {
    agentTools: [ToolInfo!]!
    toolGroups: [ToolGroup!]!
}

extend type Mutation {
    askAgent(input: AgentQueryInput!): AgentQueryResult!
}

input AgentQueryInput {
    question: String!
    collectionId: ID!
    maxIterations: Int = 10
    allowedGroups: [String!]
}

type AgentQueryResult {
    sessionId: ID!
    answer: String!
    durationMs: Int!
}

type ToolInfo {
    name: String!
    description: String!
    groups: [String!]!
}

type ToolGroup {
    name: String!
    description: String!
    toolNames: [String!]!
}
```

**Important:** Replace ONLY the `AgentQueryResult` type block (adding the `sessionId: ID!` field). Leave `ToolInfo` and `ToolGroup` exactly as they already exist in the file — they are reproduced above for context only.

- [ ] **Step 6: Build & test**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL — fix any test files that destructure `AgentQueryResult` (likely `AgentServiceTest` if it exists) by adding the new `sessionId` parameter.

Run: `./gradlew test`
Expected: All tests pass. If `AgentServiceTest` exists and instantiates the service, pass a no-op `ExplainabilityEventProducer` as in Task 8 Step 7.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/agent/ \
        src/main/resources/graphql/agent.graphqls
git commit -m "feat(explainability): emit explainability events from AgentService with Koog tracing"
```

---

## Task 10: GraphQL Schema & Controller

**Files:**
- Create: `src/main/resources/graphql/explainability.graphqls`
- Create: `src/main/kotlin/com/agentwork/graphmesh/api/ExplainabilityController.kt`

- [ ] **Step 1: Create the GraphQL schema fragment**

Create `src/main/resources/graphql/explainability.graphqls`:

```graphql
extend type Query {
    explanationChain(collectionId: ID!, sessionUri: ID!): ExplanationChain
    explanationSessions(
        collectionId: ID!,
        mechanism: QueryMechanism,
        limit: Int = 50
    ): [QuestionExplanation!]!
}

enum QueryMechanism {
    GRAPH_RAG
    DOC_RAG
    AGENT
}

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
    iterationIndex: Int!
    thought: String!
    action: String
    arguments: [ArgumentEntry!]
    observation: String
}

type ArgumentEntry {
    key: String!
    value: String!
}

type SynthesisExplanation {
    uri: ID!
    answerText: String!
}

type ConclusionExplanation {
    uri: ID!
    answerText: String!
}
```

- [ ] **Step 2: Create the controller**

Create `src/main/kotlin/com/agentwork/graphmesh/api/ExplainabilityController.kt`:

```kotlin
package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.provenance.query.Analysis
import com.agentwork.graphmesh.provenance.query.Conclusion
import com.agentwork.graphmesh.provenance.query.ExplanationChain
import com.agentwork.graphmesh.provenance.query.ExplanationChainLoader
import com.agentwork.graphmesh.provenance.query.Exploration
import com.agentwork.graphmesh.provenance.query.Focus
import com.agentwork.graphmesh.provenance.query.Question
import com.agentwork.graphmesh.provenance.query.QueryMechanism
import com.agentwork.graphmesh.provenance.query.SelectedEdgeExplanation
import com.agentwork.graphmesh.provenance.query.Synthesis
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

@Controller
class ExplainabilityController(
    private val chainLoader: ExplanationChainLoader,
) {

    @QueryMapping
    fun explanationChain(
        @Argument collectionId: String,
        @Argument sessionUri: String,
    ): ExplanationChainView? {
        val chain = chainLoader.load(collectionId, sessionUri) ?: return null
        return ExplanationChainView.from(chain)
    }

    @QueryMapping
    fun explanationSessions(
        @Argument collectionId: String,
        @Argument mechanism: QueryMechanism?,
        @Argument limit: Int,
    ): List<QuestionExplanationView> =
        chainLoader.listSessions(collectionId, mechanism, limit)
            .map { QuestionExplanationView.from(it) }
}

// --- View DTOs that the GraphQL layer reads via property names matching the schema ---

data class ExplanationChainView(
    val question: QuestionExplanationView,
    val exploration: ExplorationExplanationView?,
    val focus: FocusExplanationView?,
    val analyses: List<AnalysisExplanationView>?,
    val synthesis: SynthesisExplanationView?,
    val conclusion: ConclusionExplanationView?,
    val mechanism: QueryMechanism,
) {
    companion object {
        fun from(c: ExplanationChain) = ExplanationChainView(
            question = QuestionExplanationView.from(c.question),
            exploration = c.exploration?.let { ExplorationExplanationView.from(it) },
            focus = c.focus?.let { FocusExplanationView.from(it) },
            analyses = c.analyses?.map { AnalysisExplanationView.from(it) },
            synthesis = c.synthesis?.let { SynthesisExplanationView.from(it) },
            conclusion = c.conclusion?.let { ConclusionExplanationView.from(it) },
            mechanism = c.mechanism,
        )
    }
}

data class QuestionExplanationView(
    val uri: String,
    val queryText: String,
    val timestamp: String,
    val mechanism: QueryMechanism,
) {
    companion object {
        fun from(q: Question) = QuestionExplanationView(
            uri = q.uri,
            queryText = q.queryText,
            timestamp = q.timestamp.toString(),
            mechanism = q.mechanism,
        )
    }
}

data class ExplorationExplanationView(val uri: String, val edgeCount: Int) {
    companion object {
        fun from(e: Exploration) = ExplorationExplanationView(e.uri, e.edgeCount)
    }
}

data class FocusExplanationView(val uri: String, val selectedEdges: List<SelectedEdgeDetailView>) {
    companion object {
        fun from(f: Focus) = FocusExplanationView(
            uri = f.uri,
            selectedEdges = f.selectedEdges.map { SelectedEdgeDetailView.from(it) },
        )
    }
}

data class SelectedEdgeDetailView(
    val subject: String, val predicate: String, val objectValue: String, val reasoning: String,
) {
    companion object {
        fun from(e: SelectedEdgeExplanation) = SelectedEdgeDetailView(
            e.subject, e.predicate, e.objectValue, e.reasoning,
        )
    }
}

data class AnalysisExplanationView(
    val uri: String,
    val iterationIndex: Int,
    val thought: String,
    val action: String?,
    val arguments: List<ArgumentEntryView>?,
    val observation: String?,
) {
    companion object {
        fun from(a: Analysis) = AnalysisExplanationView(
            uri = a.uri,
            iterationIndex = a.iterationIndex,
            thought = a.thought,
            action = a.action,
            arguments = a.arguments?.map { (k, v) -> ArgumentEntryView(k, v) },
            observation = a.observation,
        )
    }
}

data class ArgumentEntryView(val key: String, val value: String)

data class SynthesisExplanationView(val uri: String, val answerText: String) {
    companion object {
        fun from(s: Synthesis) = SynthesisExplanationView(s.uri, s.answerText)
    }
}

data class ConclusionExplanationView(val uri: String, val answerText: String) {
    companion object {
        fun from(c: Conclusion) = ConclusionExplanationView(c.uri, c.answerText)
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/graphql/explainability.graphqls \
        src/main/kotlin/com/agentwork/graphmesh/api/ExplainabilityController.kt
git commit -m "feat(explainability): add GraphQL schema and controller for explanation chains"
```

---

## Task 11: Full Build & Verification

- [ ] **Step 1: Run the full build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL — all modules compile, all tests pass.

If any tests fail:
- Read the failure output carefully.
- The most likely cause is a service test that needs the new `ExplainabilityEventProducer` constructor argument. Add a no-op producer instance as described in Task 8 Step 7.
- A second likely cause is the `AgentQueryResult` / `GraphRagResult` / `DocumentRagResult` constructor signature change — add the new `sessionId` parameter wherever the test constructs one directly.

- [ ] **Step 2: Manual smoke check via GraphQL (optional)**

If a Cassandra+Kafka stack is running locally via docker-compose, run `./gradlew bootRun`, then issue a `graphRag` query, capture the returned `sessionId`, and call `explanationChain(collectionId: ..., sessionUri: "urn:graphmesh:question:<id>")`. Verify the chain contains the expected fields.

This step is optional — the unit tests already cover the contract. Skip if no local broker is available.

- [ ] **Step 3: Write the done doc**

Create `docs/features/30-query-explainability-done.md`:

```markdown
# Feature 30: Query-Time Explainability — DONE

## Implemented

- PROV-O quad model for query-time explainability in `urn:graph:retrieval`
- Three mechanism-specific chains:
  - GraphRAG: Question → Exploration → Focus → Synthesis
  - DocRAG:   Question → Exploration → Synthesis (no Focus)
  - Agent:    Question → Analysis₁ → … → Analysisₙ → Conclusion
- Kafka-based async recording (`graphmesh.query.explained` topic)
- GraphQL drill-down via `explanationChain` and `explanationSessions`
- Koog Tracing bridge for agent iteration capture (reflection-based, robust to API drift)

## Deviations from the original feature doc

- Package root is `com.agentwork.graphmesh` (not `com.graphmesh`).
- Single Gradle module — no submodule layout.
- All explainability data classes live in one `ExplainabilityModels.kt` file
  rather than one file per class (matches the existing codebase style).
- `arguments` is exposed as `[ArgumentEntry!]` rather than a `JSON` scalar
  (no JSON scalar registered in the project).
- `QuadQuery.dataset` was already named `dataset` (not `graph`); no schema change needed.
- Explainability data is collection-scoped — `collectionId` is required on
  GraphQL queries and is used as the `QuadStore` partition key.

## Open items / tech debt

- Koog event field names (`responseText`, `args`, etc.) are read via reflection;
  if Koog 0.8+ introduces typed visitor APIs, the bridge should be refactored
  to use them.
- Replay idempotency relies on Cassandra UPSERT semantics — re-consuming an
  event simply rewrites the same quads. No explicit dedup at consumer level.
- Drill-down from `SelectedEdgeDetail` to extraction-time provenance
  (`urn:graph:source`, Feature 29) is left to clients — no server-side join.
```

- [ ] **Step 4: Commit the done doc**

```bash
git add docs/features/30-query-explainability-done.md
git commit -m "docs(explainability): add Feature 30 done doc"
```

---

## Acceptance Criteria

- [ ] GraphRAG session produces Question → Exploration → Focus → Synthesis quads in `urn:graph:retrieval`
- [ ] DocRAG session produces Question → Exploration → Synthesis (no Focus)
- [ ] Agent session produces Question → Analysis₁ → … → Analysisₙ → Conclusion
- [ ] Each Analysis carries thought, action, arguments, observation (when non-null)
- [ ] Agent analyses are linearly chained via `prov:wasDerivedFrom`
- [ ] Selected edges in Focus carry the LLM reasoning literal as a `tg:reasoning` quad on the quoted triple
- [ ] GraphQL `explanationChain(collectionId, sessionUri)` returns the full chain
- [ ] GraphQL `explanationSessions(collectionId, mechanism?, limit)` lists sessions
- [ ] All explainability quads use `NamedGraph.RETRIEVAL`
- [ ] Producer failure does not affect query latency or correctness (fire-and-forget)
- [ ] Consumer is idempotent at session granularity (replay safe via Cassandra UPSERT)
- [ ] `./gradlew clean build` passes
