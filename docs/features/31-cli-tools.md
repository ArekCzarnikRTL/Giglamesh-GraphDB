# Feature 31: CLI Tools

## Problem

GraphMesh kann nur ueber die GraphQL-API und die Web-UI bedient werden. Fuer Automatisierung, Scripting, CI/CD-Pipelines
und Debugging fehlt ein Kommandozeilen-Interface. Administratoren und Entwickler benoetigen ein CLI, um Collections zu
verwalten, Dokumente hochzuladen, Queries auszufuehren, Konfigurationen zu aendern und Provenance-Ketten zu
inspizieren -- ohne einen Browser oeffnen zu muessen.

## Ziel

Implementierung eines Kotlin-basierten CLI mit Clikt-Framework, das alle wesentlichen GraphMesh-Operationen ueber die
Kommandozeile zugaenglich macht und sowohl JSON- als auch Tabellenausgabe unterstuetzt.

1. **Collection-Management** -- create, list, delete von Collections
2. **Document-Management** -- upload, list, info von Dokumenten
3. **Query-Ausfuehrung** -- graphrag, docrag und nlp Queries
4. **Konfiguration** -- get, set, list von Konfigurationswerten
5. **Explainability** -- Provenance-Ketten inspizieren und Drill-Down
6. **Ausgabeformate** -- JSON und Tabelle (human-readable)
7. **GraphQL-Backend** -- Verbindung ueber GraphQL-Endpunkt

## Voraussetzungen

| Abhaengigkeit                                                  | Status     | Blocker? |
|----------------------------------------------------------------|------------|----------|
| Feature 08: Collection Management (CollectionService)          | Geplant    | Ja       |
| Feature 09: Document Management / Librarian (LibrarianService) | Geplant    | Ja       |
| Feature 14: GraphQL API (Endpunkt fuer alle Operationen)       | Geplant    | Ja       |
| Feature 06: Configuration Service (ConfigService)              | Geplant    | Ja       |
| Clikt (Kotlin CLI Framework)                                   | Verfuegbar | Nein     |
| OkHttp / Ktor Client                                           | Verfuegbar | Nein     |

## Architektur

### CLI-Struktur

```kotlin
package com.graphmesh.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

/**
 * Haupteinstiegspunkt fuer das GraphMesh CLI.
 * Registriert alle Subcommands.
 */
class GraphMeshCli : CliktCommand(
    name = "graphmesh",
    help = "GraphMesh CLI -- Knowledge Graph Management"
) {
    override fun run() = Unit
}

fun main(args: Array<String>) = GraphMeshCli()
    .subcommands(
        CollectionCommand(),
        DocumentCommand(),
        QueryCommand(),
        ConfigCommand(),
        ExplainCommand()
    )
    .main(args)
```

### Globale Optionen

```kotlin
package com.graphmesh.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum

/**
 * Basis-Klasse fuer alle Commands mit gemeinsamen Optionen.
 */
abstract class BaseCommand(name: String, help: String) : CliktCommand(name = name, help = help) {

    /** GraphMesh GraphQL-Endpunkt. */
    val endpoint by option("--endpoint", "-e", help = "GraphQL endpoint URL")
        .default(System.getenv("GRAPHMESH_ENDPOINT") ?: "http://localhost:8080/graphql")

    /** Authentifizierungs-Token. */
    val token by option("--token", "-t", help = "Bearer token for authentication")
        .default(System.getenv("GRAPHMESH_TOKEN") ?: "")

    /** Ausgabeformat. */
    val format by option("--format", "-f", help = "Output format")
        .enum<OutputFormat>()
        .default(OutputFormat.TABLE)
}

enum class OutputFormat { TABLE, JSON }
```

### CollectionCommand

```kotlin
package com.graphmesh.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option

/**
 * Verwaltet Collections.
 *
 * Beispiele:
 *   graphmesh collection list
 *   graphmesh collection create "Meine Sammlung" --description "Beschreibung"
 *   graphmesh collection delete <collection-id>
 */
class CollectionCommand : BaseCommand("collection", "Manage collections") {
    override fun run() = Unit

    init {
        subcommands(
            CollectionList(),
            CollectionCreate(),
            CollectionDelete()
        )
    }
}

class CollectionList : BaseCommand("list", "List all collections") {
    override fun run() {
        val client = GraphQlClient(endpoint, token)
        val collections = client.query("""
            query { collections { id name description documentCount createdAt } }
        """)
        when (format) {
            OutputFormat.TABLE -> printTable(
                headers = listOf("ID", "Name", "Docs", "Created"),
                rows = collections.map { listOf(it.id, it.name, it.docCount, it.createdAt) }
            )
            OutputFormat.JSON -> echo(collections.toJson())
        }
    }
}

class CollectionCreate : BaseCommand("create", "Create a new collection") {
    val name by argument(help = "Collection name")
    val description by option("--description", "-d", help = "Collection description")

    override fun run() {
        val client = GraphQlClient(endpoint, token)
        val result = client.mutate("""
            mutation { createCollection(name: "$name", description: "${description ?: ""}") { id name } }
        """)
        echo("Collection created: ${result.id}")
    }
}

class CollectionDelete : BaseCommand("delete", "Delete a collection") {
    val id by argument(help = "Collection ID")

    override fun run() {
        val client = GraphQlClient(endpoint, token)
        client.mutate("""mutation { deleteCollection(id: "$id") }""")
        echo("Collection deleted: $id")
    }
}
```

### DocumentCommand

```kotlin
package com.graphmesh.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file

/**
 * Verwaltet Dokumente.
 *
 * Beispiele:
 *   graphmesh document upload --collection <id> --file ./paper.pdf
 *   graphmesh document list --collection <id>
 *   graphmesh document info <document-id>
 */
class DocumentCommand : BaseCommand("document", "Manage documents") {
    override fun run() = Unit

    init {
        subcommands(DocumentUpload(), DocumentList(), DocumentInfo())
    }
}

class DocumentUpload : BaseCommand("upload", "Upload a document") {
    val collectionId by option("--collection", "-c", help = "Target collection ID").required()
    val file by option("--file", help = "File to upload").file(mustExist = true).required()
    val title by option("--title", help = "Document title (defaults to filename)")

    override fun run() {
        val client = GraphQlClient(endpoint, token)
        val docTitle = title ?: file.name
        val result = client.uploadDocument(collectionId, file, docTitle)
        echo("Document uploaded: ${result.id} (${file.length()} bytes)")
    }
}

class DocumentList : BaseCommand("list", "List documents in a collection") {
    val collectionId by option("--collection", "-c", help = "Collection ID").required()

    override fun run() {
        val client = GraphQlClient(endpoint, token)
        val docs = client.query("""
            query { documents(collectionId: "$collectionId") { id title mimeType size createdAt } }
        """)
        when (format) {
            OutputFormat.TABLE -> printTable(
                headers = listOf("ID", "Title", "Type", "Size", "Created"),
                rows = docs.map { listOf(it.id, it.title, it.mimeType, it.size, it.createdAt) }
            )
            OutputFormat.JSON -> echo(docs.toJson())
        }
    }
}

class DocumentInfo : BaseCommand("info", "Show document details") {
    val id by argument(help = "Document ID")

    override fun run() {
        val client = GraphQlClient(endpoint, token)
        val doc = client.query("""
            query { document(id: "$id") { id title mimeType size pageCount chunkCount createdAt } }
        """)
        echo(doc.toFormattedString())
    }
}
```

### QueryCommand

```kotlin
package com.graphmesh.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required

/**
 * Fuehrt Queries aus.
 *
 * Beispiele:
 *   graphmesh query graphrag --collection <id> "Was ist GraphMesh?"
 *   graphmesh query docrag --collection <id> "Zusammenfassung der Dokumente"
 *   graphmesh query nlp --collection <id> "Welche Entitaeten gibt es?"
 */
class QueryCommand : BaseCommand("query", "Execute queries") {
    override fun run() = Unit

    init {
        subcommands(QueryGraphRag(), QueryDocRag(), QueryNlp())
    }
}

class QueryGraphRag : BaseCommand("graphrag", "Execute a GraphRAG query") {
    val question by argument(help = "The question to ask")
    val collectionId by option("--collection", "-c", help = "Collection ID").required()
    val explainable by option("--explain", help = "Enable explainability recording")

    override fun run() {
        val client = GraphQlClient(endpoint, token)
        val result = client.query("""
            query {
                graphRagQuery(
                    question: "$question"
                    collectionId: "$collectionId"
                ) { answer selectedEdgeCount retrievedEdgeCount durationMs }
            }
        """)
        echo(result.answer)
        if (format == OutputFormat.JSON) {
            echo(result.toJson())
        }
    }
}

class QueryDocRag : BaseCommand("docrag", "Execute a Document RAG query") {
    val question by argument(help = "The question to ask")
    val collectionId by option("--collection", "-c", help = "Collection ID").required()

    override fun run() {
        val client = GraphQlClient(endpoint, token)
        val result = client.query("""
            query {
                docRagQuery(question: "$question", collectionId: "$collectionId") { answer durationMs }
            }
        """)
        echo(result.answer)
    }
}

class QueryNlp : BaseCommand("nlp", "Execute an NLP query") {
    val question by argument(help = "The question to ask")
    val collectionId by option("--collection", "-c", help = "Collection ID").required()

    override fun run() {
        val client = GraphQlClient(endpoint, token)
        val result = client.query("""
            query {
                nlpQuery(question: "$question", collectionId: "$collectionId") { answer triples { s p o } }
            }
        """)
        echo(result.answer)
    }
}
```

### ConfigCommand

```kotlin
package com.graphmesh.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option

/**
 * Verwaltet Konfigurationen.
 *
 * Beispiele:
 *   graphmesh config list
 *   graphmesh config get llm.model
 *   graphmesh config set llm.model "gpt-4"
 */
class ConfigCommand : BaseCommand("config", "Manage configuration") {
    override fun run() = Unit

    init {
        subcommands(ConfigList(), ConfigGet(), ConfigSet())
    }
}

class ConfigList : BaseCommand("list", "List configuration keys") {
    val type by option("--type", help = "Filter by config type")

    override fun run() {
        val client = GraphQlClient(endpoint, token)
        val configs = client.query("""
            query { configKeys${type?.let { "(type: \"$it\")" } ?: ""} { key type value } }
        """)
        when (format) {
            OutputFormat.TABLE -> printTable(
                headers = listOf("Key", "Type", "Value"),
                rows = configs.map { listOf(it.key, it.type, it.value) }
            )
            OutputFormat.JSON -> echo(configs.toJson())
        }
    }
}

class ConfigGet : BaseCommand("get", "Get a configuration value") {
    val key by argument(help = "Configuration key")

    override fun run() {
        val client = GraphQlClient(endpoint, token)
        val value = client.query("""query { configValue(key: "$key") { value } }""")
        echo(value)
    }
}

class ConfigSet : BaseCommand("set", "Set a configuration value") {
    val key by argument(help = "Configuration key")
    val value by argument(help = "Configuration value")

    override fun run() {
        val client = GraphQlClient(endpoint, token)
        client.mutate("""mutation { setConfig(key: "$key", value: "$value") { key value } }""")
        echo("Set $key = $value")
    }
}
```

### ExplainCommand

```kotlin
package com.graphmesh.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.types.int

/**
 * Inspiziert Provenance-Ketten und Explainability-Daten.
 *
 * Beispiele:
 *   graphmesh explain sessions --limit 20
 *   graphmesh explain trace <session-uri>
 *   graphmesh explain document <document-uri>
 */
class ExplainCommand : BaseCommand("explain", "Inspect provenance and explainability") {
    override fun run() = Unit

    init {
        subcommands(ExplainSessions(), ExplainTrace(), ExplainDocument())
    }
}

class ExplainSessions : BaseCommand("sessions", "List explainability sessions") {
    val limit by option("--limit", help = "Maximum number of sessions").int().default(50)
    val mechanism by option("--mechanism", "-m", help = "Filter: GRAPH_RAG, DOC_RAG, AGENT")

    override fun run() {
        val client = GraphQlClient(endpoint, token)
        val sessions = client.query("""
            query {
                explanationSessions(limit: $limit${mechanism?.let { ", mechanism: $it" } ?: ""}) {
                    uri queryText timestamp mechanism
                }
            }
        """)
        when (format) {
            OutputFormat.TABLE -> printTable(
                headers = listOf("Session URI", "Question", "Time", "Type"),
                rows = sessions.map { listOf(it.uri, it.queryText, it.timestamp, it.mechanism) }
            )
            OutputFormat.JSON -> echo(sessions.toJson())
        }
    }
}

class ExplainTrace : BaseCommand("trace", "Show full explainability trace for a session") {
    val sessionUri by argument(help = "Session URI (e.g. urn:graphmesh:question:...)")
    val showProvenance by option("--provenance", "-p", help = "Trace edges to source documents")
    val maxAnswer by option("--max-answer", help = "Max characters for answer").int().default(500)

    override fun run() {
        val client = GraphQlClient(endpoint, token)
        val chain = client.query("""
            query {
                explanationChain(sessionUri: "$sessionUri") {
                    question { queryText timestamp mechanism }
                    exploration { edgeCount }
                    focus { selectedEdges { subject predicate object reasoning } }
                    analyses { thought action arguments observation }
                    conclusion { answer }
                    mechanism
                }
            }
        """)

        when (format) {
            OutputFormat.TABLE -> {
                echo("=== Session: $sessionUri ===\n")
                echo("Question: ${chain.question.queryText}")
                echo("Time: ${chain.question.timestamp}")
                echo("Type: ${chain.mechanism}\n")

                chain.exploration?.let {
                    echo("--- Exploration ---")
                    echo("Retrieved ${it.edgeCount} edges\n")
                }
                chain.focus?.let { focus ->
                    echo("--- Focus (Edge Selection) ---")
                    echo("Selected ${focus.selectedEdges.size} edges:")
                    focus.selectedEdges.forEachIndexed { i, edge ->
                        echo("  ${i + 1}. (${edge.subject}, ${edge.predicate}, ${edge.object})")
                        echo("     Reasoning: ${edge.reasoning}")
                    }
                    echo()
                }
                chain.analyses?.forEachIndexed { i, analysis ->
                    echo("--- Analysis ${i + 1} ---")
                    echo("Thought: ${analysis.thought}")
                    analysis.action?.let { echo("Action: $it") }
                    analysis.observation?.let { echo("Observation: $it") }
                    echo()
                }
                chain.conclusion?.let {
                    echo("--- Conclusion ---")
                    echo(it.answer.take(maxAnswer))
                    if (it.answer.length > maxAnswer) echo("[truncated]")
                }
            }
            OutputFormat.JSON -> echo(chain.toJson())
        }
    }
}

class ExplainDocument : BaseCommand("document", "Show document provenance hierarchy") {
    val documentUri by argument(help = "Document URI")

    override fun run() {
        val client = GraphQlClient(endpoint, token)
        val hierarchy = client.query("""
            query {
                documentHierarchy(documentUri: "$documentUri") {
                    uri title type children { uri title type children { uri title type } }
                }
            }
        """)
        when (format) {
            OutputFormat.TABLE -> printTree(hierarchy)
            OutputFormat.JSON -> echo(hierarchy.toJson())
        }
    }
}
```

### GraphQL Client

```kotlin
package com.graphmesh.cli

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Einfacher GraphQL-Client fuer CLI-Operationen.
 * Verbindet sich zum GraphMesh GraphQL-Endpunkt.
 */
class GraphQlClient(
    private val endpoint: String,
    private val token: String
) {
    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json".toMediaType()

    fun query(graphql: String): Any {
        val body = """{"query": ${graphql.trimIndent().replace("\"", "\\\"")}}"""
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody(jsonMediaType))
            .apply {
                if (token.isNotBlank()) {
                    addHeader("Authorization", "Bearer $token")
                }
            }
            .build()

        val response = httpClient.newCall(request).execute()
        return parseGraphQlResponse(response.body?.string() ?: "")
    }
}
```

## Betroffene Dateien

### Backend

| Datei                                                        | Aenderung                        |
|--------------------------------------------------------------|----------------------------------|
| `cli/src/main/kotlin/com/graphmesh/cli/GraphMeshCli.kt`      | Haupteinstiegspunkt              |
| `cli/src/main/kotlin/com/graphmesh/cli/BaseCommand.kt`       | Gemeinsame Optionen              |
| `cli/src/main/kotlin/com/graphmesh/cli/CollectionCommand.kt` | Collection-Verwaltung            |
| `cli/src/main/kotlin/com/graphmesh/cli/DocumentCommand.kt`   | Dokument-Verwaltung              |
| `cli/src/main/kotlin/com/graphmesh/cli/QueryCommand.kt`      | Query-Ausfuehrung                |
| `cli/src/main/kotlin/com/graphmesh/cli/ConfigCommand.kt`     | Konfigurations-Verwaltung        |
| `cli/src/main/kotlin/com/graphmesh/cli/ExplainCommand.kt`    | Explainability-Inspektion        |
| `cli/src/main/kotlin/com/graphmesh/cli/GraphQlClient.kt`     | GraphQL-Client                   |
| `cli/src/main/kotlin/com/graphmesh/cli/OutputFormatter.kt`   | Tabellen- und JSON-Formatierung  |
| `cli/build.gradle.kts`                                       | Neues Modul mit Clikt-Dependency |
| `settings.gradle.kts`                                        | Modul `cli` registrieren         |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                            | Aenderung                      |
|------------------------------------------------------------------|--------------------------------|
| `cli/src/test/kotlin/com/graphmesh/cli/CollectionCommandTest.kt` | Tests fuer Collection-Commands |
| `cli/src/test/kotlin/com/graphmesh/cli/DocumentCommandTest.kt`   | Tests fuer Document-Commands   |
| `cli/src/test/kotlin/com/graphmesh/cli/QueryCommandTest.kt`      | Tests fuer Query-Commands      |
| `cli/src/test/kotlin/com/graphmesh/cli/ConfigCommandTest.kt`     | Tests fuer Config-Commands     |
| `cli/src/test/kotlin/com/graphmesh/cli/ExplainCommandTest.kt`    | Tests fuer Explain-Commands    |
| `cli/src/test/kotlin/com/graphmesh/cli/GraphQlClientTest.kt`     | Tests fuer GraphQL-Client      |
| `cli/src/test/kotlin/com/graphmesh/cli/OutputFormatterTest.kt`   | Tests fuer Ausgabeformatierung |

## Platform-Einschraenkungen

| Plattform         | Unterstuetzt | Anmerkung                                     |
|-------------------|--------------|-----------------------------------------------|
| Spring Boot (JVM) | Ja           | CLI laeuft als eigenstaendige JVM-Applikation |
| KMP Library       | Nein         | CLI ist JVM-only (OkHttp, Clikt)              |
| Ktor/Wasm         | Nein         | Kein CLI im Browser                           |

## Akzeptanzkriterien

- [ ] `graphmesh collection list` zeigt alle Collections als Tabelle an
- [ ] `graphmesh collection create "Test" --description "Desc"` erstellt eine neue Collection
- [ ] `graphmesh collection delete <id>` loescht eine Collection
- [ ] `graphmesh document upload --collection <id> --file ./test.pdf` laedt ein Dokument hoch
- [ ] `graphmesh document list --collection <id>` listet Dokumente mit Metadaten
- [ ] `graphmesh query graphrag --collection <id> "Frage"` gibt eine natuerlichsprachige Antwort aus
- [ ] `graphmesh config list` zeigt alle Konfigurationsschluessel
- [ ] `graphmesh config set <key> <value>` setzt einen Konfigurationswert
- [ ] `graphmesh explain sessions` listet alle Explainability-Sessions
- [ ] `graphmesh explain trace <uri>` zeigt die vollstaendige Erklaerungskette
- [ ] Alle Commands unterstuetzen `--format json` fuer maschinenlesbare Ausgabe
- [ ] Endpunkt und Token koennen via Umgebungsvariablen (`GRAPHMESH_ENDPOINT`, `GRAPHMESH_TOKEN`) gesetzt werden
- [ ] Fehlermeldungen bei ungueltigem Endpunkt oder fehlender Authentifizierung sind verstaendlich
