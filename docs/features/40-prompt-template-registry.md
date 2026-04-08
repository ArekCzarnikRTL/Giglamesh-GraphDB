# Feature 40: Prompt Template Registry

## Problem

Prompt-Templates sind in GraphMesh aktuell als Kotlin-Objekte hart verdrahtet:
`DefinitionPromptTemplate`, `ExtractionPromptTemplate`, und kuenftig
`TopicPromptTemplate` (Feature 38) bzw. viele weitere. Jede Aenderung an einem Prompt
erfordert einen Re-Deploy. Das hat mehrere Konsequenzen:

1. **Keine Experimente zur Laufzeit** -- A/B-Tests zwischen zwei Prompt-Varianten sind
   unmoeglich.
2. **Keine Mehrsprachigkeit** -- deutscher und englischer Prompt muesste als separater
   Kotlin-Code leben.
3. **Kein Wiederverwenden von Variablen** -- jede Template-Klasse definiert eigene
   Strings, Copy-Paste der "Regeln"-Blocks fuehrt zu Drift.
4. **Schlechte Versionierbarkeit** -- "welchen Prompt hat der LLM-Aufruf X gestern
   benutzt?" ist nicht rekonstruierbar.
5. **Kein Response-Type-Contract** -- jede Antwort wird ad-hoc als JSONL oder Text
   geparst; keine zentrale Stelle sagt "dieser Prompt liefert strukturiertes JSON".

TrustGraph verwendet ein zentrales, Jinja-aehnliches Template-System mit Global Terms,
Prompt-spezifischen Terms und einer Response-Type-Deklaration. Das holen wir uns
nach -- Kotlin-idiomatisch und Spring-nativ.

## Ziel

Einfuehrung einer `PromptTemplateRegistry`, die Prompt-Templates als Daten (nicht als
Code) verwaltet: ladbar aus YAML-Dateien und Cassandra, ueberschreibbar zur Laufzeit,
mit Variablen-Substitution und einem Response-Type-Contract.

1. **Template-Definition** -- YAML mit `id`, `version`, `language`, `system`, `user`,
   `responseType`, `variables`.
2. **Variablen-Substitution** -- einfache `{{ var }}`-Syntax; keine Turing-vollstaendige
   Template-Engine.
3. **Global Terms** -- projektweite Variablen (z.B. `{{ projectName }}`, `{{ today }}`)
   werden zuerst injiziert.
4. **Registry-Backend** -- Default: YAML-Dateien unter `src/main/resources/prompts/`;
   Override: Cassandra-Tabelle `prompt_templates` (dynamisch per Admin-UI pflegbar).
5. **Response-Type-Contract** -- `text`, `jsonl`, `json-object`, `json-schema:<name>`;
   der Caller bekommt einen typisierten Parser zurueck.
6. **Fallback-Chain** -- Lookup-Reihenfolge: Cassandra(lang, version) ->
   Cassandra(lang) -> YAML(lang) -> YAML(default).
7. **Versionierung & Audit** -- jeder LLM-Aufruf loggt `templateId@version`, damit
   Feature 39 (LLM Observability) und #30 (Query Explainability) sichtbar machen, welche
   Prompt-Version tatsaechlich verwendet wurde.

## Voraussetzungen

| Abhaengigkeit                                                        | Status     | Blocker? |
|----------------------------------------------------------------------|------------|----------|
| Feature 02: Cassandra Storage Layer                                  | Vorhanden  | Ja       |
| Feature 05: LLM Provider Abstraction (`PromptExecutor`)              | Vorhanden  | Ja       |
| Feature 06: Configuration Service                                    | Vorhanden  | Empfohlen|
| Jackson YAML (`jackson-dataformat-yaml`)                             | Verfuegbar | Ja       |

## Architektur

### YAML-Format

```yaml
# src/main/resources/prompts/definition-extractor/v1.de.yml
id: definition-extractor
version: 1
language: de
responseType: jsonl
description: >
  Extrahiert Entitaetsdefinitionen aus einem Textchunk.
variables:
  - name: chunkText
    required: true
    description: Der zu analysierende Chunk-Text.
system: |
  Du bist ein Wissensextraktions-Assistent. Deine Aufgabe ist es,
  Definitionen und Beschreibungen von Entitaeten aus dem gegebenen Text
  zu extrahieren.

  Extrahiere fuer jede Entitaet ein JSON-Objekt pro Zeile im Format:
  {"entity": "<Name>", "definition": "<Definition>"}

  Regeln:
  - Nur explizit im Text enthaltene Definitionen.
  - Ignoriere reine Beziehungen (z.B. "A arbeitet bei B").
  - JSONL, keine Code-Fences.
user: |
  Extrahiere alle Entitaets-Definitionen aus folgendem Text:

  ---
  {{ chunkText }}
  ---

  Antworte NUR mit JSON-Objekten im JSONL-Format, eines pro Zeile.
```

### Datenklassen

```kotlin
package com.agentwork.graphmesh.prompt

enum class ResponseType {
    TEXT,          // freier Text
    JSONL,         // ein JSON-Objekt pro Zeile, truncation-resilient
    JSON_OBJECT,   // exakt ein JSON-Objekt
    JSON_SCHEMA    // siehe PromptTemplate.schemaRef
}

data class TemplateVariable(
    val name: String,
    val required: Boolean = true,
    val description: String? = null,
    val default: String? = null
)

data class PromptTemplate(
    val id: String,
    val version: Int,
    val language: String,        // "de", "en", ...
    val responseType: ResponseType,
    val description: String? = null,
    val variables: List<TemplateVariable>,
    val system: String,
    val user: String,
    val schemaRef: String? = null  // nur bei ResponseType.JSON_SCHEMA
)

data class RenderedPrompt(
    val templateId: String,
    val version: Int,
    val language: String,
    val responseType: ResponseType,
    val system: String,
    val user: String
)
```

### PromptTemplateRegistry

```kotlin
package com.agentwork.graphmesh.prompt

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import jakarta.annotation.PostConstruct

/**
 * Verwaltet alle Prompt-Templates. Laedt YAML-Dateien aus dem Classpath beim Start
 * und erlaubt Overrides aus Cassandra.
 *
 * Lookup-Reihenfolge bei render(id, lang):
 *   1. Cassandra(id, lang, latest)
 *   2. Cassandra(id, "en",  latest)
 *   3. YAML(id, lang)
 *   4. YAML(id, "en")
 */
@Component
class PromptTemplateRegistry(
    private val cassandraStore: CassandraPromptStore,
    private val globalTerms: GlobalTermsProvider
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerModule(kotlinModule())
    private val classpathTemplates = mutableMapOf<TemplateKey, PromptTemplate>()

    @PostConstruct
    fun loadClasspathTemplates() {
        val resolver = PathMatchingResourcePatternResolver()
        val resources = resolver.getResources("classpath*:prompts/**/*.yml")
        for (res in resources) {
            val template: PromptTemplate = yamlMapper.readValue(res.inputStream)
            classpathTemplates[TemplateKey(template.id, template.language)] = template
            logger.info("Loaded prompt template {}@v{} lang={}", template.id, template.version, template.language)
        }
    }

    fun render(templateId: String, language: String = "de", vars: Map<String, String>): RenderedPrompt {
        val template = lookup(templateId, language)
            ?: error("Unknown prompt template: $templateId ($language)")

        // Variablen validieren
        for (v in template.variables.filter { it.required }) {
            require(vars[v.name] != null || v.default != null) {
                "Missing required variable '${v.name}' for template $templateId"
            }
        }

        val effectiveVars = globalTerms.all() + template.variables.associate { v ->
            v.name to (vars[v.name] ?: v.default ?: "")
        }

        val system = substitute(template.system, effectiveVars)
        val user = substitute(template.user, effectiveVars)

        return RenderedPrompt(
            templateId = template.id,
            version = template.version,
            language = template.language,
            responseType = template.responseType,
            system = system,
            user = user
        )
    }

    private fun lookup(id: String, lang: String): PromptTemplate? =
        cassandraStore.findLatest(id, lang)
            ?: cassandraStore.findLatest(id, "en")
            ?: classpathTemplates[TemplateKey(id, lang)]
            ?: classpathTemplates[TemplateKey(id, "en")]

    /** Sehr bewusst minimal: nur `{{ name }}`-Substitution, kein Logik. */
    internal fun substitute(template: String, vars: Map<String, String>): String {
        val pattern = Regex("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\}\\}")
        return pattern.replace(template) { match ->
            vars[match.groupValues[1]] ?: match.value  // unbekannte Var bleibt stehen -> sichtbar im Prompt
        }
    }

    private data class TemplateKey(val id: String, val language: String)
}
```

### GlobalTermsProvider

```kotlin
package com.agentwork.graphmesh.prompt

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class GlobalTermsProvider(
    @Value("\${graphmesh.project.name:GraphMesh}") private val projectName: String
) {
    fun all(): Map<String, String> = mapOf(
        "projectName" to projectName,
        "today" to LocalDate.now().toString()
    )
}
```

### CassandraPromptStore

```kotlin
package com.agentwork.graphmesh.prompt

import com.datastax.oss.driver.api.core.CqlSession
import org.springframework.stereotype.Component

/**
 * Cassandra-Tabelle:
 *
 *   CREATE TABLE IF NOT EXISTS graphmesh.prompt_templates (
 *     id        text,
 *     language  text,
 *     version   int,
 *     response_type text,
 *     system    text,
 *     user      text,
 *     variables text,         -- JSON-Array
 *     schema_ref text,
 *     updated_at timestamp,
 *     updated_by text,
 *     PRIMARY KEY ((id, language), version)
 *   ) WITH CLUSTERING ORDER BY (version DESC);
 */
@Component
class CassandraPromptStore(private val session: CqlSession) {
    fun findLatest(id: String, language: String): PromptTemplate? = TODO()
    fun save(template: PromptTemplate, updatedBy: String) { /* INSERT */ }
    fun history(id: String, language: String): List<PromptTemplate> = TODO()
}
```

### Typed Response Parsing

```kotlin
package com.agentwork.graphmesh.prompt

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Haelt ResponseType und Template zusammen, sodass Aufrufer
 * strukturierte Parser bekommen, ohne selber JSONL-Code zu schreiben.
 */
class PromptResponseParser(private val mapper: ObjectMapper) {

    fun <T> parseJsonl(rendered: RenderedPrompt, raw: String, itemType: Class<T>): List<T> {
        require(rendered.responseType == ResponseType.JSONL)
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line -> runCatching { mapper.readValue(line, itemType) }.getOrNull() }
    }

    fun <T> parseJsonObject(rendered: RenderedPrompt, raw: String, type: Class<T>): T {
        require(rendered.responseType == ResponseType.JSON_OBJECT)
        return mapper.readValue(raw.trim().trim('`'), type)
    }
}
```

### Beispiel-Migration: DefinitionExtractorService

```kotlin
val rendered = templateRegistry.render(
    templateId = "definition-extractor",
    language = "de",
    vars = mapOf("chunkText" to chunkText)
)

val extractionPrompt = prompt("definition-extraction") {
    system(rendered.system)
    user(rendered.user)
}

val llmResponse = metrics.record("definition-extractor", modelName,
    collection = collectionId,
    extraTag = "template" to "${rendered.templateId}@v${rendered.version}"
) { bucket ->
    runBlocking { promptExecutor.execute(extractionPrompt, resolveLlmModel(modelName)) }
}

val definitions = responseParser.parseJsonl(rendered, llmResponse.first().content, DefinitionResult::class.java)
```

Das loescht `DefinitionPromptTemplate`-Objekt und uebertraegt den Inhalt in
`src/main/resources/prompts/definition-extractor/v1.de.yml`.

### GraphQL-Erweiterung (optional)

```graphql
type PromptTemplate {
  id: String!
  version: Int!
  language: String!
  responseType: String!
  description: String
  system: String!
  user: String!
}

extend type Query {
  promptTemplates: [PromptTemplate!]!
  promptTemplate(id: String!, language: String!): PromptTemplate
}

extend type Mutation {
  overridePromptTemplate(
    id: String!, language: String!, system: String!, user: String!, responseType: String!
  ): PromptTemplate!
  revertPromptTemplate(id: String!, language: String!): Boolean!
}
```

## Betroffene Dateien

### Backend

| Datei                                                                                               | Aenderung                                         |
|-----------------------------------------------------------------------------------------------------|---------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/prompt/PromptTemplateRegistry.kt`                          | NEU - zentrale Registry + Lookup-Chain            |
| `src/main/kotlin/com/agentwork/graphmesh/prompt/PromptTemplate.kt`                                  | NEU - Datenklassen + `ResponseType`               |
| `src/main/kotlin/com/agentwork/graphmesh/prompt/GlobalTermsProvider.kt`                             | NEU - `projectName`, `today`, ...                 |
| `src/main/kotlin/com/agentwork/graphmesh/prompt/CassandraPromptStore.kt`                            | NEU - Override-Store                              |
| `src/main/kotlin/com/agentwork/graphmesh/prompt/PromptResponseParser.kt`                            | NEU - getippter JSONL/JSON-Parser                 |
| `src/main/kotlin/com/agentwork/graphmesh/storage/CassandraSchemaInitializer.kt`                     | UPDATE - `prompt_templates`-Tabelle               |
| `src/main/kotlin/com/agentwork/graphmesh/api/graphql/PromptTemplateDataFetcher.kt`                  | NEU - GraphQL-Queries/Mutations                   |
| `src/main/resources/schema/prompt-templates.graphqls`                                               | NEU - GraphQL-Typen                               |
| `src/main/resources/prompts/definition-extractor/v1.de.yml`                                         | NEU - Migration aus `DefinitionPromptTemplate`    |
| `src/main/resources/prompts/relationship-extractor/v1.de.yml`                                       | NEU - Migration aus `ExtractionPromptTemplate`    |
| `src/main/resources/prompts/topic-extractor/v1.de.yml`                                              | NEU - Feature 38                                  |
| `src/main/resources/prompts/nlp-query/v1.de.yml`                                                    | NEU - NLP Query Prompt                            |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorService.kt`       | UPDATE - nutzt Registry statt `DefinitionPromptTemplate` |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/relationship/RelationshipExtractorService.kt`   | UPDATE - analog                                   |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionPromptTemplate.kt`         | DELETE                                            |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/relationship/ExtractionPromptTemplate.kt`       | DELETE                                            |
| `build.gradle.kts`                                                                                  | UPDATE - `jackson-dataformat-yaml`                |

### Frontend

| Datei                                                                  | Aenderung                                         |
|------------------------------------------------------------------------|---------------------------------------------------|
| `frontend/src/app/admin/prompts/page.tsx`                              | NEU - Liste aller Templates (aus GraphQL)         |
| `frontend/src/app/admin/prompts/[id]/[lang]/page.tsx`                  | NEU - Editor (System/User/ResponseType)           |
| `frontend/src/components/prompts/TemplateEditor.tsx`                   | NEU - Monaco-basiert, mit Variable-Vorschau       |

### Tests

| Datei                                                                                      | Aenderung                                       |
|--------------------------------------------------------------------------------------------|-------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/prompt/PromptTemplateRegistryTest.kt`             | NEU - YAML-Load, Lookup-Chain, Substitution     |
| `src/test/kotlin/com/agentwork/graphmesh/prompt/PromptSubstitutionTest.kt`                 | NEU - Edge Cases (unbekannte Var, Whitespace)   |
| `src/test/kotlin/com/agentwork/graphmesh/prompt/PromptResponseParserTest.kt`               | NEU - JSONL/JSON-Object-Parser                  |
| `src/test/kotlin/com/agentwork/graphmesh/prompt/CassandraPromptStoreTest.kt`               | NEU - Versionierung, findLatest                 |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                               |
|-------------------|-------------|-------------------------------------|
| Spring Boot (JVM) | Ja          | Jackson YAML, Cassandra, Classpath  |

## Akzeptanzkriterien

- [ ] Registry laedt beim Start alle YAML-Dateien unter `classpath*:prompts/**/*.yml`.
- [ ] `render(id, lang, vars)` substituiert `{{ var }}`-Platzhalter; unbekannte Variablen bleiben als `{{ name }}` erhalten (damit sie im Prompt sichtbar sind).
- [ ] Fehlende **required** Variablen ohne Default werfen eine klare Exception.
- [ ] Global Terms (`projectName`, `today`) sind ohne weiteres Zutun verfuegbar.
- [ ] Lookup-Chain respektiert die Reihenfolge Cassandra(lang) -> Cassandra(en) -> YAML(lang) -> YAML(en).
- [ ] `DefinitionExtractorService` und `RelationshipExtractorService` verwenden die Registry; die alten `*PromptTemplate`-Objekte sind entfernt.
- [ ] `RenderedPrompt` enthaelt `templateId` und `version`, damit Metrics/Explainability loggen koennen, welche Variante gelaufen ist.
- [ ] `PromptResponseParser.parseJsonl` liefert nur gueltige Zeilen; invalide werden stillschweigend verworfen (wie bisher).
- [ ] Admin-UI kann einen Override speichern; der naechste LLM-Aufruf verwendet ohne Neustart die neue Version.
- [ ] Cassandra-History-Endpoint liefert alle Versionen eines Templates absteigend sortiert.
- [ ] Integrationstest: Ueberschreiben eines Templates per GraphQL veraendert das nachfolgende Extraktions-Ergebnis (Fake-LLM verifiziert Prompt-Inhalt).
