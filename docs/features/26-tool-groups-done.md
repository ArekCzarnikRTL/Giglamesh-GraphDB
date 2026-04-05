# Feature 26: Tool Groups — Done

## Zusammenfassung

Minimales Tool-Gruppen-System implementiert. Tools werden in 4 vordefinierte Gruppen organisiert (all, basic, advanced, read-only). Pro `askAgent`-Anfrage können erlaubte Gruppen angegeben werden — der Agent bekommt nur die Tools der angegebenen Gruppen.

### Implementierte Komponenten

| Datei | Zweck |
|-------|-------|
| `agent/ToolGroupRegistry.kt` | @Component — 4 vordefinierte Gruppen, resolveToolNames(), getGroupsForTool() |
| `agent/Models.kt` | Erweitert: ToolInfo.groups, neues ToolGroup data class |
| `agent/AgentService.kt` | Erweitert: allowedGroups Parameter, Tool-Filtering via Registry |
| `api/AgentController.kt` | Erweitert: allowedGroups in Input, toolGroups Query |
| `resources/graphql/agent.graphqls` | Erweitert: ToolGroup Type, toolGroups Query, allowedGroups Input |

### Tests

| Test | Anzahl |
|------|--------|
| ToolGroupRegistryTest | 9 Tests (Gruppen-Auflösung, Union, Edge Cases) |
| AgentServiceTest | 4 Tests (aktualisiert für Groups) |
| AgentControllerTest | 5 Tests (aktualisiert + allowedGroups + toolGroups) |

### Tool-Gruppen

| Gruppe | Tools |
|--------|-------|
| all | knowledge_query, document_query |
| basic | knowledge_query |
| advanced | knowledge_query, document_query |
| read-only | knowledge_query, document_query |

## Abweichungen vom Feature-Dokument

1. **YAGNI-Scope** — Nur Tool Groups + Request-Filtering implementiert
2. **Weggelassen:** FlowDefinition, FlowInstance, ToolServiceDescriptor, ToolServiceConfig, ProcessorSpec, ConfigParam, State-basierte Verfügbarkeit
3. **Kein custom ToolServiceRegistry Interface** — direkte @Component Implementierung
4. **Kein Kafka-basierter Tool-Service** — keine externen Tools vorhanden
5. **Statische Gruppen-Definition** — Gruppen sind im Code definiert, nicht per ConfigService

## Offene Punkte

- Dynamische Tool-Registration via ConfigService kann bei Bedarf ergänzt werden
- FlowDefinition/FlowInstance Pattern kann als separates Feature implementiert werden wenn ein Use-Case entsteht
