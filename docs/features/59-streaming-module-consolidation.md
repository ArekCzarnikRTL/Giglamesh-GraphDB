# Feature 59: Streaming-Modul-Konsolidierung (streaming/ → agent/)

## Problem

Das `streaming/`-Package (3 Dateien, 259 LOC) ist ein Thin Wrapper um `AgentService.query()` mit SSE-Streaming. `StreamingAgentServiceImpl` importiert 4 Typen aus `agent/` (`AgentQueryConfig`, `DocumentQueryTool`, `KnowledgeQueryTool`, `ToolGroupRegistry`). Es hat eine 51:1 Shallow-Ratio — die zweitflachste im Codebase. Das 1-Methoden-Interface `StreamingAgentService` hat keinen eigenen Abstraktionswert. Die `internal`-Methoden `tryParseTextualToolCall` und `parseToolQuestion` funktionieren nur zufaellig, weil `streaming` ein separates Compilation-Unit ist.

## Ziel

Zusammenfuehrung des `streaming/`-Packages in `agent/`, sodass Cross-Package-Abhaengigkeiten zu Same-Package-Referenzen werden und die `internal`-Visibility korrekt greift.

1. **Verschiebung aller 3 Dateien** aus `streaming/` nach `agent/` (Package-Wechsel).
2. **4 Cross-Package-Imports werden Same-Package-Referenzen** — entfallen ersatzlos.
3. **`internal`-Visibility fuer `tryParseTextualToolCall`/`parseToolQuestion`** funktioniert korrekt, weil `agent/` ein einziges Compilation-Unit bildet.
4. **`agent/` waechst von 5 auf 8 Dateien** — noch komfortabel flach.
5. **StreamingController und StreamingControllerTest in `api/`** aendern nur Imports (von `streaming.*` auf `agent.*`).
6. **GraphQL-Schema `streaming.graphqls`** bleibt unveraendert.

## Voraussetzungen

| Abhaengigkeit                         | Status        | Blocker? |
|---------------------------------------|---------------|----------|
| Feature 25 (Agent System)             | Implementiert | Nein     |
| Feature 27 (Streaming)                | Implementiert | Nein     |

Keine Infra-Aenderung in docker-compose oder Build-Konfiguration.

## Architektur

### Ist-Zustand

```kotlin
// streaming/StreamingAgentServiceImpl.kt
package com.agentwork.graphmesh.streaming

import com.agentwork.graphmesh.agent.AgentQueryConfig
import com.agentwork.graphmesh.agent.DocumentQueryTool
import com.agentwork.graphmesh.agent.KnowledgeQueryTool
import com.agentwork.graphmesh.agent.ToolGroupRegistry

class StreamingAgentServiceImpl(
    private val agentService: AgentService,
    private val toolGroupRegistry: ToolGroupRegistry,
) : StreamingAgentService {

    override fun streamQuery(question: String, config: AgentQueryConfig): Flow<StreamToken> {
        // ... SSE-Streaming-Logik, delegiert an agentService.query()
    }

    internal fun tryParseTextualToolCall(text: String): ToolCall? { /* ... */ }
    internal fun parseToolQuestion(text: String): String? { /* ... */ }
}
```

4 explizite Cross-Package-Imports aus `agent/`. Die `internal`-Modifier auf den Parse-Hilfsmethoden schuetzen nur zufaellig, weil Kotlin `internal` auf Modul-Ebene (= Compilation-Unit) arbeitet und das gesamte Backend ein einzelnes Modul ist — die Methoden sind faktisch oeffentlich fuer alle Packages im selben Modul.

### Soll-Zustand

```kotlin
// agent/StreamingAgentServiceImpl.kt
package com.agentwork.graphmesh.agent

// Keine Cross-Package-Imports mehr fuer AgentQueryConfig, DocumentQueryTool,
// KnowledgeQueryTool, ToolGroupRegistry — alles im selben Package.

class StreamingAgentServiceImpl(
    private val agentService: AgentService,
    private val toolGroupRegistry: ToolGroupRegistry,
) : StreamingAgentService {

    override fun streamQuery(question: String, config: AgentQueryConfig): Flow<StreamToken> {
        // ... Logik identisch, nur Package-Deklaration geaendert
    }

    internal fun tryParseTextualToolCall(text: String): ToolCall? { /* ... */ }
    internal fun parseToolQuestion(text: String): String? { /* ... */ }
}
```

Gleiche Klasse, nur `package`-Zeile geaendert. Die 4 Import-Zeilen fuer Agent-Typen entfallen komplett. `internal`-Visibility bleibt bestehen und ist nun semantisch korrekt innerhalb des Moduls.

### Subsection 1: File-Mapping

| Aktion   | Quelle                                          | Ziel                                           |
|----------|--------------------------------------------------|-------------------------------------------------|
| MOVE     | `streaming/StreamToken.kt`                       | `agent/StreamToken.kt`                          |
| MOVE     | `streaming/StreamingAgentService.kt`             | `agent/StreamingAgentService.kt`                |
| MOVE     | `streaming/StreamingAgentServiceImpl.kt`         | `agent/StreamingAgentServiceImpl.kt`            |
| MOVE     | test: `streaming/StreamingAgentServiceTest.kt`   | test: `agent/StreamingAgentServiceTest.kt`      |
| DELETE   | `streaming/` Package (source + test)             | —                                               |

### Subsection 2: Import-Anpassungen in api/

```kotlin
// api/StreamingController.kt — vorher
import com.agentwork.graphmesh.streaming.StreamingAgentService
import com.agentwork.graphmesh.streaming.StreamToken

// api/StreamingController.kt — nachher
import com.agentwork.graphmesh.agent.StreamingAgentService
import com.agentwork.graphmesh.agent.StreamToken
```

```kotlin
// api/StreamingControllerTest.kt — vorher
import com.agentwork.graphmesh.streaming.StreamingAgentService
import com.agentwork.graphmesh.streaming.StreamToken
import com.agentwork.graphmesh.streaming.StreamingAgentServiceImpl

// api/StreamingControllerTest.kt — nachher
import com.agentwork.graphmesh.agent.StreamingAgentService
import com.agentwork.graphmesh.agent.StreamToken
import com.agentwork.graphmesh.agent.StreamingAgentServiceImpl
```

### Subsection 3: Unveraenderte Artefakte

- `src/main/resources/graphql/streaming.graphqls` — Schema bleibt identisch, GraphQL-Typen und Subscriptions sind package-unabhaengig.
- `application.yml` — keine Konfigurationsaenderungen.
- `docker-compose.yaml` — keine Infra-Aenderungen.

## Betroffene Dateien

### Backend

| Datei                                                                                                  | Aenderung                                                                                |
|--------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/streaming/StreamToken.kt`                                    | MOVE nach `agent/StreamToken.kt` — Package-Deklaration aendern                          |
| `src/main/kotlin/com/agentwork/graphmesh/streaming/StreamingAgentService.kt`                           | MOVE nach `agent/StreamingAgentService.kt` — Package-Deklaration aendern                 |
| `src/main/kotlin/com/agentwork/graphmesh/streaming/StreamingAgentServiceImpl.kt`                      | MOVE nach `agent/StreamingAgentServiceImpl.kt` — Package-Deklaration aendern, 4 Imports entfernen |
| `src/main/kotlin/com/agentwork/graphmesh/api/StreamingController.kt`                                  | 2 Imports von `streaming.*` auf `agent.*` aendern                                        |

### Frontend

Keine Aenderungen.

### Tests

| Datei                                                                                                  | Aenderung                                                                                |
|--------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/streaming/StreamingAgentServiceTest.kt`                      | MOVE nach `agent/StreamingAgentServiceTest.kt` — Package-Deklaration aendern             |
| `src/test/kotlin/com/agentwork/graphmesh/api/StreamingControllerTest.kt`                              | 3 Imports von `streaming.*` auf `agent.*` aendern                                        |

## Akzeptanzkriterien

- [ ] Das Package `streaming/` existiert nicht mehr (weder unter `src/main/` noch `src/test/`).
- [ ] `agent/` enthaelt die 3 verschobenen Dateien (`StreamToken.kt`, `StreamingAgentService.kt`, `StreamingAgentServiceImpl.kt`) mit korrekter Package-Deklaration `com.agentwork.graphmesh.agent`.
- [ ] In `StreamingAgentServiceImpl.kt` gibt es keine Imports aus `com.agentwork.graphmesh.streaming` oder `com.agentwork.graphmesh.agent` fuer die 4 ehemals cross-package Typen (`AgentQueryConfig`, `DocumentQueryTool`, `KnowledgeQueryTool`, `ToolGroupRegistry`).
- [ ] `StreamingController.kt` und `StreamingControllerTest.kt` importieren `StreamingAgentService` und `StreamToken` aus `com.agentwork.graphmesh.agent`.
- [ ] `./gradlew build` kompiliert fehlerfrei.
- [ ] Alle bestehenden Tests (Unit + Integration) bleiben gruen.
- [ ] GraphQL-Schema `streaming.graphqls` ist unveraendert.
- [ ] Bestehende Funktionalitaet (SSE-Streaming, Agent-Queries) bleibt unberuehrt.
