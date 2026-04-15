# Feature 17: MCP Tool Interface — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/api/mcp/GraphMeshMcpTools.kt`** — `@Service`-Klasse, die alle vier MCP-Tools ueber die Spring-AI-Annotationen `@McpTool` und `@McpToolParam` (Package `org.springframework.ai.mcp.annotation`) deklariert:
  - `knowledgeQuery(question, collectionId, maxEdges?)` — delegiert an `GraphRagService.query`, formatiert Antwort + Quellen (`subject --[predicate]--> object` + `Reasoning: ...`).
  - `documentQuery(question, collectionId, topK?)` — delegiert an `DocumentRagService.query`, formatiert Antwort + Quellen (`title`, `page`, `score`, `snippet`).
  - `collectionList(tags?)` — delegiert an `CollectionService.findAll(tagSet)`; Tag-Parsing per Komma-Trim.
  - `documentSearch(collectionId, titleFilter?)` — delegiert an `LibrarianService.findByCollection`, case-insensitiver Titel-Filter.
- **`src/main/resources/application.yml`** — Konfiguration `spring.ai.mcp.server.name=graphmesh`, `version=1.0.0`, `protocol=STREAMABLE`. Kein Bearer-Token konfiguriert.
- **`build.gradle.kts`** — Dependency `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` (Spring AI MCP Server, nicht das rohe MCP Kotlin SDK).

### Tests

- **`GraphMeshMcpToolsTest`** — 11 MockK-Unit-Tests: `knowledgeQuery` (Format, Default-/Custom-`maxEdges`), `documentQuery` (Format, Default-/Custom-`topK`), `collectionList` (Format, Empty-Message, Tag-Parsing), `documentSearch` (Format, Empty-Message, Case-insensitive Titelfilter).
- **`McpServerSmokeTest`** — Optionaler HTTP-Smoke-Test gegen eine laufende Instanz, aktiviert via `MCP_SMOKE=1`. Schickt echten JSON-RPC (`initialize` + `notifications/initialized` + `tools/list`) gegen den Streamable-HTTP-Endpunkt `/mcp`, prueft `serverInfo.name == "graphmesh"` und Vorhandensein von `knowledgeQuery`/`documentQuery`. Parsen von JSON **und** SSE-Frames (`data:`-Zeilen).

## Abweichungen vom Feature-Dokument

- **Framework-Wechsel**: Spec beschreibt eine eigene MCP-Server-Implementierung auf Basis des **MCP Kotlin SDK** (`io.modelcontextprotocol:kotlin-sdk`), mit `McpTool`-Interface, `McpToolDefinition`-Datenklassen, `DefaultMcpServer`, `McpSseController`, `StaticMcpAuthProvider` etc. Tatsaechlich genutzt wird **Spring AI MCP Server** (`spring-ai-starter-mcp-server-webmvc`) mit Annotation-basierter Tool-Registrierung. Keine der Spec-Klassen existiert im Codebase.
- **Transport ist Streamable HTTP (nicht SSE)**: `spring.ai.mcp.server.protocol=STREAMABLE`. Kein `/mcp/sse` + `/mcp/messages`; der Spring-AI-Starter stellt den Endpoint unter `/mcp` bereit. Der Smoke-Test handhabt beide Response-Varianten (JSON und SSE-Framing).
- **Keine Bearer-Token-Authentifizierung**: `McpAuthProvider` und `StaticMcpAuthProvider` sind nicht implementiert. Auth-Config (`graphmesh.mcp.auth-token`) fehlt komplett. Der MCP-Server ist aktuell offen (bzw. nur durch Netzwerkebene abgesichert).
- **Tool-Namen in camelCase**: Die Tools heissen `knowledgeQuery`, `documentQuery`, `collectionList`, `documentSearch` (Methodennamen), nicht snake_case (`knowledge_query` etc.).
- **Keine typisierte `McpToolDefinition`-Struktur**: Argument-Schemas werden komplett vom Spring-AI-Starter aus den Kotlin-Parametertypen generiert. `McpToolArgument`, `McpToolResult`, `required`-Flag werden als Parameter von `@McpToolParam(required=...)` ausgedrueckt.
- **Rueckgabetyp ist `String`** (formatierte Multi-Line-Antwort), nicht `McpToolResult` mit `isError`-Flag. Fehlerbehandlung erfolgt implizit ueber Exceptions, die der Spring-AI-Starter in MCP-Error-Responses umwandelt.
- **Keine eigenen JSON-RPC-Handler** (`initialize`, `tools/list`, `tools/call`): Der Spring-AI-Starter erledigt den gesamten JSON-RPC-Stack.

## Akzeptanzkriterien

- [x] MCP-Server antwortet auf `initialize`-Request mit Protokollversion und Capabilities (vom Spring-AI-Starter bereitgestellt, verifiziert via `McpServerSmokeTest`)
- [x] `tools/list` gibt alle vier Tools zurueck (`knowledgeQuery`, `documentQuery`, `collectionList`, `documentSearch` — camelCase statt snake_case)
- [x] Jedes Tool hat typisierte Argumente mit Name, Typ und Beschreibung im JSON-Schema-Format (Spring-AI generiert Schema aus Kotlin-Typen + `@McpToolParam`-Metadaten)
- [x] `tools/call` fuehrt das Tool mit validierten Argumenten aus und gibt Ergebnis zurueck
- [x] Fehlende Pflichtargumente werden mit einem Validierungsfehler abgelehnt (vom Starter durchgesetzt)
- [ ] Bearer-Token-Authentifizierung blockiert unautorisierte Requests mit Fehlercode -32000 — **nicht** implementiert
- [ ] Bei leerem konfiguriertem Token ist die Authentifizierung deaktiviert (Entwicklungsmodus) — obsolet, da keine Auth existiert
- [ ] SSE-Endpunkt unter `/mcp/sse` sendet Endpoint-Event mit Messages-URL — **nicht zutreffend**, Streamable HTTP statt SSE, Endpunkt ist `/mcp`
- [ ] Messages-Endpunkt unter `/mcp/messages` verarbeitet JSON-RPC-Requests — **nicht zutreffend**, Single-Endpoint `/mcp`
- [x] Unbekannte Methoden werden mit Fehlercode -32601 beantwortet (vom Starter)
- [ ] Tool-Ergebnisse enthalten `isError`-Flag bei fehlgeschlagenen Ausfuehrungen — Tools geben `String` zurueck; Fehler werden als Exception/JSON-RPC-Error propagiert, kein explizites Flag im Payload

## Offene Punkte

- Bearer-Token-Authentifizierung nachruesten, falls der MCP-Server nicht nur hinter einem VPN/Gateway steht.
- Explizite `isError`-Semantik: aktuell werden Fehler per Exception weitergegeben; eventuell `McpToolResult`-artige Rueckgabe mit `isError: Boolean` etablieren.
