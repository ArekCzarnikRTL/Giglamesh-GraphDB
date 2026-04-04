# Feature 15: Graph RAG

## Problem

Benutzer stellen Fragen an den Knowledge Graph, erhalten aber keine natuerlichsprachigen Antworten mit Quellennachweis.
Ohne eine strukturierte Retrieval-Augmented-Generation-Pipeline, die relevante Subgraphen abruft, Kanten priorisiert und
eine LLM-basierte Synthese durchfuehrt, muessen Benutzer manuell durch Triple-Ergebnisse navigieren. Ausserdem fehlt die
Nachvollziehbarkeit, welche Kanten zur Antwort beigetragen haben und warum sie ausgewaehlt wurden.

## Ziel

Implementierung einer dreistufigen Graph-RAG-Pipeline, die relevante Subgraphen abruft, Kanten per LLM selektiert und
eine quellenbasierte Antwort synthetisiert. Die Pipeline wird ueber GraphQL exponiert und zeichnet zur Query-Zeit auf,
welche Kanten verwendet wurden.

1. **Subgraph Retrieval** -- Abruf relevanter Subgraphen ueber Embedding-Similarity und Keyword-Suche
2. **Edge Selection** -- LLM-basierte Auswahl relevanter Kanten mit Begruendung
3. **Answer Synthesis** -- LLM-generierte Antwort ausschliesslich auf Basis der selektierten Kanten
4. **GraphQL-Integration** -- Exponierung als GraphQL-Query mit Streaming-Unterstuetzung
5. **Provenance Recording** -- Aufzeichnung, welche Kanten verwendet wurden und warum

## Voraussetzungen

| Abhaengigkeit                                                             | Status     | Blocker? |
|---------------------------------------------------------------------------|------------|----------|
| Feature 05: LLM Provider Abstraction (LlmProvider, ChatCompletionService) | Geplant    | Ja       |
| Feature 07: RDF Graph Model (Quad, Triple, RdfTerm)                       | Geplant    | Ja       |
| Feature 12: Relationship Extractor (liefert extrahierte Kanten)           | Geplant    | Ja       |
| Feature 13: Document Embeddings (EmbeddingService, VectorStore)           | Geplant    | Ja       |
| Feature 14: GraphQL API (Schema-first, Controller-Infrastruktur)          | Geplant    | Ja       |
| Spring Boot Starter GraphQL                                               | Verfuegbar | Nein     |

## Architektur

### Datenmodell

```kotlin
package com.graphmesh.query.graphrag

import com.graphmesh.rdf.Quad
import com.graphmesh.rdf.RdfTerm
import java.util.UUID

/**
 * Eingabe-Query fuer die Graph-RAG-Pipeline.
 */
data class GraphRagQuery(
    /** Natuerlichsprachige Frage des Benutzers. */
    val question: String,
    /** Collection, in der gesucht werden soll. */
    val collectionId: UUID,
    /** Maximale Anzahl der Kanten im Subgraphen. */
    val maxEdges: Int = 150,
    /** Maximale Traversierungstiefe im Graphen. */
    val maxDepth: Int = 2,
    /** Maximale Anzahl der Kanten fuer die LLM-Selektion. */
    val maxSelectedEdges: Int = 30,
    /** Ob Streaming aktiviert werden soll. */
    val streaming: Boolean = false
)

/**
 * Ergebnis der Graph-RAG-Pipeline mit Provenance-Informationen.
 */
data class GraphRagResult(
    /** Die generierte natuerlichsprachige Antwort. */
    val answer: String,
    /** Liste der selektierten Kanten mit Begruendung. */
    val selectedEdges: List<SelectedEdge>,
    /** Gesamtzahl der abgerufenen Kanten im Subgraphen. */
    val retrievedEdgeCount: Int,
    /** Dauer der Pipeline-Ausfuehrung in Millisekunden. */
    val durationMs: Long
)

/**
 * Eine vom LLM selektierte Kante mit Begruendung.
 */
data class SelectedEdge(
    /** Das urspruengliche Quad aus dem Knowledge Graph. */
    val quad: Quad,
    /** Begruendung des LLM, warum diese Kante relevant ist. */
    val reasoning: String,
    /** Relevanz-Score (0.0 - 1.0), vom LLM vergeben. */
    val relevanceScore: Double
)
```

### Service-Interfaces

```kotlin
package com.graphmesh.query.graphrag

import kotlinx.coroutines.flow.Flow

/**
 * Hauptservice fuer die Graph-RAG-Pipeline.
 * Orchestriert die drei Phasen: Retrieval, Selection, Synthesis.
 */
interface GraphRagService {

    /**
     * Fuehrt die vollstaendige Graph-RAG-Pipeline aus.
     *
     * @param query Die Benutzeranfrage mit Konfigurationsparametern.
     * @return Das Ergebnis mit Antwort und Provenance-Daten.
     */
    suspend fun query(query: GraphRagQuery): GraphRagResult

    /**
     * Fuehrt die Pipeline mit Streaming-Antwort aus.
     * Retrieval und Selection laufen blockierend, die Synthese wird gestreamt.
     *
     * @param query Die Benutzeranfrage (streaming wird ignoriert).
     * @return Flow von Antwort-Tokens.
     */
    fun queryStreaming(query: GraphRagQuery): Flow<String>
}

/**
 * Phase 1: Abruf eines relevanten Subgraphen.
 * Kombiniert Embedding-Similarity mit Keyword-basierter Suche.
 */
interface SubgraphRetriever {

    /**
     * Ruft einen relevanten Subgraphen ab, indem zunaechst aehnliche
     * Entitaeten via Vektor-Suche gefunden und anschliessend deren
     * Kanten traversiert werden.
     *
     * @param question Die natuerlichsprachige Frage.
     * @param collectionId Die Ziel-Collection.
     * @param maxEdges Maximale Kantenanzahl im Subgraphen.
     * @param maxDepth Maximale Traversierungstiefe.
     * @return Liste der abgerufenen Quads.
     */
    suspend fun retrieve(
        question: String,
        collectionId: java.util.UUID,
        maxEdges: Int,
        maxDepth: Int
    ): List<com.graphmesh.rdf.Quad>
}

/**
 * Phase 2: LLM-basierte Auswahl relevanter Kanten.
 * Das LLM bewertet jede Kante hinsichtlich ihrer Relevanz fuer die Frage.
 */
interface EdgeSelector {

    /**
     * Selektiert die relevantesten Kanten aus dem Subgraphen.
     * Das LLM erhaelt die Frage und den Subgraphen und gibt eine
     * priorisierte Liste mit Begruendungen zurueck.
     *
     * @param question Die natuerlichsprachige Frage.
     * @param edges Alle Kanten des abgerufenen Subgraphen.
     * @param maxSelected Maximale Anzahl zu selektierender Kanten.
     * @return Liste der selektierten Kanten mit Begruendung.
     */
    suspend fun select(
        question: String,
        edges: List<com.graphmesh.rdf.Quad>,
        maxSelected: Int
    ): List<SelectedEdge>
}

/**
 * Phase 3: LLM-basierte Antwortsynthese.
 * Generiert eine natuerlichsprachige Antwort ausschliesslich
 * auf Basis der selektierten Kanten.
 */
interface AnswerSynthesizer {

    /**
     * Synthetisiert eine Antwort aus den selektierten Kanten.
     *
     * @param question Die natuerlichsprachige Frage.
     * @param selectedEdges Die vom EdgeSelector gewaehlten Kanten.
     * @return Die generierte Antwort.
     */
    suspend fun synthesize(
        question: String,
        selectedEdges: List<SelectedEdge>
    ): String

    /**
     * Streaming-Variante der Synthese.
     *
     * @param question Die natuerlichsprachige Frage.
     * @param selectedEdges Die vom EdgeSelector gewaehlten Kanten.
     * @return Flow von Antwort-Tokens.
     */
    fun synthesizeStreaming(
        question: String,
        selectedEdges: List<SelectedEdge>
    ): Flow<String>
}
```

### DefaultGraphRagService

```kotlin
package com.graphmesh.query.graphrag

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Standard-Implementierung der dreistufigen Graph-RAG-Pipeline.
 *
 * Ablauf:
 * 1. SubgraphRetriever holt relevante Kanten via Embedding-Similarity + Keyword-Suche
 * 2. EdgeSelector laesst das LLM relevante Kanten auswaehlen und begruenden
 * 3. AnswerSynthesizer generiert die finale Antwort aus den selektierten Kanten
 */
@Service
class DefaultGraphRagService(
    private val subgraphRetriever: SubgraphRetriever,
    private val edgeSelector: EdgeSelector,
    private val answerSynthesizer: AnswerSynthesizer
) : GraphRagService {

    private val logger = LoggerFactory.getLogger(DefaultGraphRagService::class.java)

    override suspend fun query(query: GraphRagQuery): GraphRagResult {
        val startTime = System.currentTimeMillis()

        // Phase 1: Subgraph Retrieval
        logger.info("Phase 1: Subgraph-Retrieval fuer Collection {}", query.collectionId)
        val subgraph = subgraphRetriever.retrieve(
            question = query.question,
            collectionId = query.collectionId,
            maxEdges = query.maxEdges,
            maxDepth = query.maxDepth
        )
        logger.info("Subgraph mit {} Kanten abgerufen", subgraph.size)

        // Phase 2: Edge Selection
        logger.info("Phase 2: Edge-Selektion aus {} Kanten", subgraph.size)
        val selectedEdges = edgeSelector.select(
            question = query.question,
            edges = subgraph,
            maxSelected = query.maxSelectedEdges
        )
        logger.info("{} Kanten selektiert", selectedEdges.size)

        // Phase 3: Answer Synthesis
        logger.info("Phase 3: Antwortsynthese")
        val answer = answerSynthesizer.synthesize(
            question = query.question,
            selectedEdges = selectedEdges
        )

        val durationMs = System.currentTimeMillis() - startTime
        logger.info("Graph-RAG-Pipeline abgeschlossen in {} ms", durationMs)

        return GraphRagResult(
            answer = answer,
            selectedEdges = selectedEdges,
            retrievedEdgeCount = subgraph.size,
            durationMs = durationMs
        )
    }

    override fun queryStreaming(query: GraphRagQuery): Flow<String> = flow {
        // Phase 1 + 2 laufen blockierend
        val subgraph = subgraphRetriever.retrieve(
            question = query.question,
            collectionId = query.collectionId,
            maxEdges = query.maxEdges,
            maxDepth = query.maxDepth
        )

        val selectedEdges = edgeSelector.select(
            question = query.question,
            edges = subgraph,
            maxSelected = query.maxSelectedEdges
        )

        // Phase 3 wird gestreamt
        answerSynthesizer.synthesizeStreaming(
            question = query.question,
            selectedEdges = selectedEdges
        ).collect { token -> emit(token) }
    }
}
```

### SubgraphRetriever-Implementierung

```kotlin
package com.graphmesh.query.graphrag

import com.graphmesh.llm.EmbeddingService
import com.graphmesh.rdf.Quad
import com.graphmesh.storage.cassandra.QuadStore
import com.graphmesh.storage.cassandra.QuadQuery
import com.graphmesh.storage.qdrant.VectorStore
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Kombiniert Vektor-Similarity-Suche mit BFS-Traversierung
 * zur Subgraph-Extraktion.
 *
 * Optimierungen gemaess graphrag-performance-optimization.md:
 * - Iterative BFS statt rekursiver Traversierung
 * - Batched Entity-Verarbeitung pro Tiefenstufe
 * - Zykluserkennung via Visited-Set
 * - Early Termination bei Erreichen von maxEdges
 */
@Component
class DefaultSubgraphRetriever(
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore,
    private val quadStore: QuadStore
) : SubgraphRetriever {

    override suspend fun retrieve(
        question: String,
        collectionId: UUID,
        maxEdges: Int,
        maxDepth: Int
    ): List<Quad> {
        // Schritt 1: Frage vektorisieren und aehnliche Entitaeten finden
        val queryVector = embeddingService.embed(question)
        val seedEntities = vectorStore.search(
            collection = "entity-embeddings-$collectionId",
            vector = queryVector,
            limit = 50
        ).map { it.payload["entity_uri"] ?: "" }
            .filter { it.isNotBlank() }

        // Schritt 2: BFS-Traversierung ab Seed-Entitaeten
        val visited = mutableSetOf<String>()
        var currentLevel = seedEntities.toSet()
        val subgraph = mutableListOf<Quad>()

        for (depth in 0 until maxDepth) {
            if (currentLevel.isEmpty() || subgraph.size >= maxEdges) break

            val nextLevel = mutableSetOf<String>()

            for (entity in currentLevel) {
                if (entity in visited) continue
                visited.add(entity)

                val quads = quadStore.query(
                    QuadQuery(
                        collection = collectionId.toString(),
                        subject = entity,
                        limit = maxEdges - subgraph.size
                    )
                )

                for (quad in quads) {
                    if (subgraph.size >= maxEdges) break
                    subgraph.add(quad)
                    nextLevel.add(quad.`object`.value)
                }
            }

            currentLevel = nextLevel - visited
        }

        return subgraph
    }
}
```

### GraphQL-Integration

```graphql
# api/src/main/resources/graphql/graph-rag.graphqls

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
    answer: String!
    selectedEdges: [SelectedEdgeType!]!
    retrievedEdgeCount: Int!
    durationMs: Long!
}

type SelectedEdgeType {
    subject: String!
    predicate: String!
    object: String!
    graph: String!
    reasoning: String!
    relevanceScore: Float!
}

scalar Long
```

### GraphRagController

```kotlin
package com.graphmesh.api.graphql

import com.graphmesh.query.graphrag.GraphRagQuery
import com.graphmesh.query.graphrag.GraphRagResult
import com.graphmesh.query.graphrag.GraphRagService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import java.util.UUID

@Controller
class GraphRagController(
    private val graphRagService: GraphRagService
) {

    @QueryMapping
    suspend fun graphRag(@Argument input: GraphRagInput): GraphRagResult {
        val query = GraphRagQuery(
            question = input.question,
            collectionId = UUID.fromString(input.collectionId),
            maxEdges = input.maxEdges ?: 150,
            maxDepth = input.maxDepth ?: 2,
            maxSelectedEdges = input.maxSelectedEdges ?: 30
        )
        return graphRagService.query(query)
    }
}

data class GraphRagInput(
    val question: String,
    val collectionId: String,
    val maxEdges: Int?,
    val maxDepth: Int?,
    val maxSelectedEdges: Int?
)
```

## Betroffene Dateien

### Backend

| Datei                                                                            | Aenderung                                                |
|----------------------------------------------------------------------------------|----------------------------------------------------------|
| `query/src/main/kotlin/com/graphmesh/query/graphrag/GraphRagQuery.kt`            | NEU - Query-Datenmodell                                  |
| `query/src/main/kotlin/com/graphmesh/query/graphrag/GraphRagResult.kt`           | NEU - Ergebnis-Datenmodell mit Provenance                |
| `query/src/main/kotlin/com/graphmesh/query/graphrag/SelectedEdge.kt`             | NEU - Selektierte Kante mit Begruendung                  |
| `query/src/main/kotlin/com/graphmesh/query/graphrag/GraphRagService.kt`          | NEU - Service-Interface                                  |
| `query/src/main/kotlin/com/graphmesh/query/graphrag/SubgraphRetriever.kt`        | NEU - Retrieval-Interface                                |
| `query/src/main/kotlin/com/graphmesh/query/graphrag/EdgeSelector.kt`             | NEU - Selection-Interface                                |
| `query/src/main/kotlin/com/graphmesh/query/graphrag/AnswerSynthesizer.kt`        | NEU - Synthese-Interface                                 |
| `query/src/main/kotlin/com/graphmesh/query/graphrag/DefaultGraphRagService.kt`   | NEU - Pipeline-Orchestrierung                            |
| `query/src/main/kotlin/com/graphmesh/query/graphrag/DefaultSubgraphRetriever.kt` | NEU - BFS-basiertes Retrieval                            |
| `query/src/main/kotlin/com/graphmesh/query/graphrag/LlmEdgeSelector.kt`          | NEU - LLM-basierte Kantenselektion                       |
| `query/src/main/kotlin/com/graphmesh/query/graphrag/LlmAnswerSynthesizer.kt`     | NEU - LLM-basierte Synthese                              |
| `api/src/main/resources/graphql/graph-rag.graphqls`                              | NEU - GraphQL-Schema-Erweiterung                         |
| `api/src/main/kotlin/com/graphmesh/api/graphql/GraphRagController.kt`            | NEU - GraphQL-Controller                                 |
| `query/build.gradle.kts`                                                         | AENDERUNG - Abhaengigkeiten auf rdf, storage, llm Module |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                | Aenderung                                              |
|--------------------------------------------------------------------------------------|--------------------------------------------------------|
| `query/src/test/kotlin/com/graphmesh/query/graphrag/DefaultGraphRagServiceTest.kt`   | NEU - Unit-Tests fuer Pipeline-Orchestrierung          |
| `query/src/test/kotlin/com/graphmesh/query/graphrag/DefaultSubgraphRetrieverTest.kt` | NEU - Tests fuer BFS-Traversierung und Zykluserkennung |
| `query/src/test/kotlin/com/graphmesh/query/graphrag/LlmEdgeSelectorTest.kt`          | NEU - Tests fuer Kantenselektion mit Mock-LLM          |
| `query/src/test/kotlin/com/graphmesh/query/graphrag/LlmAnswerSynthesizerTest.kt`     | NEU - Tests fuer Antwortsynthese                       |
| `api/src/test/kotlin/com/graphmesh/api/graphql/GraphRagControllerTest.kt`            | NEU - GraphQL-Integration-Tests                        |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                                               |
|-------------------|-------------|---------------------------------------------------------------------|
| Spring Boot (JVM) | Ja          | Spring Boot GraphQL + Coroutines Integration                        |
| KMP Library       | Nein        | Abhaengigkeit von Spring-spezifischen Annotations und Qdrant-Client |
| Ktor/Wasm         | Nein        | Spring Boot GraphQL ist JVM-spezifisch                              |

## Akzeptanzkriterien

- [ ] GraphQL-Query `graphRag` nimmt eine natuerlichsprachige Frage und Collection-ID entgegen
- [ ] Phase 1 (Retrieval) findet relevante Entitaeten via Embedding-Similarity und traversiert deren Kanten per BFS
- [ ] Phase 2 (Selection) laesst das LLM relevante Kanten auswaehlen und gibt pro Kante eine Begruendung zurueck
- [ ] Phase 3 (Synthesis) generiert eine Antwort ausschliesslich auf Basis der selektierten Kanten
- [ ] Jede selektierte Kante enthaelt Subject, Predicate, Object, Reasoning und Relevanz-Score
- [ ] Die Pipeline haelt konfigurierbare Limits ein (maxEdges, maxDepth, maxSelectedEdges)
- [ ] BFS-Traversierung erkennt Zyklen und terminiert fruehzeitig bei Limit-Erreichung
- [ ] Streaming-Modus liefert Antwort-Tokens als Flow aus der Synthese-Phase
- [ ] Antwortzeit fuer typische Anfragen liegt unter 10 Sekunden (mit optimiertem Batching)
- [ ] Pipeline gibt `durationMs` und `retrievedEdgeCount` als Metriken zurueck
