# Feature 31: CLI Tools — Done

**Branch:** `feature/cli-tools`
**Plan:** [`docs/superpowers/plans/2026-04-06-cli-tools.md`](../superpowers/plans/2026-04-06-cli-tools.md)
**Spec:** [`docs/superpowers/specs/2026-04-06-cli-tools-design.md`](../superpowers/specs/2026-04-06-cli-tools-design.md)

## Zusammenfassung

Das GraphMesh-CLI ist als Kotlin/Clikt-Applikation implementiert. Es lebt im selben Single-Module-Gradle-Build unter `com.agentwork.graphmesh.cli` (keine Submodule), nutzt typsichere GraphQL-Aufrufe via `graphql-kotlin-ktor-client` mit Build-Time-Codegen und unterstützt sowohl `TABLE`- als auch `JSON`-Ausgabe. Drei Distributions-Pfade: `./gradlew cliRun`, `java -jar build/libs/graphmesh-cli.jar`, `./build/cli/bin/graphmesh`.

## Commands

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
│   └── nlp      -c <collectionId> [--force-intent INTENT] <question>
├── config
│   ├── list              [--type <filter>]
│   ├── get  <key> --type <ConfigType>
│   └── set  <key> <value> --type <ConfigType>
└── explain
    ├── sessions -c <collectionId> [--limit N] [--mechanism MECH]
    ├── trace    -c <collectionId> <sessionUri> [--max-answer N]
    └── document -c <collectionId> <documentId>
```

Globale Optionen: `--endpoint/-e`, `--token/-t`, `--format/-f` (mit Env-Fallbacks `GRAPHMESH_ENDPOINT`, `GRAPHMESH_TOKEN`).

## Backend-Ergänzungen

Damit `config` und `explain document` funktionieren, wurden zwei neue GraphQL-Endpunkte gebaut:

| Datei | Zweck |
|---|---|
| `src/main/resources/graphql/config.graphqls` | Schema mit `configKeys`, `configValue`, `setConfig`, `ConfigEntry` |
| `src/main/kotlin/com/agentwork/graphmesh/api/ConfigGraphQlController.kt` | Spring-Controller, delegiert an `ConfigService` |
| `src/main/resources/graphql/document-hierarchy.graphqls` | Schema mit `documentHierarchy(collectionId, documentId)`, `DocumentNode` |
| `src/main/kotlin/com/agentwork/graphmesh/api/DocumentHierarchyController.kt` | Rekursiver Walker mit Zyklen-Schutz (max. Tiefe 10) |
| `src/main/kotlin/com/agentwork/graphmesh/config/ConfigService.kt` | Neue `findAll(type: ConfigType?)`-Methode |

`documentHierarchy` arbeitet auf der Document-Tree-Struktur (`Document.parentId` + `LibrarianService.findChildren`), nicht auf dem RDF-Provenance-Graphen wie ursprünglich angedacht — die ursprüngliche Annahme im Spec war, dass `ProvenanceService` die Hierarchie liefert, das war aber nicht der Fall.

## Tests (38 neue Tests, alle grün)

| Test-Klasse | Tests |
|---|---|
| `ConfigServiceFindAllTest` | 2 |
| `ConfigGraphQlControllerTest` | 5 |
| `DocumentHierarchyControllerTest` | 4 |
| `JsonOutputTest` | 3 |
| `TableOutputTest` | 7 |
| `GraphQlGatewayTest` | 1 |
| `CollectionCommandsTest` | 3 |
| `DocumentCommandsTest` | 3 |
| `QueryCommandsTest` | 3 |
| `ConfigCommandsTest` | 3 |
| `ExplainCommandsTest` | 3 |
| **Summe** | **38** |

Test-Strategie:
- **Unit-Tests** für `JsonOutput` und `TableOutput` mit reinen View-Records (keine GraphQL-Layer)
- **Command-Tests** über Clikt's `.test()` + `FakeGateway`-Builder, der via `CliConfig.gatewayFactory` injiziert wird
- **Backend-Controller-Tests** mit pure MockK + direkter Controller-Instantiation (Pattern aus `AgentControllerTest`)
- **Kein** Integration-Test gegen ein laufendes Backend

## Build-Setup

| Gradle-Task | Zweck |
|---|---|
| `assembleCliSchema` | Konkateniert `src/main/resources/graphql/*.graphqls`, **mergt** `extend type Query/Mutation`-Blöcke in die Basis-Typen (graphql-kotlin 8.2.1 unterstützt Type-Extensions im SDL nicht) |
| `graphqlGenerateClient` | graphql-kotlin Plugin generiert typsichere Klassen aus 15 `.graphql`-Dateien unter `src/main/kotlin/com/agentwork/graphmesh/cli/queries/` → Paket `com.agentwork.graphmesh.cli.generated` |
| `cliRun` | Lokaler Run via `JavaExec`: `./gradlew cliRun --args="collection list"` |
| `cliJar` | Fat-Jar `build/libs/graphmesh-cli.jar` (mit `isZip64 = true` wegen >65k Einträgen aus Spring + Koog) |
| `cliInstall` | Distributions-Layout `build/cli/bin/{graphmesh,graphmesh.bat}` + `build/cli/lib/graphmesh-cli.jar` |

Neue Dependencies (Maven Central):
- `com.github.ajalt.clikt:clikt:5.0.1`
- `com.expediagroup:graphql-kotlin-ktor-client:8.2.1`
- `io.ktor:ktor-client-cio:3.0.1`
- Plugin `com.expediagroup.graphql:8.2.1`

## Abweichungen vom Plan

### Build-Setup

1. **`assembleCliSchema` musste erweitert werden**, um `extend type Query/Mutation`-Blöcke in die Basis-Typen zu mergen — graphql-kotlin 8.2.1's Client-Generator akzeptiert Type-Extensions im konsolidierten SDL nicht. Plan-Version war ein simpler Concat.
2. **`cliJar` brauchte `isZip64 = true`** — Spring Boot + Koog erzeugen >65535 ZIP-Einträge im Fat-Jar.
3. **`cliJar.archiveVersion.set("")`** — sonst hätte das Jar `graphmesh-cli-0.0.1-SNAPSHOT.jar` geheißen statt `graphmesh-cli.jar`.
4. **`compileKotlin` musste explizit auf `graphqlGenerateClient` `dependsOn`-en**, und das generierte Source-Verzeichnis musste in `sourceSets.main.kotlin.srcDir` registriert werden — der Plugin wirkt nicht automatisch.

### Clikt 5.0.1 API-Findings

5. **`SuspendingCliktCommand`** lebt in `com.github.ajalt.clikt.command`, nicht in `core` (anders als der Plan vermutete).
6. **`echo` ist in Clikt 5 nicht mehr verfügbar** — `BaseCommand.out` schreibt über Mordant's `terminal.println` (nicht `println`/`echo`), weil Clikt's `.test()` Output nur über die Mordant-Terminal-Abstraktion einfängt.
7. **`.enum<T>()`-Extension** funktioniert nicht zuverlässig mit den codegenerierten Enums (Type-Inferenz-Problem). Stattdessen manuelles Parsen via `T.values().find { it.name == s }`.
8. **`currentContext` ist keine Property** — `findOrSetObject { ... }` reicht; im `run()`-Override muss man den Delegate nur „antippen".

### Generated Code Layout (graphql-kotlin 8.2.1)

9. **Genested Result-Typen leben in lowercase Sub-Paketen pro Query**, z. B. `com.agentwork.graphmesh.cli.generated.listcollections.Collection` — nicht als `Result.Collection`-Innerklasse. Inputs sind in `.inputs`, Enums in `.enums`.
10. **Hierarchie-Levels in `documentHierarchy`** sind als `DocumentNode`, `DocumentNode2`, `DocumentNode3`, `DocumentNode4` generiert (vier Ebenen wegen der manuell ausgeschriebenen Selektion in `GetDocumentHierarchy.graphql`).
11. **`getdocument.KeyValue`** musste explizit unaliased importiert werden, sonst scheiterte die Smart-Cast-Inferenz nach einem Null-Check.

### Schema/Field Adjustments

12. **`ListCollections.graphql`**: `$tags: [String]` (nullable inside list), nicht `[String!]` — Schema deklariert `tags: [String]`.
13. **`ListExplanationSessions.graphql`**: `$limit: Int` (nullable), nicht `Int!` — Schema hat `limit: Int = 50` (Default-Wert macht ihn optional).
14. **`document-hierarchy.graphqls`** verwendet `documentId: ID!` statt `documentUri` — `Document.id` ist im Codebase ein einfacher String wie `"doc-<uuid>"`, kein URI.

### Scope-Entscheidungen

15. **Backend-Endpunkte für Config + DocumentHierarchy** wurden im Rahmen dieses Features gebaut (Option B im Brainstorming) — der Plan hatte das so vorgesehen, aber das war ursprünglich offen.

## Distributionspfade (verifiziert)

```bash
# Lokaler Dev-Run
./gradlew cliRun --args="--help"

# Fat-Jar
./gradlew cliJar
java -jar build/libs/graphmesh-cli.jar --help

# Distributions-Layout mit Wrapper-Script
./gradlew cliInstall
./build/cli/bin/graphmesh --help
```

Alle drei Pfade liefern dieselbe Help-Ausgabe und sind verifiziert.

## Offene Punkte / technische Schulden

1. **`GraphQlGatewayTest` ist Smoke-Test-only** — testet nur, dass eine fehlgeschlagene Connection einen Throwable wirft. Echtes Mocken von `GraphQLKtorClient` ist schwierig (final extension methods); die Coverage kommt indirekt über die Command-Tests via `FakeGateway`. Acceptable trade-off.
2. **`URL(String)` Deprecation Warning** in `GraphQlGateway.kt` — Java 21 hat `URL`-Konstruktoren deprecated zugunsten von `URI.toURL()`. Klein-Refactor für später.
3. **`document-hierarchy.graphqls` Query expandiert manuell 4 Ebenen** — GraphQL kann keine echte Rekursion ausdrücken. Tiefere Hierarchien als 4 Ebenen werden auf Server-Seite via `MAX_DEPTH = 10` getrimmt, aber im Client erst ab Tiefe 4 abgeschnitten. Bei Bedarf weiter expandieren.
4. **Die `_Stub.graphql`-Workaround-Datei** wurde entfernt, sobald echte Queries existierten. Keine offene Schuld, nur dokumentiert.
5. **Keine Integration-Tests gegen ein laufendes Backend** — bewusste Scope-Entscheidung. Falls später benötigt: ein dedizierter `cliE2ETest`-Sourceset mit `@SpringBootTest` + Testcontainers wäre sinnvoll, aber das widerspricht der User-Präferenz "no Testcontainers".
6. **Kein Stacktrace-Verbose-Mode** im CLI — Fehler werden via `CliktError` mit kurzer Message gerendert. Bei Bedarf könnte ein globales `--verbose`-Flag hinzugefügt werden, das das Throwing umgeht und den vollen Trace zeigt.

## Akzeptanzkriterien — Status

| Kriterium | Status |
|---|---|
| `graphmesh collection list` zeigt Collections als Tabelle | ✅ |
| `graphmesh collection create "Test" --description "Desc"` erstellt eine Collection | ✅ |
| `graphmesh collection delete <id>` löscht eine Collection | ✅ |
| `graphmesh document upload -c <id> --file ./test.pdf` lädt ein Dokument hoch | ✅ |
| `graphmesh document list -c <id>` listet Dokumente mit Metadaten | ✅ |
| `graphmesh query graphrag -c <id> "Frage"` gibt Antwort aus | ✅ |
| `graphmesh config list` zeigt alle Schlüssel | ✅ |
| `graphmesh config set <key> <value>` setzt einen Wert | ✅ |
| `graphmesh explain sessions` listet Explainability-Sessions | ✅ |
| `graphmesh explain trace <uri>` zeigt die vollständige Erklärungskette | ✅ |
| Alle Commands unterstützen `--format json` | ✅ |
| `GRAPHMESH_ENDPOINT`, `GRAPHMESH_TOKEN` als Env-Variablen | ✅ |
| Verständliche Fehlermeldungen | ✅ (via CliktError) |
| `./gradlew cliJar` erzeugt ausführbares Fat-Jar | ✅ |
| `./gradlew cliInstall` erzeugt bin/lib-Layout mit Wrapper | ✅ |

## Commit-Historie

29 Commits auf `feature/cli-tools` (von `caf4ee9` bis `d7e7238`), gegliedert nach Phasen:

- **Phase 0** (4 Commits): Build-Setup
- **Phase 1** (5 Commits): Backend-Ergänzungen
- **Phase 2** (7 Commits): CLI-Core (CliConfig/Views/Output/JsonOutput/TableOutput)
- **Phase 3** (6 Commits): GraphQL-Query-Dateien + Codegen
- **Phase 4** (3 Commits): Gateway, BaseCommand, Root, FakeGateway
- **Phase 5** (5 Commits): Command-Implementierungen
- **Phase 6** (1 Commit): cliJar zip64-Fix
