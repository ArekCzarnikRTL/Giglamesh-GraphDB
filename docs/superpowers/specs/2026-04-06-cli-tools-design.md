# Feature 31: CLI Tools — Design

**Status:** Draft
**Datum:** 2026-04-06
**Feature-Doc:** `docs/features/31-cli-tools.md`

## Ziel

Implementierung eines Kotlin-basierten Kommandozeilen-Interfaces für GraphMesh auf Basis des Clikt-Frameworks. Das CLI stellt alle wesentlichen GraphMesh-Operationen über die Shell bereit und verbindet sich über den bestehenden GraphQL-Endpunkt mit dem Backend. Unterstützt werden sowohl menschenlesbare Tabellen-Ausgabe als auch maschinenlesbares JSON.

## Scope

Volle Feature-Doc-Abdeckung (Option B im Brainstorming): Collection-, Document-, Query-, Config- und Explain-Commands. Da die GraphQL-API für Config und Document-Hierarchie noch nicht existiert, werden die entsprechenden Backend-Endpunkte im Rahmen dieses Features ergänzt.

## Abweichungen vom Feature-Dokument

| # | Doc sagt | Stattdessen |
|---|---|---|
| 1 | Paket `com.graphmesh.cli` | `com.agentwork.graphmesh.cli` (Projekt-Konvention) |
| 2 | Separates `cli/`-Gradle-Submodul | CLI lebt im selben Single-Module-Build unter `src/main/kotlin/com/agentwork/graphmesh/cli/` (User-Präferenz: keine Submodule) |
| 3 | OkHttp-HTTP-Client | graphql-kotlin-ktor-client (typisiert, mit Codegen) auf Ktor-CIO-Engine |
| 4 | Naive String-Interpolation der GraphQL-Queries | GraphQL-Variables über typisierte, codegenerierte Query-Klassen |
| 5 | `createCollection(name, description)` als lose Args | `createCollection(input: CreateCollectionInput!)` mit Tags + Metadata |
| 6 | `uploadDocument` als Multipart-Upload | `uploadDocument(input: UploadDocumentInput!)` mit Base64-Content im String-Feld (echtes Schema) |
| 7 | `explanationSessions` ohne collectionId | `explanationSessions(collectionId: ID!, ...)` — `-c` wird Pflicht-Option |
| 8 | `graphRagQuery(question: "...", ...)` | `graphRag(input: GraphRagInput!)` |
| 9 | Feld `object` im SelectedEdge | `objectValue` (Kotlin-Reserviertes-Wort-Workaround im Schema) |
| 10 | Config-Commands gegen nicht-existierende API | Neues `ConfigGraphQlController` + `config.graphqls` wird als Teil dieses Features gebaut |
| 11 | `explain document` gegen nicht-existierende `documentHierarchy` | Neue Query in `explainability.graphqls` + `DocumentHierarchyController` wird gebaut |

## Architektur

### Build-Integration

Das CLI lebt vollständig im bestehenden Single-Module-Gradle-Projekt. Es benötigt Spring zur Laufzeit nicht — die `main`-Funktion startet eine reine Clikt-Applikation ohne Spring-Kontext. Die Spring-Boot-Dependencies bleiben unberührt.

**Neue Gradle-Tasks in `build.gradle.kts`:**

| Task | Typ | Zweck |
|---|---|---|
| `assembleCliSchema` | Custom | Konkateniert `src/main/resources/graphql/*.graphqls` → `build/generated/cli-schema/schema.graphqls` für die Codegen |
| `graphqlGenerateClient` | Plugin | Generiert Kotlin-Query-Klassen aus `.graphql`-Dateien + konsolidiertem Schema; dependsOn `assembleCliSchema` |
| `cliRun` | JavaExec | Lokaler Entwicklungs-Run: `./gradlew cliRun --args="collection list"` |
| `cliJar` | Jar | Fat-Jar `build/libs/graphmesh-cli.jar` mit `Main-Class`-Manifest und allen CLI-Dependencies |
| `cliInstall` | Copy (+ doLast) | Erzeugt Distributions-Layout `build/cli/bin/graphmesh` (Shell-Script) + `build/cli/bin/graphmesh.bat` + `build/cli/lib/graphmesh-cli.jar` |

Die Tasks `cliRun` und `cliJar` hängen transitiv von `graphqlGenerateClient` ab, sodass das Schema immer aktuell generiert wird. `cliInstall` hängt von `cliJar` ab.

**Neue Dependencies:**

```kotlin
implementation("com.github.ajalt.clikt:clikt:5.0.1")
implementation("com.expediagroup:graphql-kotlin-ktor-client:8.2.1")
implementation("io.ktor:ktor-client-cio:3.0.1")
```

*(Exakte Versionen zum Zeitpunkt der Implementierung aktualisieren.)*

**Neues Gradle-Plugin:**

```kotlin
plugins {
    id("com.expediagroup.graphql") version "8.2.1"
}

graphql {
    client {
        packageName = "com.agentwork.graphmesh.cli.generated"
        serializer = com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer.JACKSON
        queryFileDirectory = "${projectDir}/src/main/kotlin/com/agentwork/graphmesh/cli/queries"
    }
}

tasks.named<com.expediagroup.graphql.plugin.gradle.tasks.GraphQLGenerateClientTask>("graphqlGenerateClient") {
    dependsOn(assembleCliSchema)
    schemaFile.set(layout.buildDirectory.file("generated/cli-schema/schema.graphqls"))
}
```

### Command-Baum

```
graphmesh
├── collection
│   ├── list
│   ├── create <name> [-d <desc>] [--tag <t>]*
│   └── delete <id>
├── document
│   ├── upload -c <collectionId> --file <path> [--title <t>] [--mime <m>]
│   ├── list   -c <collectionId> [--type SOURCE|PAGE|CHUNK]
│   └── info   <documentId>
├── query
│   ├── graphrag -c <collectionId> [--max-edges N] [--max-depth N] [--max-selected N] <question>
│   ├── docrag   -c <collectionId> [--top-k N] [--threshold F] <question>
│   └── nlp      -c <collectionId> [--force-intent GRAPH_QUERY|DOCUMENT_QUERY|STRUCTURED_QUERY|HYBRID] <question>
├── config
│   ├── list              [--type <filter>]
│   ├── get  <key>
│   └── set  <key> <value> [--type STRING|INT|BOOLEAN|...]
└── explain
    ├── sessions -c <collectionId> [--limit N] [--mechanism GRAPH_RAG|DOC_RAG|AGENT]
    ├── trace    -c <collectionId> <sessionUri> [--max-answer N]
    └── document -c <collectionId> <documentUri>
```

### Globale Optionen (Root-Command)

| Option | Default | Env-Variable | Typ |
|---|---|---|---|
| `--endpoint, -e` | `http://localhost:8080/graphql` | `GRAPHMESH_ENDPOINT` | String (URL) |
| `--token, -t` | leer | `GRAPHMESH_TOKEN` | String |
| `--format, -f` | `TABLE` | — | Enum `TABLE` \| `JSON` |

Der Root-Command setzt `currentContext.obj = CliConfig(endpoint, token, format)`; alle Child-Commands lesen das via `val cfg by requireObject<CliConfig>()`.

### Datei-Layout

```
src/main/kotlin/com/agentwork/graphmesh/cli/
├── GraphMeshCli.kt              # RootCommand + main()
├── CliConfig.kt                 # data class CliConfig + enum OutputFormat
├── BaseCommand.kt               # abstract GraphMeshCommand : SuspendingCliktCommand
├── GraphQlGateway.kt            # Thin wrapper um GraphQLKtorClient (injizierbar für Tests)
├── commands/
│   ├── CollectionCommands.kt
│   ├── DocumentCommands.kt
│   ├── QueryCommands.kt
│   ├── ConfigCommands.kt
│   └── ExplainCommands.kt
├── output/
│   ├── Output.kt                # Interface
│   ├── TableOutput.kt
│   ├── JsonOutput.kt
│   └── Views.kt                 # data classes *View (entkoppelt Formatter von generierten Typen)
└── queries/                     # GraphQL-Operationen als .graphql-Dateien (Input für Codegen)
    ├── ListCollections.graphql
    ├── CreateCollection.graphql
    ├── DeleteCollection.graphql
    ├── UploadDocument.graphql
    ├── ListDocuments.graphql
    ├── GetDocument.graphql
    ├── GraphRagQuery.graphql
    ├── DocRagQuery.graphql
    ├── NlpQuery.graphql
    ├── ListConfigKeys.graphql
    ├── GetConfigValue.graphql
    ├── SetConfigValue.graphql
    ├── ListExplanationSessions.graphql
    ├── GetExplanationChain.graphql
    └── GetDocumentHierarchy.graphql
```

### GraphQL-Layer

**Schema-Aufbereitung:** Eine `assembleCliSchema`-Task konkateniert alle `src/main/resources/graphql/*.graphqls` in der Reihenfolge `schema.graphqls` zuerst (Basis-Typ), danach die `extend type`-Dateien. Das konsolidierte SDL landet unter `build/generated/cli-schema/schema.graphqls` und dient als Input für `graphqlGenerateClient`.

**Query-Dateien:** Pro CLI-Operation existiert eine `.graphql`-Datei mit der jeweiligen GraphQL-Operation inklusive Variables. Beispiel `GraphRagQuery.graphql`:

```graphql
query GraphRagQuery($input: GraphRagInput!) {
  graphRag(input: $input) {
    sessionId
    answer
    selectedEdges { subject predicate objectValue reasoning relevanceScore }
    retrievedEdgeCount
    durationMs
  }
}
```

Der Plugin generiert daraus im Paket `com.agentwork.graphmesh.cli.generated` eine `GraphRagQuery`-Klasse, Jackson-kompatible Input- und Output-Types.

**Gateway-Wrapper:** Die `GraphQlGateway`-Klasse kapselt den `GraphQLKtorClient` und stellt pro CLI-Operation eine suspend-Methode zur Verfügung. Bearer-Token-Injection passiert per Request-Customizer:

```kotlin
class GraphQlGateway(private val cfg: CliConfig) {
    private val client = GraphQLKtorClient(
        url = URL(cfg.endpoint),
        httpClient = HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 60_000 }
        }
    )

    suspend fun <T : Any> execute(request: GraphQLClientRequest<T>): T {
        val response = client.execute(request) {
            if (cfg.token.isNotBlank()) header("Authorization", "Bearer ${cfg.token}")
        }
        if (!response.errors.isNullOrEmpty()) {
            throw CliktError("GraphQL error: ${response.errors!!.joinToString { it.message }}")
        }
        return response.data ?: throw CliktError("GraphQL response contained no data")
    }

    fun close() = client.close()
}
```

Das `GraphQlGateway`-Interface (nicht die konkrete Klasse) wird in `CliConfig` als Factory-Funktion abgelegt, sodass Tests einen Fake-Gateway injizieren können.

### Output-Formatter

**View-Records entkoppeln Formatter von generierten Typen:** Jeder Command mapped die generierten GraphQL-Response-Typen auf Kotlin-`data class`-Records im Paket `cli/output/Views.kt`. Das hält die Formatter unabhängig von Schema-Änderungen und macht sie trivial testbar.

**`Output`-Interface:**

```kotlin
interface Output {
    fun writeCollections(items: List<CollectionView>)
    fun writeDocuments(items: List<DocumentView>)
    fun writeDocumentInfo(doc: DocumentInfoView)
    fun writeGraphRag(result: GraphRagResponseView)
    fun writeDocRag(result: DocRagResponseView)
    fun writeNlp(result: NlpResponseView)
    fun writeConfigEntries(items: List<ConfigEntryView>)
    fun writeConfigValue(entry: ConfigEntryView)
    fun writeExplanationSessions(items: List<QuestionExplanationView>)
    fun writeExplanationChain(chain: ExplanationChainView)
    fun writeDocumentHierarchy(root: DocumentNodeView)
    fun writeSimple(message: String)
}
```

**`TableOutput`** — handgeschriebener ASCII-Renderer, ~100 Zeilen:
- Listen: Tabellen mit dynamischer Spaltenbreite (max 60 Zeichen + Ellipsis-Truncation)
- Einzelobjekte: Key/Value-Blöcke mit Section-Headern
- `explain trace`: strukturierte Sections (Question / Exploration / Focus / Analyses / Conclusion) wie in der Feature-Doc
- `explain document`: ASCII-Tree (`├─ └─ │`)

**`JsonOutput`** — verwendet das bestehende `jackson-module-kotlin`:
- Jackson-Pretty-Printer serialisiert direkt die View-Records
- Timestamps als ISO-8601, Property-Namen in camelCase

**Fehlerausgabe:** Fehler werden nicht durch den Formatter geleitet, sondern als `CliktError(message)` geworfen. Clikt rendert das automatisch auf `stderr` mit non-zero Exit-Code. Stacktraces bleiben standardmäßig verborgen.

### Backend-Ergänzungen

#### Config-GraphQL-API

**Neue Datei `src/main/resources/graphql/config.graphqls`:**

```graphql
extend type Query {
    configKeys(type: String): [ConfigEntry!]!
    configValue(key: String!): ConfigEntry
}

extend type Mutation {
    setConfig(key: String!, value: String!, type: String!): ConfigEntry!
}

type ConfigEntry {
    key: String!
    type: String!
    value: String!
}
```

**Neuer Controller `src/main/kotlin/com/agentwork/graphmesh/config/ConfigGraphQlController.kt`:**

- `@Controller` mit `@QueryMapping configKeys`, `@QueryMapping configValue`, `@MutationMapping setConfig`
- Delegiert an den bestehenden `ConfigService`
- Wenn `ConfigService` aktuell keine Methoden zum Auflisten aller Keys bereitstellt, werden diese im Plan-Schritt ergänzt (kleine Erweiterung an bestehendem Service)

#### documentHierarchy-Query

**Erweiterung von `src/main/resources/graphql/explainability.graphqls`:**

```graphql
extend type Query {
    documentHierarchy(collectionId: ID!, documentUri: ID!): DocumentNode
}

type DocumentNode {
    uri: ID!
    title: String!
    type: String!
    children: [DocumentNode!]!
}
```

**Neuer Controller `src/main/kotlin/com/agentwork/graphmesh/provenance/DocumentHierarchyController.kt`:**

- `@Controller` mit `@QueryMapping documentHierarchy`
- Nutzt bestehenden `ProvenanceService` falls möglich
- Falls `ProvenanceService` die rekursive Auflösung nicht anbietet, wird ein kleiner Helper ergänzt, der PART_OF-Triples via `QuadStoreService` rekursiv zieht
- Schutz gegen Zyklen und maximale Tiefe (z. B. 10) zum Schutz vor pathologischen Graphen

Der genaue Integrationspunkt wird im Plan-Schritt durch Lesen des tatsächlichen `ProvenanceService`-Codes verifiziert.

## Test-Strategie

**Drei Ebenen:**

### 1. Formatter-Unit-Tests (pure, kein I/O)

- `TableOutputTest` — View-Records als Input, exakte String-Assertions auf ASCII-Output
- `JsonOutputTest` — Jackson-Round-Trip, Assertions auf `JsonNode`-Struktur (keine brittle String-Vergleiche)
- `ViewMappingTest` — Mapping von generierten GraphQL-Response-Typen auf `*View`-Records

### 2. Command-Tests über Clikt `.test()` + Fake-Gateway

- `CollectionCommandsTest`, `DocumentCommandsTest`, `QueryCommandsTest`, `ConfigCommandsTest`, `ExplainCommandsTest`
- Ein `FakeGraphQlGateway` wird in den `CliConfig` injiziert und liefert vordefinierte Responses
- Pro Command drei Szenarien: Happy-Path TABLE, Happy-Path JSON, GraphQL-Error → non-zero Exit
- Zusätzlich: fehlende Pflichtoption → Clikt-Usage-Error
- `DocumentCommandsTest` verifiziert das Base64-Encoding + MIME-Detection für Upload

### 3. Backend-Controller-Tests (MockMvc + Service-Mocks)

- `ConfigGraphQlControllerTest` — testet `configKeys`/`configValue`/`setConfig` gegen gemockten `ConfigService`
- `DocumentHierarchyControllerTest` — gemockter `ProvenanceService` / `QuadStoreService`, testet rekursive Baum-Konstruktion mit 2-3 Ebenen und Zyklen-Schutz

**Kein HTTP-Integrationstest** gegen eine laufende Instanz — der Mock-Gateway-Ansatz deckt die CLI-Logik vollständig ab.

## Akzeptanzkriterien

- [ ] `./gradlew cliRun --args="collection list"` → Tabelle aller Collections
- [ ] `./gradlew cliRun --args="collection create Test --description Desc --tag foo"` → neue Collection inkl. ID
- [ ] `./gradlew cliRun --args="collection delete <id>"` → löscht Collection
- [ ] `./gradlew cliRun --args="document upload -c <id> --file ./test.pdf"` → lädt Datei Base64-encodiert hoch, MIME automatisch erkannt
- [ ] `./gradlew cliRun --args="document list -c <id>"` → Dokumente als Tabelle
- [ ] `./gradlew cliRun --args="document info <id>"` → Detail-Ansicht
- [ ] `./gradlew cliRun --args="query graphrag -c <id> 'Frage'"` → Antwort mit Meta-Infos
- [ ] `./gradlew cliRun --args="query docrag -c <id> 'Frage'"` → Antwort mit Quellen
- [ ] `./gradlew cliRun --args="query nlp -c <id> 'Frage'"` → Antwort mit Intent
- [ ] `./gradlew cliRun --args="config list"` → alle Config-Keys als Tabelle
- [ ] `./gradlew cliRun --args="config get llm.model"` → einzelner Wert
- [ ] `./gradlew cliRun --args="config set llm.model gpt-4 --type STRING"` → Wert gesetzt
- [ ] `./gradlew cliRun --args="explain sessions -c <id>"` → Sessions-Liste
- [ ] `./gradlew cliRun --args="explain trace -c <id> <sessionUri>"` → vollständige Kette strukturiert
- [ ] `./gradlew cliRun --args="explain document -c <id> <documentUri>"` → Baum-Darstellung
- [ ] Alle Commands unterstützen `--format json` mit valider JSON-Ausgabe
- [ ] `GRAPHMESH_ENDPOINT` und `GRAPHMESH_TOKEN` als Env-Variablen werden respektiert
- [ ] Fehlermeldungen bei Connection-Refused / ungültigem Token / GraphQL-Errors sind verständlich (kein Stacktrace ohne Debug-Flag)
- [ ] `./gradlew cliJar` erzeugt ein ausführbares Fat-Jar, `java -jar build/libs/graphmesh-cli.jar collection list` funktioniert
- [ ] `./gradlew cliInstall` erzeugt `build/cli/bin/graphmesh` (Shell-Script) + `build/cli/bin/graphmesh.bat` + `build/cli/lib/graphmesh-cli.jar`
- [ ] `./build/cli/bin/graphmesh collection list` funktioniert (mit laufendem Backend)
- [ ] Backend: Neue `ConfigGraphQlController`- und `DocumentHierarchyController`-Endpunkte sind über GraphiQL erreichbar
- [ ] `./gradlew build` bleibt grün (alle bestehenden Tests + neue Tests)

## Betroffene Dateien

### CLI (neu)

```
src/main/kotlin/com/agentwork/graphmesh/cli/GraphMeshCli.kt
src/main/kotlin/com/agentwork/graphmesh/cli/CliConfig.kt
src/main/kotlin/com/agentwork/graphmesh/cli/BaseCommand.kt
src/main/kotlin/com/agentwork/graphmesh/cli/GraphQlGateway.kt
src/main/kotlin/com/agentwork/graphmesh/cli/commands/CollectionCommands.kt
src/main/kotlin/com/agentwork/graphmesh/cli/commands/DocumentCommands.kt
src/main/kotlin/com/agentwork/graphmesh/cli/commands/QueryCommands.kt
src/main/kotlin/com/agentwork/graphmesh/cli/commands/ConfigCommands.kt
src/main/kotlin/com/agentwork/graphmesh/cli/commands/ExplainCommands.kt
src/main/kotlin/com/agentwork/graphmesh/cli/output/Output.kt
src/main/kotlin/com/agentwork/graphmesh/cli/output/TableOutput.kt
src/main/kotlin/com/agentwork/graphmesh/cli/output/JsonOutput.kt
src/main/kotlin/com/agentwork/graphmesh/cli/output/Views.kt
src/main/kotlin/com/agentwork/graphmesh/cli/queries/*.graphql   (15 Dateien)
```

### Backend (neu)

```
src/main/kotlin/com/agentwork/graphmesh/config/ConfigGraphQlController.kt
src/main/kotlin/com/agentwork/graphmesh/provenance/DocumentHierarchyController.kt
src/main/resources/graphql/config.graphqls
```

### Backend (modifiziert)

```
src/main/resources/graphql/explainability.graphqls   (Erweiterung um documentHierarchy + DocumentNode)
src/main/kotlin/com/agentwork/graphmesh/config/ConfigService.kt   (ggf. Ergänzung für listAll)
src/main/kotlin/com/agentwork/graphmesh/provenance/ProvenanceService.kt   (ggf. rekursive Auflösung)
build.gradle.kts   (Dependencies, Plugin, Tasks cliRun/cliJar/cliInstall/assembleCliSchema)
```

### Tests (neu)

```
src/test/kotlin/com/agentwork/graphmesh/cli/output/TableOutputTest.kt
src/test/kotlin/com/agentwork/graphmesh/cli/output/JsonOutputTest.kt
src/test/kotlin/com/agentwork/graphmesh/cli/output/ViewMappingTest.kt
src/test/kotlin/com/agentwork/graphmesh/cli/commands/CollectionCommandsTest.kt
src/test/kotlin/com/agentwork/graphmesh/cli/commands/DocumentCommandsTest.kt
src/test/kotlin/com/agentwork/graphmesh/cli/commands/QueryCommandsTest.kt
src/test/kotlin/com/agentwork/graphmesh/cli/commands/ConfigCommandsTest.kt
src/test/kotlin/com/agentwork/graphmesh/cli/commands/ExplainCommandsTest.kt
src/test/kotlin/com/agentwork/graphmesh/cli/GraphQlGatewayTest.kt
src/test/kotlin/com/agentwork/graphmesh/config/ConfigGraphQlControllerTest.kt
src/test/kotlin/com/agentwork/graphmesh/provenance/DocumentHierarchyControllerTest.kt
```

## Offene Punkte für den Plan-Schritt

- Exakte Versionsnummern von Clikt, graphql-kotlin, Ktor-CIO (aus Maven Central bei Plan-Erstellung beziehen)
- Verifikation der genauen Property-API des `GraphQLGenerateClientTask` (je nach Plugin-Version können Attribute abweichen)
- Lesen des tatsächlichen `ProvenanceService`-Codes, um zu entscheiden, ob documentHierarchy eine neue Service-Methode braucht oder bestehendes wiederverwendet werden kann
- Lesen des tatsächlichen `ConfigService`-Codes, um zu entscheiden, ob `listAll(type?)` schon existiert oder ergänzt werden muss
- Verifikation der `CreateCollectionInput`-Feldnamen (`tags`, `metadata`) im aktuellen Code
