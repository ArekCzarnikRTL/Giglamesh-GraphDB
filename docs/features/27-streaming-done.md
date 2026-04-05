# Feature 27: Streaming — Done

## Zusammenfassung

Echtes Token-Streaming für Agent-Queries über GraphQL Subscriptions implementiert. Baut einen manuellen ReAct-Loop mit Koog's `PromptExecutor.executeStreaming()` der `Flow<StreamFrame>` liefert. Jede Phase (THOUGHT, ACTION, OBSERVATION, ANSWER) wird als `StreamToken` über eine GraphQL Subscription gestreamt.

### Implementierte Komponenten

| Datei | Zweck |
|-------|-------|
| `streaming/StreamToken.kt` | StreamToken + StreamTokenType Model |
| `streaming/StreamingAgentService.kt` | Interface für Streaming-Agent |
| `streaming/StreamingAgentServiceImpl.kt` | @Service — Manueller ReAct-Loop mit executeStreaming(), emittiert Flow<StreamToken> |
| `resources/graphql/streaming.graphqls` | GraphQL Subscription Schema (agentStream) |
| `api/StreamingController.kt` | @Controller — @SubscriptionMapping für agentStream |
| `build.gradle.kts` | WebSocket-Dependency hinzugefügt |

### Tests

| Test | Anzahl |
|------|--------|
| StreamingAgentServiceTest | 7 Tests (Tool-Parsing, StreamToken Model, StreamTokenType) |
| StreamingControllerTest | 2 Tests (Delegation, Parameter-Durchreichung) |

### GraphQL Subscription API

```graphql
subscription {
    agentStream(input: {
        question: "Was ist Photosynthese?"
        collectionId: "col-1"
        maxIterations: 10
        allowedGroups: ["all"]
    }) {
        content
        type        # THOUGHT, ACTION, OBSERVATION, ANSWER, ERROR
        endOfMessage
        endOfStream
    }
}
```

## Abweichungen vom Feature-Dokument

1. **Nur Agent-Streaming** — kein graphRagStream, docRagStream (YAGNI)
2. **Manueller ReAct-Loop** statt AIAgent — AIAgent.run() ist Black Box ohne Streaming
3. **PromptExecutor.executeStreaming()** direkt — kein custom StreamingLlmProvider Interface
4. **Kein StreamingGraphRagService/StreamingDocumentRagService** — RAG-Queries zu kurz für Streaming
5. **Kein Backward-Compatibility-Test** — bestehende APIs unverändert
6. **Package `com.agentwork.graphmesh.streaming`**

## Offene Punkte

- WebSocket-Konfiguration (CORS, Timeouts) muss ggf. für Produktion angepasst werden
- Agent-Streaming-Qualität hängt vom LLM-Modell ab — Modelle mit guter Tool-Call-Unterstützung empfohlen
- Integration mit Frontend (WebSocket GraphQL Client) ist separates Feature
