# Feature 25: Agent System — Done

## Zusammenfassung

Allgemeiner ReAct-Query-Agent implementiert, der komplexe User-Fragen durch iteratives Recherchieren über GraphRAG und DocumentRAG beantwortet. Basiert auf Koog AIAgent + reActStrategy. Exponiert als eigenständiger GraphQL-Endpoint (`askAgent` Mutation + `agentTools` Query).

### Implementierte Komponenten

| Datei | Zweck |
|-------|-------|
| `agent/Models.kt` | AgentQueryConfig, AgentQueryResult, ToolInfo |
| `agent/AgentQueryTools.kt` | 2 Koog SimpleTool: KnowledgeQueryTool, DocumentQueryTool |
| `agent/AgentService.kt` | @Service — AIAgent mit reActStrategy erstellen + Query ausführen |
| `resources/graphql/agent.graphqls` | GraphQL-Schema mit askAgent Mutation + agentTools Query |
| `api/AgentController.kt` | @Controller — GraphQL Endpoint |

### Tests

| Test | Anzahl |
|------|--------|
| AgentQueryToolsTest | 2 Tests (KnowledgeQueryTool, DocumentQueryTool) |
| AgentServiceTest | 3 Tests (Tool-Liste, Descriptions, Config-Defaults) |
| AgentControllerTest | 3 Tests (Delegation, maxIterations, agentTools) |

## Abweichungen vom Feature-Dokument

1. **Koog AIAgent + reActStrategy** statt manuellem DefaultAgentService mit ReAct-Loop
2. **Kein AgentState Enum** — Koog managed den State intern
3. **Kein AgentContext/AgentIteration** — Koog gibt den Iterationsverlauf nicht direkt zurück
4. **Kein custom AgentTool Interface** — Koog SimpleTool
5. **Kein custom AgentService Interface** — direkte @Service Implementierung
6. **2 Tools** (knowledge_query, document_query) statt 3 (kein McpToolAdapter)
7. **AgentQueryResult vereinfacht** — nur answer + durationMs
8. **Eigenständiger Endpoint** — parallel zu NlpQueryService, kein Ersetzen
9. **Package `com.agentwork.graphmesh.agent`** statt `com.graphmesh.agent`

## Offene Punkte

- McpToolAdapter kann in Feature 26 (Tool Groups) hinzugefügt werden
- Streaming-Support (SSE) kann in Feature 27 ergänzt werden
- Integration mit NlpQueryService für automatische Agent-Weiterleitung möglich
