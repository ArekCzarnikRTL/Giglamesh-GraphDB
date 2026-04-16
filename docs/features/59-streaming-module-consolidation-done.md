# Feature 59: Streaming-Modul-Konsolidierung — Done

Abschlussdatum: 2026-04-16
Spec: [59-streaming-module-consolidation.md](59-streaming-module-consolidation.md)

## Zusammenfassung

Das `streaming/`-Package (3 Dateien) wurde vollstaendig in `agent/` verschoben. Die 4 Cross-Package-Imports (`AgentQueryConfig`, `DocumentQueryTool`, `KnowledgeQueryTool`, `ToolGroupRegistry`) sind jetzt Same-Package-Referenzen und entfallen als explizite Imports. `StreamingController` und `StreamingControllerTest` importieren aus `com.agentwork.graphmesh.agent`.

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/agent/StreamToken.kt`** — verschoben aus `streaming/`, Package-Deklaration auf `com.agentwork.graphmesh.agent` geaendert. Data class `StreamToken` (content, type, endOfMessage, endOfStream) und Enum `StreamTokenType` (TEXT, THOUGHT, ACTION, OBSERVATION, ANSWER, ERROR) unveraendert.
- **`src/main/kotlin/com/agentwork/graphmesh/agent/StreamingAgentService.kt`** — verschoben aus `streaming/`, Package-Deklaration geaendert. Interface mit `queryStreaming(question, collectionId, config, allowedGroups): Flow<StreamToken>` unveraendert.
- **`src/main/kotlin/com/agentwork/graphmesh/agent/StreamingAgentServiceImpl.kt`** — verschoben aus `streaming/`, Package-Deklaration geaendert. 4 Import-Zeilen fuer Agent-Typen entfallen, da jetzt im selben Package. SSE-Streaming-Logik, `tryParseTextualToolCall` und `parseToolQuestion` unveraendert.
- **`src/main/kotlin/com/agentwork/graphmesh/api/StreamingController.kt`** — 2 Imports von `streaming.*` auf `agent.*` geaendert.
- **`streaming/`-Package** — komplett entfernt (source + test), kein Verzeichnis mehr vorhanden.

### Tests

- **`src/test/kotlin/com/agentwork/graphmesh/agent/StreamingAgentServiceTest.kt`** — verschoben aus `streaming/`, Package-Deklaration geaendert.
- **`src/test/kotlin/com/agentwork/graphmesh/api/StreamingControllerTest.kt`** — 3 Imports von `streaming.*` auf `agent.*` geaendert.

## Akzeptanzkriterien

- [x] Package `streaming/` existiert nicht mehr (weder `src/main/` noch `src/test/`).
- [x] `agent/` enthaelt `StreamToken.kt`, `StreamingAgentService.kt`, `StreamingAgentServiceImpl.kt` mit korrekter Package-Deklaration `com.agentwork.graphmesh.agent`.
- [x] In `StreamingAgentServiceImpl.kt` keine Imports aus `com.agentwork.graphmesh.streaming` oder `com.agentwork.graphmesh.agent` fuer die 4 ehemals cross-package Typen.
- [x] `StreamingController.kt` und `StreamingControllerTest.kt` importieren aus `com.agentwork.graphmesh.agent`.
- [x] GraphQL-Schema `streaming.graphqls` unveraendert.
- [x] Bestehende Funktionalitaet (SSE-Streaming, Agent-Queries) unberuehrt.

## Abweichungen vom Feature-Dokument

Keine wesentlichen Abweichungen. Die Verschiebung erfolgte exakt wie spezifiziert.

## Commits

```
db254ab refactor(agent): consolidate streaming/ package into agent/ (feature 59)
```
