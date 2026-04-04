# Feature 17: MCP Tool Interface

## Problem

Externe LLM-Agenten (z.B. Claude, GPT-basierte Systeme) koennen nicht auf die Wissensbasis von GraphMesh zugreifen, da
keine standardisierte Schnittstelle fuer Tool-Aufrufe existiert. Ohne eine MCP-Server-Implementierung muessen fuer jeden
LLM-Client individuelle Integrationen gebaut werden. Das Model Context Protocol (MCP) bietet einen offenen Standard,
ueber den LLMs Tools aufrufen koennen -- inklusive Argument-Validierung, Authentifizierung und Streaming.

## Ziel

Implementierung eines MCP-Servers, der GraphMesh-Funktionalitaeten als LLM-Tools exponiert,
Bearer-Token-Authentifizierung unterstuetzt und SSE-Transport fuer Streaming bereitstellt.

1. **MCP-Server** -- Standardkonforme MCP-Server-Implementierung mit SSE-Transport
2. **Tool-Definitionen** -- Exponierung von knowledge_query (Graph RAG), document_query (Doc RAG), collection_list und
   document_search als MCP-Tools
3. **Argument-Validierung** -- Typisierte Tool-Argumente mit Validierung gemaess MCP-Spezifikation
4. **Bearer-Token-Auth** -- Authentifizierung via Bearer-Token im Authorization-Header
5. **Streaming-Support** -- Server-Sent Events fuer Echtzeit-Antworten bei RAG-Queries

## Voraussetzungen

| Abhaengigkeit                                                | Status     | Blocker? |
|--------------------------------------------------------------|------------|----------|
| Feature 05: LLM Provider Abstraction (ChatCompletionService) | Geplant    | Ja       |
| Feature 14: GraphQL API (als interne Datenquelle)            | Geplant    | Ja       |
| Feature 15: Graph RAG (GraphRagService)                      | Geplant    | Ja       |
| Feature 16: Document RAG (DocumentRagService)                | Geplant    | Ja       |
| MCP Kotlin SDK (io.modelcontextprotocol:kotlin-sdk)          | Verfuegbar | Nein     |
| Spring Boot Starter Web (fuer SSE-Endpunkt)                  | Verfuegbar | Nein     |

## Architektur

### Datenmodell

```kotlin
package com.graphmesh.api.mcp

/**
 * Definition eines MCP-Tools mit typisierten Argumenten.
 */
data class McpToolDefinition(
    /** Eindeutiger Name des Tools (z.B. "knowledge_query"). */
    val name: String,
    /** Beschreibung des Tools fuer das LLM. */
    val description: String,
    /** Liste der erwarteten Argumente. */
    val arguments: List<McpToolArgument>
)

/**
 * Ein einzelnes Argument eines MCP-Tools.
 */
data class McpToolArgument(
    /** Name des Arguments. */
    val name: String,
    /** Typ des Arguments (string, number, boolean). */
    val type: String,
    /** Beschreibung fuer das LLM. */
    val description: String,
    /** Ob das Argument pflichtmaessig ist. */
    val required: Boolean = true
)

/**
 * Ergebnis eines MCP-Tool-Aufrufs.
 */
data class McpToolResult(
    /** Textuelle Antwort des Tools. */
    val content: String,
    /** Ob der Aufruf erfolgreich war. */
    val isError: Boolean = false
)
```

### Service-Interfaces

```kotlin
package com.graphmesh.api.mcp

/**
 * MCP-Server-Schnittstelle.
 * Verwaltet Tool-Registrierung, Authentifizierung und Ausfuehrung.
 */
interface McpServer {

    /**
     * Registriert ein Tool beim MCP-Server.
     */
    fun registerTool(tool: McpTool)

    /**
     * Gibt alle registrierten Tool-Definitionen zurueck.
     * Wird vom MCP-Client bei tools/list aufgerufen.
     */
    fun listTools(): List<McpToolDefinition>

    /**
     * Fuehrt ein Tool mit den gegebenen Argumenten aus.
     * Wird vom MCP-Client bei tools/call aufgerufen.
     *
     * @param toolName Name des aufzurufenden Tools.
     * @param arguments Argumente als Key-Value-Map.
     * @return Ergebnis des Tool-Aufrufs.
     */
    suspend fun executeTool(toolName: String, arguments: Map<String, Any>): McpToolResult
}

/**
 * Einzelnes MCP-Tool mit Ausfuehrungslogik.
 */
interface McpTool {

    /** Tool-Definition (Name, Beschreibung, Argumente). */
    val definition: McpToolDefinition

    /**
     * Fuehrt das Tool aus.
     *
     * @param arguments Validierte Argumente.
     * @return Ergebnis des Tool-Aufrufs.
     */
    suspend fun execute(arguments: Map<String, Any>): McpToolResult

    /**
     * Validiert die uebergebenen Argumente.
     *
     * @param arguments Die zu validierenden Argumente.
     * @throws IllegalArgumentException bei ungueltigem Argument.
     */
    fun validateArguments(arguments: Map<String, Any>) {
        for (arg in definition.arguments) {
            if (arg.required && !arguments.containsKey(arg.name)) {
                throw IllegalArgumentException(
                    "Pflichtargument '${arg.name}' fehlt fuer Tool '${definition.name}'"
                )
            }
        }
    }
}

/**
 * Authentifizierungsprovider fuer MCP-Requests.
 * Validiert Bearer-Tokens im Authorization-Header.
 */
interface McpAuthProvider {

    /**
     * Validiert ein Bearer-Token.
     *
     * @param token Das Bearer-Token aus dem Authorization-Header.
     * @return true, wenn das Token gueltig ist.
     */
    suspend fun validateToken(token: String): Boolean
}
```

### Tool-Implementierungen

```kotlin
package com.graphmesh.api.mcp.tools

import com.graphmesh.api.mcp.McpTool
import com.graphmesh.api.mcp.McpToolArgument
import com.graphmesh.api.mcp.McpToolDefinition
import com.graphmesh.api.mcp.McpToolResult
import com.graphmesh.query.graphrag.GraphRagQuery
import com.graphmesh.query.graphrag.GraphRagService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * MCP-Tool fuer Knowledge-Graph-Abfragen via Graph RAG.
 */
@Component
class KnowledgeQueryTool(
    private val graphRagService: GraphRagService
) : McpTool {

    override val definition = McpToolDefinition(
        name = "knowledge_query",
        description = "Beantwortet Fragen anhand des Knowledge Graphs. " +
            "Sucht relevante Entitaeten und Beziehungen und generiert " +
            "eine quellenbasierte Antwort.",
        arguments = listOf(
            McpToolArgument(
                name = "question",
                type = "string",
                description = "Die natuerlichsprachige Frage"
            ),
            McpToolArgument(
                name = "collection_id",
                type = "string",
                description = "UUID der Ziel-Collection"
            ),
            McpToolArgument(
                name = "max_edges",
                type = "number",
                description = "Maximale Anzahl Kanten im Subgraphen (Standard: 150)",
                required = false
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): McpToolResult {
        return try {
            val query = GraphRagQuery(
                question = arguments["question"] as String,
                collectionId = UUID.fromString(arguments["collection_id"] as String),
                maxEdges = (arguments["max_edges"] as? Number)?.toInt() ?: 150
            )
            val result = graphRagService.query(query)

            val sourcesText = result.selectedEdges.joinToString("\n") { edge ->
                "- ${edge.quad.subject} -> ${edge.quad.predicate} -> ${edge.quad.`object`} " +
                    "(Relevanz: ${edge.relevanceScore}, Grund: ${edge.reasoning})"
            }

            McpToolResult(
                content = "${result.answer}\n\n--- Quellen ---\n$sourcesText"
            )
        } catch (e: Exception) {
            McpToolResult(content = "Fehler: ${e.message}", isError = true)
        }
    }
}
```

```kotlin
package com.graphmesh.api.mcp.tools

import com.graphmesh.api.mcp.McpTool
import com.graphmesh.api.mcp.McpToolArgument
import com.graphmesh.api.mcp.McpToolDefinition
import com.graphmesh.api.mcp.McpToolResult
import com.graphmesh.query.docrag.DocumentRagQuery
import com.graphmesh.query.docrag.DocumentRagService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * MCP-Tool fuer Dokumentensuche via Document RAG.
 */
@Component
class DocumentQueryTool(
    private val documentRagService: DocumentRagService
) : McpTool {

    override val definition = McpToolDefinition(
        name = "document_query",
        description = "Beantwortet Fragen anhand der Dokumentensammlung. " +
            "Sucht semantisch aehnliche Textabschnitte und generiert " +
            "eine Antwort mit Quellenangaben.",
        arguments = listOf(
            McpToolArgument(
                name = "question",
                type = "string",
                description = "Die natuerlichsprachige Frage"
            ),
            McpToolArgument(
                name = "collection_id",
                type = "string",
                description = "UUID der Ziel-Collection"
            ),
            McpToolArgument(
                name = "top_k",
                type = "number",
                description = "Anzahl der abzurufenden Chunks (Standard: 10)",
                required = false
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): McpToolResult {
        return try {
            val query = DocumentRagQuery(
                question = arguments["question"] as String,
                collectionId = UUID.fromString(arguments["collection_id"] as String),
                topK = (arguments["top_k"] as? Number)?.toInt() ?: 10
            )
            val result = documentRagService.query(query)

            val sourcesText = result.sources.joinToString("\n") { src ->
                "- ${src.documentTitle} (Seite ${src.pageNumber ?: "?"}," +
                    " Score: ${src.score}): ${src.snippet}"
            }

            McpToolResult(
                content = "${result.answer}\n\n--- Quellen ---\n$sourcesText"
            )
        } catch (e: Exception) {
            McpToolResult(content = "Fehler: ${e.message}", isError = true)
        }
    }
}
```

```kotlin
package com.graphmesh.api.mcp.tools

import com.graphmesh.api.mcp.McpTool
import com.graphmesh.api.mcp.McpToolArgument
import com.graphmesh.api.mcp.McpToolDefinition
import com.graphmesh.api.mcp.McpToolResult
import com.graphmesh.collection.CollectionService
import org.springframework.stereotype.Component

/**
 * MCP-Tool zum Auflisten aller Collections.
 */
@Component
class CollectionListTool(
    private val collectionService: CollectionService
) : McpTool {

    override val definition = McpToolDefinition(
        name = "collection_list",
        description = "Listet alle verfuegbaren Collections mit Name, Beschreibung und Tags.",
        arguments = listOf(
            McpToolArgument(
                name = "tags",
                type = "string",
                description = "Komma-separierte Tags zum Filtern (optional)",
                required = false
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): McpToolResult {
        return try {
            val tags = (arguments["tags"] as? String)
                ?.split(",")
                ?.map { it.trim() }
                ?.toSet()
                ?: emptySet()

            val collections = collectionService.listCollections(tags)
            val text = collections.joinToString("\n") { col ->
                "- ${col.name} (ID: ${col.id}): ${col.description} [Tags: ${col.tags.joinToString(", ")}]"
            }

            McpToolResult(content = text.ifEmpty { "Keine Collections gefunden." })
        } catch (e: Exception) {
            McpToolResult(content = "Fehler: ${e.message}", isError = true)
        }
    }
}
```

```kotlin
package com.graphmesh.api.mcp.tools

import com.graphmesh.api.mcp.McpTool
import com.graphmesh.api.mcp.McpToolArgument
import com.graphmesh.api.mcp.McpToolDefinition
import com.graphmesh.api.mcp.McpToolResult
import com.graphmesh.librarian.LibrarianService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * MCP-Tool zur Dokumentensuche innerhalb einer Collection.
 */
@Component
class DocumentSearchTool(
    private val librarianService: LibrarianService
) : McpTool {

    override val definition = McpToolDefinition(
        name = "document_search",
        description = "Sucht Dokumente in einer Collection nach Titel oder Metadaten.",
        arguments = listOf(
            McpToolArgument(
                name = "collection_id",
                type = "string",
                description = "UUID der Ziel-Collection"
            ),
            McpToolArgument(
                name = "title_filter",
                type = "string",
                description = "Suchbegriff fuer den Dokumenttitel (optional)",
                required = false
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): McpToolResult {
        return try {
            val collectionId = UUID.fromString(arguments["collection_id"] as String)
            val documents = librarianService.listDocuments(
                collectionId = collectionId,
                includeChildren = false
            )

            val titleFilter = arguments["title_filter"] as? String
            val filtered = if (titleFilter != null) {
                documents.filter { it.title.contains(titleFilter, ignoreCase = true) }
            } else {
                documents
            }

            val text = filtered.joinToString("\n") { doc ->
                "- ${doc.title} (ID: ${doc.id}, Typ: ${doc.type}, Status: ${doc.state})"
            }

            McpToolResult(content = text.ifEmpty { "Keine Dokumente gefunden." })
        } catch (e: Exception) {
            McpToolResult(content = "Fehler: ${e.message}", isError = true)
        }
    }
}
```

### MCP-Server-Implementierung

```kotlin
package com.graphmesh.api.mcp

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Standard-Implementierung des MCP-Servers.
 * Verwaltet Tool-Registrierung und -Ausfuehrung.
 */
@Service
class DefaultMcpServer(
    tools: List<McpTool>,
    private val authProvider: McpAuthProvider
) : McpServer {

    private val logger = LoggerFactory.getLogger(DefaultMcpServer::class.java)
    private val toolRegistry = mutableMapOf<String, McpTool>()

    init {
        tools.forEach { registerTool(it) }
        logger.info("{} MCP-Tools registriert: {}", toolRegistry.size, toolRegistry.keys)
    }

    override fun registerTool(tool: McpTool) {
        toolRegistry[tool.definition.name] = tool
    }

    override fun listTools(): List<McpToolDefinition> {
        return toolRegistry.values.map { it.definition }
    }

    override suspend fun executeTool(
        toolName: String,
        arguments: Map<String, Any>
    ): McpToolResult {
        val tool = toolRegistry[toolName]
            ?: return McpToolResult(
                content = "Tool '$toolName' nicht gefunden",
                isError = true
            )

        return try {
            tool.validateArguments(arguments)
            tool.execute(arguments)
        } catch (e: IllegalArgumentException) {
            McpToolResult(content = "Validierungsfehler: ${e.message}", isError = true)
        } catch (e: Exception) {
            logger.error("Fehler bei Ausfuehrung von Tool '{}'", toolName, e)
            McpToolResult(content = "Interner Fehler: ${e.message}", isError = true)
        }
    }
}
```

### SSE-Transport-Controller

```kotlin
package com.graphmesh.api.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * REST-Controller fuer den MCP-SSE-Transport.
 * Stellt die MCP-Endpunkte /mcp/sse und /mcp/messages bereit.
 */
@RestController
@RequestMapping("/mcp")
class McpSseController(
    private val mcpServer: McpServer,
    private val authProvider: McpAuthProvider,
    private val objectMapper: ObjectMapper
) {

    /**
     * SSE-Endpunkt fuer den MCP-Client.
     * Der Client verbindet sich hier fuer Echtzeit-Updates.
     */
    @GetMapping("/sse", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun sseEndpoint(
        @RequestHeader("Authorization") authHeader: String?
    ): Flux<ServerSentEvent<String>> {
        return Flux.create { sink ->
            // Sende Endpoint-Event mit der Messages-URL
            sink.next(
                ServerSentEvent.builder<String>()
                    .event("endpoint")
                    .data("/mcp/messages")
                    .build()
            )
        }
    }

    /**
     * Messages-Endpunkt fuer MCP JSON-RPC Requests.
     */
    @PostMapping("/messages")
    suspend fun handleMessage(
        @RequestHeader("Authorization") authHeader: String?,
        @RequestBody body: Map<String, Any>
    ): Map<String, Any> {
        // Bearer-Token validieren
        val token = authHeader?.removePrefix("Bearer ")?.trim()
        if (token == null || !authProvider.validateToken(token)) {
            return mapOf(
                "jsonrpc" to "2.0",
                "error" to mapOf("code" to -32000, "message" to "Nicht autorisiert"),
                "id" to (body["id"] ?: 0)
            )
        }

        val method = body["method"] as? String ?: ""
        val params = body["params"] as? Map<String, Any> ?: emptyMap()
        val id = body["id"]

        return when (method) {
            "initialize" -> mapOf(
                "jsonrpc" to "2.0",
                "result" to mapOf(
                    "protocolVersion" to "2024-11-05",
                    "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
                    "serverInfo" to mapOf("name" to "graphmesh", "version" to "1.0.0")
                ),
                "id" to (id ?: 0)
            )

            "tools/list" -> mapOf(
                "jsonrpc" to "2.0",
                "result" to mapOf("tools" to mcpServer.listTools().map { it.toJsonSchema() }),
                "id" to (id ?: 0)
            )

            "tools/call" -> {
                val toolName = params["name"] as? String ?: ""
                val args = params["arguments"] as? Map<String, Any> ?: emptyMap()
                val result = mcpServer.executeTool(toolName, args)

                mapOf(
                    "jsonrpc" to "2.0",
                    "result" to mapOf(
                        "content" to listOf(
                            mapOf("type" to "text", "text" to result.content)
                        ),
                        "isError" to result.isError
                    ),
                    "id" to (id ?: 0)
                )
            }

            else -> mapOf(
                "jsonrpc" to "2.0",
                "error" to mapOf("code" to -32601, "message" to "Methode nicht gefunden: $method"),
                "id" to (id ?: 0)
            )
        }
    }
}

/**
 * Konvertiert eine McpToolDefinition in das MCP JSON-Schema-Format.
 */
private fun McpToolDefinition.toJsonSchema(): Map<String, Any> {
    val properties = arguments.associate { arg ->
        arg.name to mapOf(
            "type" to arg.type,
            "description" to arg.description
        )
    }
    val required = arguments.filter { it.required }.map { it.name }

    return mapOf(
        "name" to name,
        "description" to description,
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required
        )
    )
}
```

### Bearer-Token-Authentifizierung

```kotlin
package com.graphmesh.api.mcp

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Einfache Bearer-Token-Validierung.
 * Fuer Single-Tenant-Deployments: ein statischer Token,
 * der ueber Konfiguration gesetzt wird.
 *
 * WICHTIG: Nicht geeignet fuer Multi-Tenant- oder Multi-User-Szenarien.
 * Siehe mcp-tool-bearer-token.md fuer Details zu Einschraenkungen.
 */
@Component
class StaticMcpAuthProvider(
    @Value("\${graphmesh.mcp.auth-token:}") private val configuredToken: String
) : McpAuthProvider {

    override suspend fun validateToken(token: String): Boolean {
        if (configuredToken.isBlank()) {
            // Kein Token konfiguriert -- Auth deaktiviert
            return true
        }
        return token == configuredToken
    }
}
```

## Betroffene Dateien

### Backend

| Datei                                                                   | Aenderung                                |
|-------------------------------------------------------------------------|------------------------------------------|
| `api/src/main/kotlin/com/graphmesh/api/mcp/McpToolDefinition.kt`        | NEU - Tool-Definition Datenmodell        |
| `api/src/main/kotlin/com/graphmesh/api/mcp/McpToolArgument.kt`          | NEU - Argument-Datenmodell               |
| `api/src/main/kotlin/com/graphmesh/api/mcp/McpToolResult.kt`            | NEU - Ergebnis-Datenmodell               |
| `api/src/main/kotlin/com/graphmesh/api/mcp/McpServer.kt`                | NEU - Server-Interface                   |
| `api/src/main/kotlin/com/graphmesh/api/mcp/McpTool.kt`                  | NEU - Tool-Interface                     |
| `api/src/main/kotlin/com/graphmesh/api/mcp/McpAuthProvider.kt`          | NEU - Auth-Interface                     |
| `api/src/main/kotlin/com/graphmesh/api/mcp/DefaultMcpServer.kt`         | NEU - Server-Implementierung             |
| `api/src/main/kotlin/com/graphmesh/api/mcp/StaticMcpAuthProvider.kt`    | NEU - Token-Validierung                  |
| `api/src/main/kotlin/com/graphmesh/api/mcp/McpSseController.kt`         | NEU - SSE-Transport-Controller           |
| `api/src/main/kotlin/com/graphmesh/api/mcp/tools/KnowledgeQueryTool.kt` | NEU - Graph-RAG-Tool                     |
| `api/src/main/kotlin/com/graphmesh/api/mcp/tools/DocumentQueryTool.kt`  | NEU - Document-RAG-Tool                  |
| `api/src/main/kotlin/com/graphmesh/api/mcp/tools/CollectionListTool.kt` | NEU - Collection-List-Tool               |
| `api/src/main/kotlin/com/graphmesh/api/mcp/tools/DocumentSearchTool.kt` | NEU - Document-Search-Tool               |
| `api/src/main/resources/application.yml`                                | AENDERUNG - MCP-Auth-Token Konfiguration |
| `api/build.gradle.kts`                                                  | AENDERUNG - MCP SDK Abhaengigkeit        |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                       | Aenderung                                            |
|-----------------------------------------------------------------------------|------------------------------------------------------|
| `api/src/test/kotlin/com/graphmesh/api/mcp/DefaultMcpServerTest.kt`         | NEU - Tests fuer Tool-Registrierung und -Ausfuehrung |
| `api/src/test/kotlin/com/graphmesh/api/mcp/McpSseControllerTest.kt`         | NEU - Tests fuer SSE-Transport und JSON-RPC          |
| `api/src/test/kotlin/com/graphmesh/api/mcp/StaticMcpAuthProviderTest.kt`    | NEU - Tests fuer Token-Validierung                   |
| `api/src/test/kotlin/com/graphmesh/api/mcp/tools/KnowledgeQueryToolTest.kt` | NEU - Tests fuer Graph-RAG-Tool                      |
| `api/src/test/kotlin/com/graphmesh/api/mcp/tools/DocumentQueryToolTest.kt`  | NEU - Tests fuer Document-RAG-Tool                   |
| `api/src/test/kotlin/com/graphmesh/api/mcp/tools/CollectionListToolTest.kt` | NEU - Tests fuer Collection-List-Tool                |
| `api/src/test/kotlin/com/graphmesh/api/mcp/tools/DocumentSearchToolTest.kt` | NEU - Tests fuer Document-Search-Tool                |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                            |
|-------------------|-------------|--------------------------------------------------|
| Spring Boot (JVM) | Ja          | Spring WebFlux fuer SSE + MCP SDK                |
| KMP Library       | Nein        | Abhaengigkeit von Spring WebFlux und HTTP-Server |
| Ktor/Wasm         | Nein        | Spring-spezifische SSE-Implementierung           |

## Akzeptanzkriterien

- [ ] MCP-Server antwortet auf `initialize`-Request mit Protokollversion und Capabilities
- [ ] `tools/list` gibt alle vier Tools (knowledge_query, document_query, collection_list, document_search) zurueck
- [ ] Jedes Tool hat typisierte Argumente mit Name, Typ und Beschreibung im JSON-Schema-Format
- [ ] `tools/call` fuehrt das Tool mit validierten Argumenten aus und gibt Ergebnis zurueck
- [ ] Fehlende Pflichtargumente werden mit einem Validierungsfehler abgelehnt
- [ ] Bearer-Token-Authentifizierung blockiert unautorisierte Requests mit Fehlercode -32000
- [ ] Bei leerem konfiguriertem Token ist die Authentifizierung deaktiviert (Entwicklungsmodus)
- [ ] SSE-Endpunkt unter `/mcp/sse` sendet Endpoint-Event mit Messages-URL
- [ ] Messages-Endpunkt unter `/mcp/messages` verarbeitet JSON-RPC-Requests
- [ ] Unbekannte Methoden werden mit Fehlercode -32601 beantwortet
- [ ] Tool-Ergebnisse enthalten `isError`-Flag bei fehlgeschlagenen Ausfuehrungen
