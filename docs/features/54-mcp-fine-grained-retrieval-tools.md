# Feature 54: Fein-granulare MCP-Retrieval-Tools

## Problem

Die MCP-Tools in GraphMesh (Feature 17 + Feature 48) decken **Write-Side**
und **High-Level-RAG** sehr gut ab (`knowledgeQuery`, `documentQuery`,
`agentQuery`, Ontologie-/Daten-CRUD — zusammen ca. 15 Tools). Die
**Retrieval-Seite** ist jedoch unterversorgt: Ein externer Agent (Claude
Desktop, Cursor, OpenCode, ChatGPT mit MCP) kann nur "ask a question and
get an answer". Er kann nicht:

1. **Gezielt nach einem Topic suchen**, ohne einen ganzen RAG-Lauf zu triggern.
2. **Eine Definition eines Begriffs nachschlagen**, ohne dass das
   Antwort-LLM sie aus RAG-Kontext neu synthetisieren muss.
3. **Nachbar-Entities finden** ("Welche Begriffe haengen mit X zusammen?").
4. **Chunks nach Themen filtern** oder einzelne Chunks im Rohtext abrufen.

MemPalace bietet nicht zufaellig 19 MCP-Tools. Feine, deterministische
Tools machen es dem client-seitigen LLM leichter, **den richtigen Baustein
zu waehlen**, statt jedes Mal den teuren RAG-Lauf zu fahren.

Konkretes Nutzer-Szenario: Claude sieht in einem Coding-Kontext "was ist
Permafrost im Projekt-Glossar?" und ruft `getDefinition(term="Permafrost")`
— Antwort in ~50 ms aus dem Graphen, keine LLM-Synthese noetig, keine
Halluzinationsgefahr.

## Ziel

Sechs neue fein-granulare MCP-Tools in `GraphMeshMcpTools`, die direkt auf
den bestehenden Daten-Layern operieren (kein zweiter LLM-Call).

1. **`getDefinition`** — Schlaegt `rdfs:comment` eines Entities nach.
2. **`searchByTopic`** — Listet Chunks/Entities innerhalb eines Topics.
3. **`listTopics`** — Top-N Topics einer Collection (Facetten-Uebersicht).
4. **`findRelated`** — Multi-Hop-Nachbarn einer Entitaet im Graph.
5. **`getChunk`** — Rohtext eines Chunks anhand ID.
6. **`listEntities`** — Entities einer Collection mit Filter (Praefix/Typ/Topic).

## Voraussetzungen

| Abhaengigkeit                              | Status       | Blocker? |
|--------------------------------------------|--------------|----------|
| Feature 17 (MCP Tool Interface)            | Implementiert | Ja       |
| Feature 19 (Definition Extractor)          | Implementiert | Ja (fuer getDefinition sinnvoll) |
| Feature 38 (Topic Extractor)               | Implementiert | Ja (fuer listTopics/searchByTopic) |
| Feature 53 (Topic Pre-Filter)              | Geplant      | Nein (kann parallel, searchByTopic profitiert aber vom Index) |
| Spring AI MCP Server                       | Implementiert | Ja       |

Die Tools werden wie die bestehenden via `@McpTool` an `GraphMeshMcpTools`
angehaengt — Auto-Registration laeuft ueber Spring AI's
`MethodToolCallbackProvider` (Standard-Pattern, siehe
`spring-ai-starter-mcp-server`-Docs).

## Architektur

### Tool-Signaturen

```kotlin
// com.agentwork.graphmesh.api.mcp.GraphMeshMcpTools (Erweiterung)

@McpTool(description = "Look up the canonical definition (rdfs:comment) of a term in a collection. Returns the definition text or an empty result if none exists. Cheap, deterministic, no LLM synthesis.")
fun getDefinition(
    @McpToolParam(description = "Collection ID") collectionId: String,
    @McpToolParam(description = "Term or entity label to look up (exact or case-insensitive match)") term: String
): DefinitionResult

@McpTool(description = "List top topics in a collection sorted by number of associated chunks. Use this to discover facets before filtering.")
fun listTopics(
    @McpToolParam(description = "Collection ID") collectionId: String,
    @McpToolParam(description = "Max topics to return (default 20)", required = false) limit: Int?
): List<TopicFacet>

@McpTool(description = "Find chunks within a collection tagged with a specific topic. Returns chunk summaries; use getChunk() to fetch raw text.")
fun searchByTopic(
    @McpToolParam(description = "Collection ID") collectionId: String,
    @McpToolParam(description = "Topic label (must match one from listTopics)") topic: String,
    @McpToolParam(description = "Max chunks to return (default 10)", required = false) limit: Int?
): List<ChunkSummary>

@McpTool(description = "Find entities connected to a given entity within N hops in the knowledge graph. Useful for 'what is related to X?' questions without a full RAG call.")
fun findRelated(
    @McpToolParam(description = "Collection ID") collectionId: String,
    @McpToolParam(description = "Entity label or URI") entity: String,
    @McpToolParam(description = "Number of hops (1–3, default 1)", required = false) hops: Int?,
    @McpToolParam(description = "Max edges to return (default 25)", required = false) limit: Int?
): List<RelatedEdge>

@McpTool(description = "Return the raw text of a specific chunk. Use chunk IDs returned by searchByTopic or documentQuery.")
fun getChunk(
    @McpToolParam(description = "Chunk ID") chunkId: String
): ChunkRawResult

@McpTool(description = "List entities in a collection. Optional filters: prefix match on label, topic filter, max results.")
fun listEntities(
    @McpToolParam(description = "Collection ID") collectionId: String,
    @McpToolParam(description = "Label prefix (case-insensitive, optional)", required = false) labelPrefix: String?,
    @McpToolParam(description = "Only entities tagged with this topic (optional)", required = false) topic: String?,
    @McpToolParam(description = "Max entities to return (default 50)", required = false) limit: Int?
): List<EntitySummary>
```

### Return-DTOs

```kotlin
data class DefinitionResult(
    val term: String,
    val definition: String?,       // null = keine Definition gespeichert
    val source: String? = null     // z.B. ChunkId, aus dem sie stammt
)

data class TopicFacet(
    val topic: String,
    val chunkCount: Int
)

data class ChunkSummary(
    val chunkId: String,
    val documentId: String,
    val documentTitle: String,
    val excerpt: String,           // ersten ~200 Zeichen
    val topics: List<String>
)

data class RelatedEdge(
    val subject: String,
    val predicate: String,
    val objectValue: String,
    val hop: Int
)

data class ChunkRawResult(
    val chunkId: String,
    val text: String,
    val documentId: String
)

data class EntitySummary(
    val uri: String,
    val label: String,
    val topics: List<String>,
    val hasDefinition: Boolean
)
```

### Backend-Services

Jedes Tool delegiert an einen bestehenden oder einen kleinen neuen Service
— **keine Logik im Tool-Layer**, nur Mapping auf DTOs:

| Tool            | Service                                                                    |
|-----------------|----------------------------------------------------------------------------|
| `getDefinition` | Neue Methode `DefinitionLookupService.findDefinition(cid, term)` → `QuadStore.findQuads(s, rdfs:comment)` |
| `listTopics`    | `ChunksByTopicRepository.listDistinctTopicsWithCount` (aus Feature 53)      |
| `searchByTopic` | `ChunksByTopicRepository.findChunks(cid, topic, limit)` + LibrarianService  |
| `findRelated`   | Neue Methode `GraphTraversalService.neighbors(cid, entity, hops, limit)`    |
| `getChunk`      | `LibrarianService.getContent(chunkId)` + Wrapping                          |
| `listEntities`  | Neue Methode `EntityBrowserService.list(cid, prefix, topic, limit)`        |

Die Lookups nutzen bereits existierende Cassandra-Indizes (Quad-Store,
`chunks_by_topic` aus Feature 53, Librarian-Blobs). Typische
Antwortzeit < 100 ms, damit MCP-Clients nicht timeouten.

### Spring AI MCP Registration

`GraphMeshMcpTools` wird bereits heute via `MethodToolCallbackProvider`
registriert (Pattern aus Spring AI 2.0.0-M3 Docs). Neue Methoden brauchen
**nichts weiter** als die `@McpTool`-Annotation — Auto-Discovery kuemmert
sich um den Rest. Kein neuer Bean, keine Schema-Datei.

### Tool-Design-Prinzipien

- **Idempotent + seiteneffektfrei** — nur Reads. Writes bleiben in Feature 48.
- **Schnell** — jeder Call unter 200 ms (Durchschnitt), keine LLM-Aufrufe.
- **Kompakte Responses** — max ~2 KB JSON, damit MCP-Client-Kontext nicht
  gesprengt wird. Bei groesseren Resultaten: Hinweis "increase limit or
  use documentQuery".
- **Descriptions sind UX.** Jede `@McpTool(description=...)` muss dem LLM
  klar machen *wann* es dieses Tool waehlen soll vs. ein anderes.

## Betroffene Dateien

### Backend

| Datei                                                 | Aenderung                                |
|-------------------------------------------------------|------------------------------------------|
| `api/mcp/GraphMeshMcpTools.kt`                        | 6 neue `@McpTool`-Methoden               |
| `api/mcp/McpDtos.kt`                                  | NEU — gemeinsame Return-DTOs             |
| `definition/DefinitionLookupService.kt`               | NEU                                      |
| `graph/GraphTraversalService.kt`                      | Neue Methode `neighbors(...)`            |
| `entity/EntityBrowserService.kt`                      | NEU                                      |
| `storage/ChunksByTopicRepository.kt`                  | `listDistinctTopicsWithCount` (erweitert)|

### Frontend

Keine Aenderungen — das Feature exponiert ausschliesslich MCP-Endpoints.

### Tests

| Datei                                                       | Aenderung                                      |
|-------------------------------------------------------------|------------------------------------------------|
| `api/mcp/GraphMeshMcpToolsTest.kt`                          | 6 neue Testfaelle                              |
| `definition/DefinitionLookupServiceTest.kt`                 | NEU                                            |
| `graph/GraphTraversalServiceTest.kt`                        | `neighbors` Happy + Empty                      |
| `tests/mcp-smoke-test.sh`                                   | Calls fuer alle 6 neuen Tools ergaenzen        |

## Akzeptanzkriterien

- [ ] Alle 6 Tools erscheinen in `tools/list` eines MCP-Clients (getestet mit `claude mcp list`).
- [ ] `getDefinition("Permafrost")` gibt die `rdfs:comment`-Literal zurueck, nicht via LLM-Synthese.
- [ ] `listTopics` liefert pro Collection ein deterministisches Ergebnis (Sortierung: chunkCount DESC, topic ASC).
- [ ] `findRelated(hops=1)` liefert genau die direkten Nachbarn; `hops=2` expandiert um eine Ebene.
- [ ] `getChunk` liefert den **Originaltext** (nicht die Embedding-saniierte Version).
- [ ] Durchschnittliche Response-Zeit aller 6 Tools < 200 ms auf Referenz-Setup (5k Chunks).
- [ ] `mcp-smoke-test.sh` testet jedes Tool mindestens einmal erfolgreich.
- [ ] Keine Aenderung an bestehenden Tools — externe Clients, die heute funktionieren, funktionieren weiter.
