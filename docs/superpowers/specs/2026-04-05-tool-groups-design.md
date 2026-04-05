# Feature 26: Tool Groups — Design Spec

## Zusammenfassung

Minimales Tool-Gruppen-System für den Agent (Feature 25). Tools werden in Gruppen organisiert (basic, advanced, read-only, all). Pro `askAgent`-Anfrage können erlaubte Gruppen angegeben werden. YAGNI: Kein FlowDefinition, kein ToolServiceDescriptor, keine State-basierte Verfügbarkeit.

## Entscheidungen

| Entscheidung | Gewählt | Grund |
|---|---|---|
| Scope | Nur Tool Groups + Request-Filtering | YAGNI — Rest hat keinen aktuellen Use-Case |
| FlowDefinition/FlowInstance | Weggelassen | Kein Blueprint-Pattern-Use-Case vorhanden |
| ToolServiceDescriptor | Weggelassen | Keine externen Tool-Services |
| State-basierte Verfügbarkeit | Weggelassen | Koog managed den Agent-State intern |

## Änderungen

### Models.kt — Erweitert
- `ToolInfo` bekommt `groups: List<String>` Feld
- Neues `ToolGroup(name, description, toolNames: Set<String>)` data class

### ToolGroupRegistry.kt — NEU (@Component)
- Vordefinierte Gruppen: `all`, `basic`, `advanced`, `read-only`
- `getGroups(): List<ToolGroup>`
- `resolveToolNames(allowedGroups: Set<String>): Set<String>` — Union aller Tool-Namen aus den angefragten Gruppen

### AgentService.kt — Erweitert
- `query()` bekommt `allowedGroups: Set<String> = setOf("all")`
- ToolRegistry-Erstellung filtert basierend auf `toolGroupRegistry.resolveToolNames(allowedGroups)`
- `getAvailableTools()` enthält jetzt `groups`-Info

### AgentController.kt — Erweitert
- `AgentQueryInput` bekommt `allowedGroups: List<String>?`
- Neues `@QueryMapping fun toolGroups(): List<ToolGroup>`

### agent.graphqls — Erweitert
- `AgentQueryInput` um `allowedGroups: [String!]`
- `ToolInfo` um `groups: [String!]!`
- `ToolGroup` Type + `toolGroups` Query

## Tool-Gruppen-Zuordnung

| Tool | basic | advanced | read-only | all |
|------|-------|----------|-----------|-----|
| knowledge_query | x | x | x | x |
| document_query | | x | x | x |
