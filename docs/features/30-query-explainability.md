# Feature 30: Query-Time Explainability

## Problem

Benutzer erhalten Antworten von GraphRAG, DocRAG und dem Agenten, koennen aber nicht nachvollziehen, wie diese Antworten
zustande gekommen sind. Es fehlt die Transparenz darueber, welche Kanten aus dem Knowledge Graph selektiert wurden,
welche Chunks herangezogen wurden und welche Reasoning-Schritte der Agent durchlaufen hat. Ohne
Query-Time-Explainability ist eine Verifikation der Antwortqualitaet und eine Rueckverfolgung zu Quellen nicht moeglich.

## Ziel

Implementierung eines Explainability-Systems, das den Ableitungspfad jeder Antwort aufzeichnet -- von der Frage ueber
Exploration und Selektion bis zur Synthese -- und diese Daten ueber GraphQL abfragbar macht.

1. **Entity Chain** -- Aufzeichnung der Kette: Question -> Exploration -> Focus -> Synthesis -> Conclusion
2. **Agent-Iteration-Tracking** -- Think/Act/Observe pro Iteration als linearer Abhaengigkeitsgraph
3. **Named Graph Storage** -- Speicherung in `urn:graph:retrieval` (getrennt von Extraction-Provenance)
4. **PROV-O Ontologie** -- W3C-konforme Provenance-Typen mit GraphMesh-spezifischen Subtypen
5. **GraphQL Drill-Down** -- Abfragbar von Antwort -> Evidenz -> Quellen
6. **Streaming Integration** -- Explainability-Events werden waehrend der Query-Ausfuehrung gestreamt

## Voraussetzungen

| Abhaengigkeit                                                    | Status     | Blocker? |
|------------------------------------------------------------------|------------|----------|
| Feature 07: RDF Graph Model (Quad, Triple, RdfTerm, NamedGraph)  | Geplant    | Ja       |
| Feature 15: Graph RAG (GraphRagService, EdgeSelector)            | Geplant    | Ja       |
| Feature 16: Document RAG (DocumentRagService, ChunkRetriever)    | Geplant    | Ja       |
| Feature 25: Agent System (AgentService, AgentIteration)          | Geplant    | Ja       |
| Feature 14: GraphQL API (Schema-first, Controller-Infrastruktur) | Geplant    | Ja       |
| W3C PROV-O Ontologie                                             | Verfuegbar | Nein     |

## Architektur

### Entity-Typen nach Abfragemechanismus

```kotlin
package com.graphmesh.provenance.query

/**
 * Gemeinsame Basis-Typen fuer alle Query-Time-Explainability-Entitaeten.
 * Alle nutzen PROV-O als Grundlage mit GraphMesh-spezifischen Subtypen.
 */

// --- GraphRAG Entity Chain ---

/**
 * Frage-Entity: Startpunkt jeder Explainability-Kette.
 * PROV-O: prov:Activity
 * TG-Typen: tg:Question, tg:GraphRagQuestion
 */
data class Question(
    val uri: String,
    val queryText: String,
    val timestamp: java.time.Instant,
    /** Typ-Diskriminator: GRAPH_RAG, DOC_RAG, AGENT */
    val mechanism: QueryMechanism
)

enum class QueryMechanism {
    GRAPH_RAG, DOC_RAG, AGENT
}

/**
 * Exploration: Alle abgerufenen Kanten/Chunks.
 * PROV-O: prov:Entity, tg:Exploration
 * Beziehung: prov:wasGeneratedBy -> Question
 */
data class Exploration(
    val uri: String,
    val edgeCount: Int,
    val questionUri: String
)

/**
 * Focus: Vom LLM selektierte Kanten mit Begruendung.
 * PROV-O: prov:Entity, tg:Focus
 * Beziehung: prov:wasDerivedFrom -> Exploration
 * Nur bei GraphRAG (DocRAG hat keinen Focus-Schritt).
 */
data class Focus(
    val uri: String,
    val selectedEdges: List<SelectedEdgeExplanation>,
    val explorationUri: String
)

data class SelectedEdgeExplanation(
    /** Das Quad (Subject, Predicate, Object) als Quoted Triple. */
    val edgeUri: String,
    /** LLM-Begruendung fuer die Auswahl. */
    val reasoning: String
)

/**
 * Synthesis: Die synthetisierte Antwort.
 * PROV-O: prov:Entity, tg:Synthesis
 * Beziehung: prov:wasDerivedFrom -> Focus (GraphRAG) oder Exploration (DocRAG)
 */
data class Synthesis(
    val uri: String,
    val documentUri: String,
    val derivedFromUri: String
)
```

### Agent-spezifische Explainability

```kotlin
package com.graphmesh.provenance.query

/**
 * Analysis: Eine einzelne Think/Act/Observe-Iteration des Agenten.
 * PROV-O: prov:Entity, tg:Analysis
 * Beziehung: prov:wasDerivedFrom -> vorherige Analysis oder Question
 *
 * Linearer Abhaengigkeitsgraph:
 *   Question -> Analysis1 -> Analysis2 -> ... -> Conclusion
 */
data class Analysis(
    val uri: String,
    val thought: String,
    val action: String?,
    val arguments: Map<String, String>?,
    val observation: String?,
    val parentUri: String
)

/**
 * Conclusion: Die finale Antwort des Agenten.
 * PROV-O: prov:Entity, tg:Conclusion
 * Beziehung: prov:wasDerivedFrom -> letzte Analysis
 */
data class Conclusion(
    val uri: String,
    val answer: String,
    val parentUri: String
)
```

### URI-Struktur

```kotlin
package com.graphmesh.provenance.query

import java.util.UUID

/**
 * URI-Generatoren fuer Explainability-Entitaeten.
 */
object ExplainabilityUris {
    private const val BASE = "urn:graphmesh"

    fun graphRagQuestion(uuid: UUID) = "$BASE:question:$uuid"
    fun docRagQuestion(uuid: UUID) = "$BASE:docrag:$uuid"
    fun agentQuestion(uuid: UUID) = "$BASE:agent:$uuid"

    fun exploration(questionUuid: UUID) = "$BASE:prov:retrieval:$questionUuid"
    fun focus(questionUuid: UUID) = "$BASE:prov:selection:$questionUuid"
    fun synthesis(questionUuid: UUID) = "$BASE:prov:answer:$questionUuid"

    fun agentIteration(sessionUuid: UUID, iterationNumber: Int) =
        "$BASE:agent:$sessionUuid/i$iterationNumber"
    fun agentConclusion(sessionUuid: UUID) =
        "$BASE:agent:$sessionUuid/final"
}
```

### ExplainabilityService

```kotlin
package com.graphmesh.provenance.query

import com.graphmesh.rdf.Quad
import java.util.UUID

/**
 * Service fuer die Aufzeichnung und Abfrage von Query-Time-Explainability.
 */
interface ExplainabilityService {

    /**
     * Zeichnet eine GraphRAG-Session auf.
     * Erzeugt Quads fuer Question -> Exploration -> Focus -> Synthesis
     * und schreibt sie in urn:graph:retrieval.
     */
    suspend fun recordGraphRagSession(
        sessionId: UUID,
        query: String,
        retrievedEdgeCount: Int,
        selectedEdges: List<SelectedEdgeExplanation>,
        answerDocumentUri: String
    )

    /**
     * Zeichnet eine DocRAG-Session auf.
     * Erzeugt Quads fuer Question -> Exploration -> Synthesis
     * (kein Focus-Schritt bei DocRAG).
     */
    suspend fun recordDocRagSession(
        sessionId: UUID,
        query: String,
        retrievedChunkCount: Int,
        selectedChunkIds: List<String>,
        answerDocumentUri: String
    )

    /**
     * Zeichnet eine Agent-Session auf.
     * Erzeugt Quads fuer Question -> Analysis1 -> ... -> Conclusion.
     */
    suspend fun recordAgentSession(
        sessionId: UUID,
        query: String,
        iterations: List<AgentIterationRecord>,
        finalAnswer: String
    )

    /**
     * Laedt eine Explainability-Kette anhand der Session-URI.
     * Ermoeglicht Drill-Down von Antwort zurueck zu Quellen.
     */
    suspend fun loadExplanationChain(sessionUri: String): ExplanationChain
}

data class AgentIterationRecord(
    val thought: String,
    val action: String?,
    val arguments: Map<String, String>?,
    val observation: String?
)
```

### ExplanationChain

```kotlin
package com.graphmesh.provenance.query

/**
 * Vollstaendige Erklaerungskette fuer eine Query-Session.
 * Ermoeglicht den Drill-Down von der Antwort zu den Quellen.
 */
data class ExplanationChain(
    /** Die Ausgangsfrage. */
    val question: Question,
    /** Abgerufene Kanten/Chunks. */
    val exploration: Exploration?,
    /** Selektierte Kanten mit Begruendung (nur GraphRAG). */
    val focus: Focus?,
    /** Agent-Iterationen (nur Agent). */
    val analyses: List<Analysis>?,
    /** Synthese-Ergebnis (GraphRAG/DocRAG). */
    val synthesis: Synthesis?,
    /** Finale Agent-Antwort (nur Agent). */
    val conclusion: Conclusion?,
    /** Typ der Query-Session. */
    val mechanism: QueryMechanism
)
```

### ExplainabilityRecorder (Pipeline-Integration)

```kotlin
package com.graphmesh.provenance.query

import com.graphmesh.rdf.Quad

/**
 * Recorder, der in RAG- und Agent-Services eingebunden wird.
 * Erzeugt PROV-O Quads und schreibt sie in urn:graph:retrieval.
 */
interface ExplainabilityRecorder {

    /**
     * Erzeugt PROV-O Quads fuer eine Question-Entity.
     */
    fun questionQuads(question: Question): List<Quad>

    /**
     * Erzeugt PROV-O Quads fuer eine Exploration-Entity.
     */
    fun explorationQuads(exploration: Exploration): List<Quad>

    /**
     * Erzeugt PROV-O Quads fuer eine Focus-Entity mit selektierten Kanten.
     */
    fun focusQuads(focus: Focus): List<Quad>

    /**
     * Erzeugt PROV-O Quads fuer eine Analysis-Entity (Agent-Iteration).
     */
    fun analysisQuads(analysis: Analysis): List<Quad>

    /**
     * Erzeugt PROV-O Quads fuer eine Conclusion-Entity.
     */
    fun conclusionQuads(conclusion: Conclusion): List<Quad>

    /**
     * Schreibt Quads in den Named Graph urn:graph:retrieval.
     */
    suspend fun persist(quads: List<Quad>)
}
```

### GraphQL Schema

```graphql
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
    object: String!
    reasoning: String!
}

type AnalysisExplanation {
    uri: ID!
    thought: String!
    action: String
    arguments: JSON
    observation: String
}

type ConclusionExplanation {
    uri: ID!
    answer: String!
}

enum QueryMechanism {
    GRAPH_RAG
    DOC_RAG
    AGENT
}

type Query {
    """Laedt die Erklaerungskette fuer eine Session."""
    explanationChain(sessionUri: ID!): ExplanationChain

    """Listet alle Sessions mit Fragen."""
    explanationSessions(
        mechanism: QueryMechanism
        limit: Int = 50
    ): [QuestionExplanation!]!
}
```

## Betroffene Dateien

### Backend

| Datei                                                                                       | Aenderung                                  |
|---------------------------------------------------------------------------------------------|--------------------------------------------|
| `provenance/src/main/kotlin/com/graphmesh/provenance/query/Question.kt`                     | Frage-Datenklasse                          |
| `provenance/src/main/kotlin/com/graphmesh/provenance/query/Exploration.kt`                  | Exploration-Datenklasse                    |
| `provenance/src/main/kotlin/com/graphmesh/provenance/query/Focus.kt`                        | Focus-Datenklasse mit Kanten-Begruendungen |
| `provenance/src/main/kotlin/com/graphmesh/provenance/query/Synthesis.kt`                    | Synthese-Datenklasse                       |
| `provenance/src/main/kotlin/com/graphmesh/provenance/query/Analysis.kt`                     | Agent-Iteration-Datenklasse                |
| `provenance/src/main/kotlin/com/graphmesh/provenance/query/Conclusion.kt`                   | Agent-Conclusion-Datenklasse               |
| `provenance/src/main/kotlin/com/graphmesh/provenance/query/ExplainabilityService.kt`        | Service-Interface                          |
| `provenance/src/main/kotlin/com/graphmesh/provenance/query/DefaultExplainabilityService.kt` | Implementierung                            |
| `provenance/src/main/kotlin/com/graphmesh/provenance/query/ExplainabilityRecorder.kt`       | Quad-Erzeugung                             |
| `provenance/src/main/kotlin/com/graphmesh/provenance/query/ExplanationChain.kt`             | Drill-Down Datenklasse                     |
| `provenance/src/main/kotlin/com/graphmesh/provenance/query/ExplainabilityUris.kt`           | URI-Generatoren                            |
| `query/src/main/kotlin/com/graphmesh/query/graphrag/DefaultGraphRagService.kt`              | Integration ExplainabilityRecorder         |
| `query/src/main/kotlin/com/graphmesh/query/docrag/DefaultDocumentRagService.kt`             | Integration ExplainabilityRecorder         |
| `agent/src/main/kotlin/com/graphmesh/agent/DefaultAgentService.kt`                          | Integration ExplainabilityRecorder         |
| `graphql/src/main/resources/graphql/explainability.graphqls`                                | GraphQL Schema                             |
| `graphql/src/main/kotlin/com/graphmesh/graphql/ExplainabilityController.kt`                 | GraphQL Controller                         |

### Frontend

| Datei                                      | Aenderung                         |
|--------------------------------------------|-----------------------------------|
| UI-Komponenten fuer Explainability-Anzeige | Drill-Down von Antwort zu Quellen |

### Tests

| Datei                                                                                     | Aenderung                    |
|-------------------------------------------------------------------------------------------|------------------------------|
| `provenance/src/test/kotlin/com/graphmesh/provenance/query/ExplainabilityServiceTest.kt`  | Tests fuer Session-Recording |
| `provenance/src/test/kotlin/com/graphmesh/provenance/query/ExplainabilityRecorderTest.kt` | Tests fuer Quad-Erzeugung    |
| `provenance/src/test/kotlin/com/graphmesh/provenance/query/ExplanationChainTest.kt`       | Tests fuer Drill-Down        |
| `graphql/src/test/kotlin/com/graphmesh/graphql/ExplainabilityControllerTest.kt`           | GraphQL Integration-Tests    |

## Platform-Einschraenkungen

| Plattform         | Unterstuetzt | Anmerkung                              |
|-------------------|--------------|----------------------------------------|
| Spring Boot (JVM) | Ja           | Primaere Zielplattform                 |
| KMP Library       | Nein         | Abhaengig von QuadStore und GraphQL    |
| Ktor/Wasm         | Nein         | Server-seitige Provenance-Aufzeichnung |

## Akzeptanzkriterien

- [ ] GraphRAG-Session erzeugt die Kette Question -> Exploration -> Focus -> Synthesis in `urn:graph:retrieval`
- [ ] DocRAG-Session erzeugt die Kette Question -> Exploration -> Synthesis (ohne Focus)
- [ ] Agent-Session erzeugt die Kette Question -> Analysis1 -> ... -> AnalysisN -> Conclusion
- [ ] Jede Analysis-Entity enthaelt thought, action, arguments und observation
- [ ] Agent-Analyses sind linear verkettet via `prov:wasDerivedFrom`
- [ ] Selektierte Kanten in Focus enthalten Quoted Triples und LLM-Begruendung
- [ ] GraphQL-Query `explanationChain` gibt die vollstaendige Kette fuer eine Session zurueck
- [ ] GraphQL-Query `explanationSessions` listet Sessions filterbar nach Mechanismus
- [ ] Alle Explainability-Quads verwenden den Named Graph `urn:graph:retrieval`
- [ ] Drill-Down von Antwort ueber selektierte Kanten bis zu Extraction-Provenance (Feature 29) ist moeglich
