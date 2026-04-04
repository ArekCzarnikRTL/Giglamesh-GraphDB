# Feature 18: NLP Query Service

## Problem

Benutzer muessen aktuell selbst entscheiden, ob sie eine Graph-RAG-Abfrage, eine Document-RAG-Suche oder eine
strukturierte Triple-Abfrage verwenden wollen. Diese Entscheidung erfordert technisches Wissen ueber die
Datenarchitektur und fuehrt oft zu suboptimalen Ergebnissen, wenn der falsche Abfragetyp gewaehlt wird. Ohne eine
einheitliche natuerlichsprachige Schnittstelle muessen Clients mehrere Query-Endpunkte kennen und die Routing-Logik
selbst implementieren.

## Ziel

Implementierung eines NLP-Query-Service, der natuerlichsprachige Fragen versteht, den optimalen Abfragetyp per
LLM-basierter Intent-Erkennung ermittelt, die Frage an den passenden Service weiterleitet und bei Bedarf reformuliert.

1. **Intent Detection** -- LLM-basierte Erkennung des Abfragetyps (graph_query, document_query, structured_query,
   hybrid)
2. **Query Routing** -- Automatische Weiterleitung an GraphRagService, DocumentRagService oder QuadStore
3. **Query Reformulation** -- Umformulierung vager oder mehrdeutiger Fragen in spezifischere Queries
4. **Unified Endpoint** -- Ein einziger GraphQL-Query-Endpunkt fuer alle Abfragearten
5. **Hybrid-Modus** -- Kombination mehrerer Abfragetypen bei komplexen Fragen

## Voraussetzungen

| Abhaengigkeit                                                    | Status     | Blocker? |
|------------------------------------------------------------------|------------|----------|
| Feature 05: LLM Provider Abstraction (ChatCompletionService)     | Geplant    | Ja       |
| Feature 14: GraphQL API (Schema-first, Controller-Infrastruktur) | Geplant    | Ja       |
| Feature 15: Graph RAG (GraphRagService)                          | Geplant    | Ja       |
| Feature 16: Document RAG (DocumentRagService)                    | Geplant    | Ja       |
| Spring Boot Starter GraphQL                                      | Verfuegbar | Nein     |

## Architektur

### Datenmodell

```kotlin
package com.graphmesh.query.nlp

import java.util.UUID

/**
 * Eingabe fuer den NLP-Query-Service.
 */
data class NlpQuery(
    /** Natuerlichsprachige Frage des Benutzers. */
    val question: String,
    /** Collection, in der gesucht werden soll. */
    val collectionId: UUID,
    /** Optional: erzwungener Intent (ueberspringt Intent Detection). */
    val forceIntent: QueryIntent? = null,
    /** Ob Streaming aktiviert werden soll. */
    val streaming: Boolean = false
)

/**
 * Erkannter Abfragetyp.
 */
enum class QueryIntent {
    /** Frage ueber Entitaeten und Beziehungen im Knowledge Graph. */
    GRAPH_QUERY,
    /** Frage, die am besten ueber Dokumentinhalte beantwortet wird. */
    DOCUMENT_QUERY,
    /** Strukturierte Abfrage nach spezifischen Triples/Quads. */
    STRUCTURED_QUERY,
    /** Komplexe Frage, die Graph- und Dokumentensuche kombiniert. */
    HYBRID
}

/**
 * Ergebnis der Intent-Erkennung mit Konfidenz.
 */
data class DetectedIntent(
    /** Der erkannte Intent. */
    val intent: QueryIntent,
    /** Konfidenz-Score (0.0 - 1.0). */
    val confidence: Double,
    /** Begruendung des LLM fuer die Klassifikation. */
    val reasoning: String,
    /** Optional: reformulierte Frage. */
    val reformulatedQuestion: String? = null
)

/**
 * Ergebnis des NLP-Query-Service.
 * Enthaelt die Antwort und Metadaten zum Routing.
 */
data class NlpQueryResult(
    /** Die generierte Antwort. */
    val answer: String,
    /** Der erkannte Intent. */
    val detectedIntent: DetectedIntent,
    /** Ob die Frage reformuliert wurde. */
    val wasReformulated: Boolean,
    /** Die verwendete (ggf. reformulierte) Frage. */
    val effectiveQuestion: String,
    /** Dauer der gesamten Verarbeitung in Millisekunden. */
    val durationMs: Long,
    /** Quellenangaben (je nach Intent unterschiedlich befuellt). */
    val sources: List<String>
)
```

### Service-Interfaces

```kotlin
package com.graphmesh.query.nlp

import kotlinx.coroutines.flow.Flow

/**
 * Hauptservice fuer natuerlichsprachige Abfragen.
 * Orchestriert Intent-Erkennung, Routing und Ergebnis-Aggregation.
 */
interface NlpQueryService {

    /**
     * Verarbeitet eine natuerlichsprachige Frage.
     * Erkennt den Intent, routet an den passenden Service
     * und gibt eine einheitliche Antwort zurueck.
     *
     * @param query Die Benutzeranfrage.
     * @return Einheitliches Ergebnis mit Antwort und Metadaten.
     */
    suspend fun query(query: NlpQuery): NlpQueryResult

    /**
     * Streaming-Variante der Abfrageverarbeitung.
     *
     * @param query Die Benutzeranfrage.
     * @return Flow von Antwort-Tokens.
     */
    fun queryStreaming(query: NlpQuery): Flow<String>
}

/**
 * LLM-basierte Erkennung des Abfragetyps.
 */
interface IntentDetector {

    /**
     * Erkennt den Intent einer natuerlichsprachigen Frage.
     * Das LLM klassifiziert die Frage in einen der definierten
     * Intent-Typen und gibt eine Begruendung zurueck.
     *
     * @param question Die natuerlichsprachige Frage.
     * @return Der erkannte Intent mit Konfidenz und Begruendung.
     */
    suspend fun detect(question: String): DetectedIntent
}

/**
 * Routing der Abfrage an den passenden Service.
 */
interface QueryRouter {

    /**
     * Routet eine Abfrage basierend auf dem erkannten Intent
     * an den passenden Service und gibt das Ergebnis zurueck.
     *
     * @param question Die (ggf. reformulierte) Frage.
     * @param intent Der erkannte Intent.
     * @param collectionId Die Ziel-Collection.
     * @return Antworttext und Quellenangaben.
     */
    suspend fun route(
        question: String,
        intent: QueryIntent,
        collectionId: java.util.UUID
    ): RoutingResult
}

/**
 * Ergebnis des Query-Routings.
 */
data class RoutingResult(
    val answer: String,
    val sources: List<String>
)

/**
 * Reformulierung vager oder mehrdeutiger Fragen.
 */
interface QueryReformulator {

    /**
     * Analysiert eine Frage und reformuliert sie bei Bedarf.
     * Mehrdeutige, unvollstaendige oder vage Fragen werden
     * in spezifischere Varianten umformuliert.
     *
     * @param question Die urspruengliche Frage.
     * @return Die reformulierte Frage oder null, wenn keine Reformulierung noetig.
     */
    suspend fun reformulate(question: String): String?
}
```

### DefaultNlpQueryService

```kotlin
package com.graphmesh.query.nlp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Standard-Implementierung des NLP-Query-Service.
 *
 * Ablauf:
 * 1. IntentDetector klassifiziert die Frage
 * 2. QueryReformulator verbessert die Frage bei Bedarf
 * 3. QueryRouter leitet an den passenden Service weiter
 * 4. Ergebnis wird mit Metadaten angereichert zurueckgegeben
 */
@Service
class DefaultNlpQueryService(
    private val intentDetector: IntentDetector,
    private val queryReformulator: QueryReformulator,
    private val queryRouter: QueryRouter
) : NlpQueryService {

    private val logger = LoggerFactory.getLogger(DefaultNlpQueryService::class.java)

    override suspend fun query(query: NlpQuery): NlpQueryResult {
        val startTime = System.currentTimeMillis()

        // Schritt 1: Intent erkennen (oder erzwungenen Intent verwenden)
        val detectedIntent = if (query.forceIntent != null) {
            DetectedIntent(
                intent = query.forceIntent,
                confidence = 1.0,
                reasoning = "Intent manuell erzwungen"
            )
        } else {
            logger.info("Intent-Erkennung fuer: '{}'", query.question)
            intentDetector.detect(query.question)
        }
        logger.info("Erkannter Intent: {} (Konfidenz: {})", detectedIntent.intent, detectedIntent.confidence)

        // Schritt 2: Frage reformulieren bei Bedarf
        val reformulated = queryReformulator.reformulate(query.question)
        val effectiveQuestion = reformulated ?: query.question
        val wasReformulated = reformulated != null

        if (wasReformulated) {
            logger.info("Frage reformuliert: '{}' -> '{}'", query.question, effectiveQuestion)
        }

        // Schritt 3: An passenden Service routen
        logger.info("Routing an {} fuer Collection {}", detectedIntent.intent, query.collectionId)
        val routingResult = queryRouter.route(
            question = effectiveQuestion,
            intent = detectedIntent.intent,
            collectionId = query.collectionId
        )

        val durationMs = System.currentTimeMillis() - startTime
        logger.info("NLP-Query abgeschlossen in {} ms", durationMs)

        return NlpQueryResult(
            answer = routingResult.answer,
            detectedIntent = detectedIntent,
            wasReformulated = wasReformulated,
            effectiveQuestion = effectiveQuestion,
            durationMs = durationMs,
            sources = routingResult.sources
        )
    }

    override fun queryStreaming(query: NlpQuery): Flow<String> = flow {
        // Intent-Erkennung und Reformulierung laufen blockierend
        val detectedIntent = if (query.forceIntent != null) {
            DetectedIntent(
                intent = query.forceIntent,
                confidence = 1.0,
                reasoning = "Intent manuell erzwungen"
            )
        } else {
            intentDetector.detect(query.question)
        }

        val reformulated = queryReformulator.reformulate(query.question)
        val effectiveQuestion = reformulated ?: query.question

        // Routing mit Streaming-Antwort
        val routingResult = queryRouter.route(
            question = effectiveQuestion,
            intent = detectedIntent.intent,
            collectionId = query.collectionId
        )

        emit(routingResult.answer)
    }
}
```

### IntentDetector-Implementierung

```kotlin
package com.graphmesh.query.nlp

import com.graphmesh.llm.ChatCompletionService
import org.springframework.stereotype.Component

/**
 * LLM-basierte Intent-Erkennung.
 * Verwendet einen speziellen System-Prompt, der das LLM anweist,
 * die Frage in einen der definierten Intent-Typen zu klassifizieren.
 */
@Component
class LlmIntentDetector(
    private val chatCompletionService: ChatCompletionService
) : IntentDetector {

    private val systemPrompt = """
        Du bist ein Query-Klassifikator. Analysiere die folgende Frage und bestimme den optimalen Abfragetyp.
        
        Antworte im Format:
        INTENT: <graph_query|document_query|structured_query|hybrid>
        CONFIDENCE: <0.0-1.0>
        REASONING: <Kurze Begruendung>
        
        Regeln:
        - graph_query: Fragen ueber Entitaeten, Beziehungen, Zusammenhaenge
        - document_query: Fragen ueber Dokumentinhalte, Zusammenfassungen, Zitate
        - structured_query: Spezifische Abfragen nach einzelnen Fakten oder Triples
        - hybrid: Komplexe Fragen, die mehrere Quellen benoetigen
    """.trimIndent()

    override suspend fun detect(question: String): DetectedIntent {
        val response = chatCompletionService.complete(
            systemPrompt = systemPrompt,
            userMessage = question
        )

        return parseIntentResponse(response)
    }

    private fun parseIntentResponse(response: String): DetectedIntent {
        val lines = response.lines()
        val intentLine = lines.find { it.startsWith("INTENT:") }
        val confidenceLine = lines.find { it.startsWith("CONFIDENCE:") }
        val reasoningLine = lines.find { it.startsWith("REASONING:") }

        val intent = when (intentLine?.substringAfter(":")?.trim()?.lowercase()) {
            "graph_query" -> QueryIntent.GRAPH_QUERY
            "document_query" -> QueryIntent.DOCUMENT_QUERY
            "structured_query" -> QueryIntent.STRUCTURED_QUERY
            "hybrid" -> QueryIntent.HYBRID
            else -> QueryIntent.GRAPH_QUERY // Fallback
        }

        val confidence = confidenceLine?.substringAfter(":")?.trim()?.toDoubleOrNull() ?: 0.5
        val reasoning = reasoningLine?.substringAfter(":")?.trim() ?: "Keine Begruendung"

        return DetectedIntent(
            intent = intent,
            confidence = confidence,
            reasoning = reasoning
        )
    }
}
```

### QueryRouter-Implementierung

```kotlin
package com.graphmesh.query.nlp

import com.graphmesh.query.graphrag.GraphRagQuery
import com.graphmesh.query.graphrag.GraphRagService
import com.graphmesh.query.docrag.DocumentRagQuery
import com.graphmesh.query.docrag.DocumentRagService
import com.graphmesh.storage.cassandra.QuadStore
import com.graphmesh.storage.cassandra.QuadQuery
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Leitet Abfragen basierend auf dem erkannten Intent
 * an den passenden Service weiter.
 */
@Component
class DefaultQueryRouter(
    private val graphRagService: GraphRagService,
    private val documentRagService: DocumentRagService,
    private val quadStore: QuadStore
) : QueryRouter {

    override suspend fun route(
        question: String,
        intent: QueryIntent,
        collectionId: UUID
    ): RoutingResult {
        return when (intent) {
            QueryIntent.GRAPH_QUERY -> {
                val result = graphRagService.query(
                    GraphRagQuery(question = question, collectionId = collectionId)
                )
                RoutingResult(
                    answer = result.answer,
                    sources = result.selectedEdges.map { edge ->
                        "${edge.quad.subject} -> ${edge.quad.predicate} -> ${edge.quad.`object`}"
                    }
                )
            }

            QueryIntent.DOCUMENT_QUERY -> {
                val result = documentRagService.query(
                    DocumentRagQuery(question = question, collectionId = collectionId)
                )
                RoutingResult(
                    answer = result.answer,
                    sources = result.sources.map { src ->
                        "${src.documentTitle} (Seite ${src.pageNumber ?: "?"})"
                    }
                )
            }

            QueryIntent.STRUCTURED_QUERY -> {
                val quads = quadStore.query(
                    QuadQuery(collection = collectionId.toString(), limit = 20)
                )
                val text = quads.joinToString("\n") { q ->
                    "${q.subject} -- ${q.predicate} --> ${q.`object`}"
                }
                RoutingResult(
                    answer = text.ifEmpty { "Keine passenden Triples gefunden." },
                    sources = quads.map { "${it.graph}" }.distinct()
                )
            }

            QueryIntent.HYBRID -> {
                // Hybrid: Graph RAG und Document RAG parallel ausfuehren
                val graphResult = graphRagService.query(
                    GraphRagQuery(question = question, collectionId = collectionId)
                )
                val docResult = documentRagService.query(
                    DocumentRagQuery(question = question, collectionId = collectionId)
                )

                val combinedAnswer = """
                    Basierend auf dem Knowledge Graph:
                    ${graphResult.answer}
                    
                    Basierend auf den Dokumenten:
                    ${docResult.answer}
                """.trimIndent()

                val combinedSources = graphResult.selectedEdges.map { edge ->
                    "[Graph] ${edge.quad.subject} -> ${edge.quad.predicate} -> ${edge.quad.`object`}"
                } + docResult.sources.map { src ->
                    "[Dokument] ${src.documentTitle} (Seite ${src.pageNumber ?: "?"})"
                }

                RoutingResult(answer = combinedAnswer, sources = combinedSources)
            }
        }
    }
}
```

### GraphQL-Integration

```graphql
# api/src/main/resources/graphql/nlp-query.graphqls

extend type Query {
    nlpQuery(input: NlpQueryInput!): NlpQueryResponse!
}

input NlpQueryInput {
    question: String!
    collectionId: ID!
    forceIntent: QueryIntentEnum
}

type NlpQueryResponse {
    answer: String!
    detectedIntent: DetectedIntentType!
    wasReformulated: Boolean!
    effectiveQuestion: String!
    durationMs: Long!
    sources: [String!]!
}

type DetectedIntentType {
    intent: QueryIntentEnum!
    confidence: Float!
    reasoning: String!
}

enum QueryIntentEnum {
    GRAPH_QUERY
    DOCUMENT_QUERY
    STRUCTURED_QUERY
    HYBRID
}
```

### NlpQueryController

```kotlin
package com.graphmesh.api.graphql

import com.graphmesh.query.nlp.NlpQuery
import com.graphmesh.query.nlp.NlpQueryResult
import com.graphmesh.query.nlp.NlpQueryService
import com.graphmesh.query.nlp.QueryIntent
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import java.util.UUID

@Controller
class NlpQueryController(
    private val nlpQueryService: NlpQueryService
) {

    @QueryMapping
    suspend fun nlpQuery(@Argument input: NlpQueryInput): NlpQueryResult {
        val query = NlpQuery(
            question = input.question,
            collectionId = UUID.fromString(input.collectionId),
            forceIntent = input.forceIntent?.let { QueryIntent.valueOf(it) }
        )
        return nlpQueryService.query(query)
    }
}

data class NlpQueryInput(
    val question: String,
    val collectionId: String,
    val forceIntent: String?
)
```

## Betroffene Dateien

### Backend

| Datei                                                                     | Aenderung                                                    |
|---------------------------------------------------------------------------|--------------------------------------------------------------|
| `query/src/main/kotlin/com/graphmesh/query/nlp/NlpQuery.kt`               | NEU - Query-Datenmodell                                      |
| `query/src/main/kotlin/com/graphmesh/query/nlp/QueryIntent.kt`            | NEU - Intent-Enum                                            |
| `query/src/main/kotlin/com/graphmesh/query/nlp/DetectedIntent.kt`         | NEU - Intent-Ergebnis-Datenmodell                            |
| `query/src/main/kotlin/com/graphmesh/query/nlp/NlpQueryResult.kt`         | NEU - Ergebnis-Datenmodell                                   |
| `query/src/main/kotlin/com/graphmesh/query/nlp/NlpQueryService.kt`        | NEU - Service-Interface                                      |
| `query/src/main/kotlin/com/graphmesh/query/nlp/IntentDetector.kt`         | NEU - Intent-Detection-Interface                             |
| `query/src/main/kotlin/com/graphmesh/query/nlp/QueryRouter.kt`            | NEU - Routing-Interface                                      |
| `query/src/main/kotlin/com/graphmesh/query/nlp/QueryReformulator.kt`      | NEU - Reformulierungs-Interface                              |
| `query/src/main/kotlin/com/graphmesh/query/nlp/DefaultNlpQueryService.kt` | NEU - Service-Orchestrierung                                 |
| `query/src/main/kotlin/com/graphmesh/query/nlp/LlmIntentDetector.kt`      | NEU - LLM-basierte Intent-Erkennung                          |
| `query/src/main/kotlin/com/graphmesh/query/nlp/DefaultQueryRouter.kt`     | NEU - Query-Routing-Implementierung                          |
| `query/src/main/kotlin/com/graphmesh/query/nlp/LlmQueryReformulator.kt`   | NEU - LLM-basierte Reformulierung                            |
| `api/src/main/resources/graphql/nlp-query.graphqls`                       | NEU - GraphQL-Schema-Erweiterung                             |
| `api/src/main/kotlin/com/graphmesh/api/graphql/NlpQueryController.kt`     | NEU - GraphQL-Controller                                     |
| `query/build.gradle.kts`                                                  | AENDERUNG - Abhaengigkeiten auf graphrag, docrag, llm Module |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                         | Aenderung                                         |
|-------------------------------------------------------------------------------|---------------------------------------------------|
| `query/src/test/kotlin/com/graphmesh/query/nlp/DefaultNlpQueryServiceTest.kt` | NEU - Unit-Tests fuer Service-Orchestrierung      |
| `query/src/test/kotlin/com/graphmesh/query/nlp/LlmIntentDetectorTest.kt`      | NEU - Tests fuer Intent-Erkennung mit Mock-LLM    |
| `query/src/test/kotlin/com/graphmesh/query/nlp/DefaultQueryRouterTest.kt`     | NEU - Tests fuer Routing an verschiedene Services |
| `query/src/test/kotlin/com/graphmesh/query/nlp/LlmQueryReformulatorTest.kt`   | NEU - Tests fuer Frage-Reformulierung             |
| `api/src/test/kotlin/com/graphmesh/api/graphql/NlpQueryControllerTest.kt`     | NEU - GraphQL-Integration-Tests                   |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                                                   |
|-------------------|-------------|-------------------------------------------------------------------------|
| Spring Boot (JVM) | Ja          | Spring Boot GraphQL + Coroutines Integration                            |
| KMP Library       | Nein        | Abhaengigkeit von Spring-spezifischen Annotations und Service-Injection |
| Ktor/Wasm         | Nein        | Spring Boot GraphQL ist JVM-spezifisch                                  |

## Akzeptanzkriterien

- [ ] GraphQL-Query `nlpQuery` nimmt eine natuerlichsprachige Frage und Collection-ID entgegen
- [ ] Intent-Erkennung klassifiziert Fragen korrekt in graph_query, document_query, structured_query oder hybrid
- [ ] Jeder erkannte Intent enthaelt einen Konfidenz-Score und eine Begruendung
- [ ] Query-Routing leitet graph_query an GraphRagService und document_query an DocumentRagService weiter
- [ ] Structured-Query-Intent fuehrt direkte Triple-Abfragen am QuadStore aus
- [ ] Hybrid-Intent kombiniert Ergebnisse aus Graph RAG und Document RAG
- [ ] Vage oder mehrdeutige Fragen werden durch den QueryReformulator verbessert
- [ ] Antwort enthaelt Metadaten: erkannter Intent, Konfidenz, ob reformuliert, effektive Frage
- [ ] `forceIntent`-Parameter ueberspringt die Intent-Erkennung und verwendet den vorgegebenen Intent
- [ ] Quellenangaben werden abhaengig vom Intent-Typ korrekt befuellt
- [ ] Antwortzeit fuer Intent-Erkennung liegt unter 2 Sekunden
- [ ] Bei Konfidenz-Score unter 0.5 wird ein Fallback auf GRAPH_QUERY durchgefuehrt
