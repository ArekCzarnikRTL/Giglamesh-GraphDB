# Feature 27: Streaming (LLM, RAG, Agent)

## Problem

Aktuell warten Benutzer auf die vollstaendige Generierung einer LLM-Antwort, bevor sie Ergebnisse sehen. Bei langen
RAG-Antworten oder mehrstufigen Agent-Sessions fuehrt dies zu inakzeptablen Wartezeiten und schlechter User Experience.
Besonders bei Agent-Iterationen (Feature 25) fehlt jede Rueckmeldung darueber, was der Agent gerade tut -- der Benutzer
sieht erst nach Abschluss aller Iterationen ein Ergebnis.

## Ziel

Implementierung von Token-by-Token-Streaming durch den gesamten Stack, von LLM-Providern ueber RAG-Pipelines bis zum
Agenten, ausgeliefert ueber GraphQL Subscriptions.

1. **LLM-Streaming** -- Token-Streaming via SSE vom LLM-Provider, abgebildet auf Kotlin Flow
2. **RAG-Streaming** -- Streaming der Synthese-Phase in GraphRAG und DocRAG
3. **Agent-Streaming** -- Streaming der Observe-Phase mit Zwischenergebnissen pro Iteration
4. **GraphQL Subscriptions** -- Echtzeit-Auslieferung ueber GraphQL Subscription-Endpunkte
5. **Backward Compatibility** -- Nicht-streaming Varianten bleiben vollstaendig funktionsfaehig
6. **Unified Token Model** -- Einheitliches `StreamToken`-Format ueber alle Streaming-Quellen

## Voraussetzungen

| Abhaengigkeit                                                             | Status     | Blocker? |
|---------------------------------------------------------------------------|------------|----------|
| Feature 05: LLM Provider Abstraction (LlmProvider, ChatCompletionService) | Geplant    | Ja       |
| Feature 15: Graph RAG (GraphRagService, AnswerSynthesizer)                | Geplant    | Ja       |
| Feature 16: Document RAG (DocumentRagService, DocumentSynthesizer)        | Geplant    | Ja       |
| Feature 25: Agent System (AgentService, AgentContext)                     | Geplant    | Ja       |
| Feature 14: GraphQL API (Subscriptions-Infrastruktur)                     | Geplant    | Ja       |
| Spring Boot Starter WebSocket                                             | Verfuegbar | Nein     |
| Kotlin Coroutines / Flow                                                  | Verfuegbar | Nein     |

## Architektur

### StreamToken

```kotlin
package com.graphmesh.streaming

/**
 * Einheitliches Token-Format fuer alle Streaming-Quellen.
 */
data class StreamToken(
    /** Der Text-Inhalt dieses Tokens. */
    val content: String,
    /** Typ des Tokens (identifiziert die Quelle/Phase). */
    val type: StreamTokenType,
    /** Ob dies das letzte Token der aktuellen Nachricht ist. */
    val endOfMessage: Boolean = false,
    /** Ob dies das letzte Token des gesamten Dialogs ist. */
    val endOfStream: Boolean = false,
    /** Optionale Metadaten (z.B. Modellname, Token-Counts). */
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Typ des Stream-Tokens, identifiziert die Phase.
 */
enum class StreamTokenType {
    /** Normaler LLM-Antwort-Token. */
    TEXT,
    /** Agent-Gedanke (Think-Phase). */
    THOUGHT,
    /** Agent-Aktion (Act-Phase). */
    ACTION,
    /** Agent-Beobachtung (Observe-Phase). */
    OBSERVATION,
    /** Finale Antwort. */
    ANSWER,
    /** Fehler. */
    ERROR
}
```

### Streaming LLM Provider

```kotlin
package com.graphmesh.streaming

import com.graphmesh.llm.LlmProvider
import kotlinx.coroutines.flow.Flow

/**
 * Erweitert den LlmProvider um Streaming-Faehigkeit.
 * Provider, die kein Streaming unterstuetzen, fallen auf
 * die blockierende Variante zurueck.
 */
interface StreamingLlmProvider : LlmProvider {

    /**
     * Ob dieser Provider Streaming unterstuetzt.
     */
    fun supportsStreaming(): Boolean = true

    /**
     * Generiert eine Antwort als Stream von Tokens.
     *
     * @param systemPrompt System-Prompt.
     * @param userPrompt Benutzer-Prompt.
     * @param temperature LLM-Temperatur.
     * @return Flow von StreamTokens.
     */
    fun completeStreaming(
        systemPrompt: String,
        userPrompt: String,
        temperature: Double = 0.7
    ): Flow<StreamToken>
}
```

### Streaming GraphRAG

```kotlin
package com.graphmesh.streaming

import com.graphmesh.query.graphrag.GraphRagQuery
import kotlinx.coroutines.flow.Flow

/**
 * GraphRAG-Service mit Streaming-Unterstuetzung.
 * Retrieval und Edge Selection laufen blockierend,
 * nur die Synthese-Phase streamt Tokens.
 */
interface StreamingGraphRagService {

    /**
     * Fuehrt die Graph-RAG-Pipeline mit Streaming-Synthese aus.
     *
     * @param query Die Benutzeranfrage.
     * @return Flow von StreamTokens (Synthese-Tokens + finales Ergebnis).
     */
    fun queryStreaming(query: GraphRagQuery): Flow<StreamToken>
}
```

### Streaming DocRAG

```kotlin
package com.graphmesh.streaming

import com.graphmesh.query.docrag.DocumentRagQuery
import kotlinx.coroutines.flow.Flow

/**
 * Document-RAG-Service mit Streaming-Unterstuetzung.
 * Chunk-Retrieval laeuft blockierend, Synthese wird gestreamt.
 */
interface StreamingDocumentRagService {

    /**
     * Fuehrt die Document-RAG-Pipeline mit Streaming-Synthese aus.
     *
     * @param query Die Benutzeranfrage.
     * @return Flow von StreamTokens.
     */
    fun queryStreaming(query: DocumentRagQuery): Flow<StreamToken>
}
```

### Streaming Agent

```kotlin
package com.graphmesh.streaming

import com.graphmesh.agent.AgentConfig
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Agent-Service mit Streaming-Unterstuetzung.
 * Streamt Gedanken, Aktionen und Beobachtungen pro Iteration.
 */
interface StreamingAgentService {

    /**
     * Fuehrt eine Agent-Session mit Streaming aus.
     *
     * Streaming-Reihenfolge pro Iteration:
     * 1. THOUGHT-Tokens (Agent denkt)
     * 2. ACTION-Token (Agent waehlt Tool)
     * 3. OBSERVATION-Tokens (Tool-Ergebnis)
     * 4. Wiederholung oder ANSWER-Tokens (finale Antwort)
     *
     * @param question Die Benutzerfrage.
     * @param collectionId Die Ziel-Collection.
     * @param config Optionale Konfiguration.
     * @return Flow von StreamTokens mit Typ-Annotation.
     */
    fun runStreaming(
        question: String,
        collectionId: UUID,
        config: AgentConfig = AgentConfig()
    ): Flow<StreamToken>
}
```

### Flow-basierte Integration

```kotlin
package com.graphmesh.streaming

import kotlinx.coroutines.flow.*

/**
 * Beispiel: Agent-Streaming-Implementierung.
 * Zeigt, wie der ReAct-Loop als Flow exponiert wird.
 */
class DefaultStreamingAgentService(
    private val streamingLlm: StreamingLlmProvider,
    private val tools: List<AgentTool>
) : StreamingAgentService {

    override fun runStreaming(
        question: String,
        collectionId: UUID,
        config: AgentConfig
    ): Flow<StreamToken> = flow {
        val context = AgentContext(question = question, collectionId = collectionId, config = config)

        for (iteration in 1..config.maxIterations) {
            // Think-Phase: LLM-Reasoning streamen
            val thoughtBuilder = StringBuilder()
            streamingLlm.completeStreaming(
                systemPrompt = config.systemPrompt,
                userPrompt = buildThinkPrompt(context)
            ).collect { token ->
                thoughtBuilder.append(token.content)
                emit(token.copy(type = StreamTokenType.THOUGHT))
            }

            val parsed = parseThought(thoughtBuilder.toString())
            if (parsed.isFinalAnswer) {
                emit(StreamToken(
                    content = parsed.answer,
                    type = StreamTokenType.ANSWER,
                    endOfMessage = true,
                    endOfStream = true
                ))
                return@flow
            }

            // Act-Phase: Tool ausfuehren
            emit(StreamToken(
                content = parsed.action.toolName,
                type = StreamTokenType.ACTION,
                endOfMessage = true
            ))

            // Observe-Phase: Tool-Ergebnis
            val observation = executeTool(parsed.action, context)
            emit(StreamToken(
                content = observation,
                type = StreamTokenType.OBSERVATION,
                endOfMessage = true
            ))
        }
    }
}
```

### GraphQL Subscription

```graphql
type Subscription {
    """Token-Streaming fuer Agent-Sessions."""
    agentStream(
        question: String!
        collectionId: ID!
        maxIterations: Int
    ): StreamToken!

    """Token-Streaming fuer GraphRAG-Queries."""
    graphRagStream(
        question: String!
        collectionId: ID!
    ): StreamToken!

    """Token-Streaming fuer DocRAG-Queries."""
    docRagStream(
        question: String!
        collectionId: ID!
    ): StreamToken!
}

type StreamToken {
    content: String!
    type: StreamTokenType!
    endOfMessage: Boolean!
    endOfStream: Boolean!
    metadata: JSON
}

enum StreamTokenType {
    TEXT
    THOUGHT
    ACTION
    OBSERVATION
    ANSWER
    ERROR
}
```

## Betroffene Dateien

### Backend

| Datei                                                                                    | Aenderung                          |
|------------------------------------------------------------------------------------------|------------------------------------|
| `streaming/src/main/kotlin/com/graphmesh/streaming/StreamToken.kt`                       | Einheitliches Token-Format         |
| `streaming/src/main/kotlin/com/graphmesh/streaming/StreamTokenType.kt`                   | Token-Typ-Enum                     |
| `streaming/src/main/kotlin/com/graphmesh/streaming/StreamingLlmProvider.kt`              | Streaming-faehiger LLM-Provider    |
| `streaming/src/main/kotlin/com/graphmesh/streaming/StreamingGraphRagService.kt`          | Streaming GraphRAG Interface       |
| `streaming/src/main/kotlin/com/graphmesh/streaming/StreamingDocumentRagService.kt`       | Streaming DocRAG Interface         |
| `streaming/src/main/kotlin/com/graphmesh/streaming/StreamingAgentService.kt`             | Streaming Agent Interface          |
| `llm/src/main/kotlin/com/graphmesh/llm/OpenAiStreamingProvider.kt`                       | OpenAI Streaming-Implementierung   |
| `query/src/main/kotlin/com/graphmesh/query/graphrag/DefaultStreamingGraphRagService.kt`  | GraphRAG Streaming-Implementierung |
| `query/src/main/kotlin/com/graphmesh/query/docrag/DefaultStreamingDocumentRagService.kt` | DocRAG Streaming-Implementierung   |
| `agent/src/main/kotlin/com/graphmesh/agent/DefaultStreamingAgentService.kt`              | Agent Streaming-Implementierung    |
| `graphql/src/main/resources/graphql/streaming.graphqls`                                  | GraphQL Subscription Schema        |
| `graphql/src/main/kotlin/com/graphmesh/graphql/StreamingController.kt`                   | Subscription Controller            |

### Frontend

| Datei                                        | Aenderung                   |
|----------------------------------------------|-----------------------------|
| UI nutzt GraphQL Subscriptions via WebSocket | Konsument der Streaming-API |

### Tests

| Datei                                                                            | Aenderung                                               |
|----------------------------------------------------------------------------------|---------------------------------------------------------|
| `streaming/src/test/kotlin/com/graphmesh/streaming/StreamingLlmProviderTest.kt`  | Unit-Tests fuer LLM-Streaming                           |
| `streaming/src/test/kotlin/com/graphmesh/streaming/StreamingAgentServiceTest.kt` | Tests fuer Agent-Streaming                              |
| `graphql/src/test/kotlin/com/graphmesh/graphql/StreamingControllerTest.kt`       | Integration-Tests fuer Subscriptions                    |
| `streaming/src/test/kotlin/com/graphmesh/streaming/BackwardCompatibilityTest.kt` | Tests dass nicht-streaming APIs weiterhin funktionieren |

## Platform-Einschraenkungen

| Plattform         | Unterstuetzt | Anmerkung                                 |
|-------------------|--------------|-------------------------------------------|
| Spring Boot (JVM) | Ja           | Primaere Zielplattform, WebSocket + SSE   |
| KMP Library       | Nein         | Server-seitige Subscription-Infrastruktur |
| Ktor/Wasm         | Nein         | Kein Server-Side Streaming im Browser     |

## Akzeptanzkriterien

- [ ] LLM-Streaming liefert Token fuer Token ueber `Flow<StreamToken>` aus
- [ ] Provider ohne Streaming-Support fallen automatisch auf blockierende Variante zurueck
- [ ] GraphRAG streamt die Synthese-Phase, Retrieval und Selection bleiben blockierend
- [ ] DocRAG streamt die Synthese-Phase, Chunk-Retrieval bleibt blockierend
- [ ] Agent-Streaming liefert THOUGHT-, ACTION- und OBSERVATION-Tokens pro Iteration
- [ ] `endOfMessage=true` markiert korrekt das Ende jeder Phase
- [ ] `endOfStream=true` markiert das Ende des gesamten Dialogs
- [ ] GraphQL Subscriptions liefern StreamTokens in Echtzeit via WebSocket
- [ ] Nicht-streaming APIs (`query()`, `run()`) funktionieren unveraendert
- [ ] Fehler waehrend des Streamings werden als ERROR-Token zugestellt
