# Feature 26: Tool Groups & Tool Services

## Problem

Der Agent (Feature 25) hat Zugriff auf alle registrierten Tools, unabhaengig vom Anfragekontext oder den
Sicherheitsanforderungen. Dies fuehrt zu mehreren Problemen: Sensible Tools (z.B. Schreiboperationen) stehen auch bei
Read-Only-Anfragen zur Verfuegung, der Agent waehlt unnoetig komplexe Tools wenn einfachere genuegen wuerden, und
verschiedene Benutzergruppen benoetigen unterschiedliche Tool-Sets. Zusaetzlich fehlt ein Mechanismus, um neue Tools
dynamisch per Konfiguration hinzuzufuegen, ohne den Agent-Code zu aendern.

## Ziel

Implementierung eines Tool-Gruppen-Systems mit dynamischen Tool Services und Flow-basierter Konfiguration nach dem
Blueprint-Instance-Pattern.

1. **Tool-Gruppen** -- Klassifizierung von Tools in Gruppen (basic, advanced, read-only, write) mit
   Mehrfachzugehoerigkeit
2. **Request-Time-Filtering** -- Einschraenkung verfuegbarer Tools pro Anfrage basierend auf Gruppenangabe
3. **Tool Service Registry** -- Dynamisch registrierbare Tool Services via Konfiguration
4. **Flow Definitions** -- Blueprint-Pattern fuer konfigurierbare Verarbeitungs-Flows mit Parametern
5. **Flow Instances** -- Instanziierung von Blueprints mit konkreten Parameterwerten (Modell, Temperatur, Chunk-Groesse)
6. **State-basierte Verfuegbarkeit** -- Tools koennen nur in bestimmten Agentenzustaenden verfuegbar sein

## Voraussetzungen

| Abhaengigkeit                                                    | Status     | Blocker? |
|------------------------------------------------------------------|------------|----------|
| Feature 25: Agent System (AgentService, AgentTool, AgentContext) | Geplant    | Ja       |
| Feature 06: Configuration Service (ConfigService, ConfigHandler) | Geplant    | Ja       |
| Feature 17: MCP Tool Interface (McpServer, McpTool)              | Geplant    | Ja       |
| Spring Boot 4.x                                                  | Verfuegbar | Nein     |

## Architektur

### ToolGroup

```kotlin
package com.graphmesh.agent.tools

/**
 * Eine Tool-Gruppe definiert eine logische Zusammenfassung von Tools.
 * Tools koennen mehreren Gruppen angehoeren.
 */
data class ToolGroup(
    /** Eindeutiger Name der Gruppe. */
    val name: String,
    /** Beschreibung der Gruppe. */
    val description: String,
    /** Tool-Namen, die zu dieser Gruppe gehoeren. */
    val toolNames: Set<String> = emptySet()
) {
    companion object {
        val BASIC = ToolGroup("basic", "Grundlegende Abfrage-Tools")
        val ADVANCED = ToolGroup("advanced", "Erweiterte Analyse-Tools")
        val READ_ONLY = ToolGroup("read-only", "Nur lesende Tools")
        val WRITE = ToolGroup("write", "Schreibende Tools")
        val DEFAULT = ToolGroup("default", "Standard-Gruppe fuer unkategorisierte Tools")
    }
}
```

### Tool-Gruppen-Zuweisung und Filterung

```kotlin
package com.graphmesh.agent.tools

import com.graphmesh.agent.AgentTool

/**
 * Registry fuer Tool Services mit Gruppen- und State-basierter Filterung.
 */
interface ToolServiceRegistry {

    /**
     * Registriert ein Tool mit Gruppenzuordnung.
     *
     * @param tool Das zu registrierende Tool.
     * @param groups Die Gruppen, denen das Tool angehoert.
     * @param availableInStates Zustaende, in denen das Tool verfuegbar ist (leer = alle).
     */
    fun register(
        tool: AgentTool,
        groups: Set<String> = setOf("default"),
        availableInStates: Set<String> = emptySet()
    )

    /**
     * Filtert Tools basierend auf angeforderten Gruppen und aktuellem Zustand.
     *
     * @param requestedGroups Die angeforderten Gruppen. "*" = alle Tools.
     * @param currentState Der aktuelle Agentenzustand.
     * @return Liste der verfuegbaren Tools.
     */
    fun filterTools(
        requestedGroups: Set<String> = setOf("default"),
        currentState: String = "undefined"
    ): List<AgentTool>

    /**
     * Gibt alle bekannten Tool-Gruppen zurueck.
     */
    fun getGroups(): List<ToolGroup>
}
```

### Tool Service Descriptor

```kotlin
package com.graphmesh.agent.tools

/**
 * Beschreibt einen dynamisch registrierbaren Tool Service.
 * Analoges Konzept zum MCP-Server: Der Descriptor definiert die Schnittstelle,
 * die Tool-Konfiguration referenziert ihn.
 */
data class ToolServiceDescriptor(
    /** Eindeutige ID des Tool Service. */
    val id: String,
    /** Kafka-Topic fuer Requests. */
    val requestTopic: String,
    /** Kafka-Topic fuer Responses. */
    val responseTopic: String,
    /** Konfigurationsparameter, die der Service akzeptiert. */
    val configParams: List<ConfigParam> = emptyList()
)

data class ConfigParam(
    val name: String,
    val required: Boolean = false
)

/**
 * Konfiguration eines konkreten Tools, das einen Tool Service referenziert.
 */
data class ToolServiceConfig(
    /** Name des Tools (wird dem LLM gezeigt). */
    val name: String,
    /** Beschreibung (wird dem LLM gezeigt). */
    val description: String,
    /** Referenzierte Tool-Service-ID. */
    val serviceId: String,
    /** Konfigurationswerte fuer den Service. */
    val configValues: Map<String, String> = emptyMap(),
    /** Argument-Definitionen fuer das LLM. */
    val arguments: List<ToolArgument> = emptyList(),
    /** Gruppen, denen dieses Tool angehoert. */
    val groups: Set<String> = setOf("default"),
    /** Zustaende, in denen das Tool verfuegbar ist. */
    val availableInStates: Set<String> = emptySet(),
    /** Zustand, zu dem nach erfolgreicher Ausfuehrung gewechselt wird. */
    val transitionToState: String? = null
)

data class ToolArgument(
    val name: String,
    val type: String,
    val description: String
)
```

### Flow Definition (Blueprint-Pattern)

```kotlin
package com.graphmesh.agent.tools

/**
 * Blueprint fuer einen Verarbeitungs-Flow.
 * Definiert Prozessoren, Interfaces und konfigurierbare Parameter.
 * Wird nicht direkt ausgefuehrt, sondern als Vorlage fuer FlowInstances verwendet.
 */
data class FlowDefinition(
    /** Eindeutiger Name des Blueprints. */
    val name: String,
    /** Beschreibung des Flows. */
    val description: String,
    /** Konfigurierbare Parameter mit Referenz auf zentrale Parameter-Definitionen. */
    val parameters: Map<String, FlowParameter>,
    /** Shared Prozessoren (einmal pro Blueprint-Klasse instanziiert). */
    val classProcessors: Map<String, ProcessorSpec> = emptyMap(),
    /** Flow-spezifische Prozessoren (einmal pro Instance instanziiert). */
    val flowProcessors: Map<String, ProcessorSpec> = emptyMap(),
    /** Interfaces (Entry-Points und Service-Endpunkte). */
    val interfaces: Map<String, String> = emptyMap()
)

/**
 * Konfigurierbarer Parameter eines Flow Blueprints.
 */
data class FlowParameter(
    /** Referenz auf zentral definierte Parameterdefinition. */
    val type: String,
    /** Beschreibung fuer UI und Dokumentation. */
    val description: String,
    /** Anzeigereihenfolge in der UI. */
    val order: Int = 0,
    /** Ob dies ein erweiterter Parameter ist (in einfacher UI versteckt). */
    val advanced: Boolean = false,
    /** Anderer Parameter, der diesen steuert (Vererbung im einfachen Modus). */
    val controlledBy: String? = null
)

data class ProcessorSpec(
    val input: String? = null,
    val output: String? = null,
    val request: String? = null,
    val response: String? = null,
    val settings: Map<String, String> = emptyMap()
)
```

### Flow Instance

```kotlin
package com.graphmesh.agent.tools

import java.util.UUID

/**
 * Konkrete Instanz eines Flow Blueprints mit aufgeloesten Parametern.
 */
data class FlowInstance(
    /** Eindeutige ID dieser Instanz. */
    val id: UUID = UUID.randomUUID(),
    /** Name des zugrunde liegenden Blueprints. */
    val blueprintName: String,
    /** Aufgeloeste Parameterwerte (User-Werte + Defaults). */
    val resolvedParameters: Map<String, String>,
    /** Status der Instanz. */
    val status: FlowInstanceStatus = FlowInstanceStatus.CREATED
)

enum class FlowInstanceStatus {
    CREATED, RUNNING, STOPPED, FAILED
}
```

### Request-Level Tool Filtering

```kotlin
package com.graphmesh.agent

/**
 * Erweiterter Agent-Request mit Tool-Gruppen-Spezifikation.
 */
data class AgentRequest(
    val question: String,
    val collectionId: UUID,
    val config: AgentConfig = AgentConfig(),
    /** Tool-Gruppen, die fuer diese Anfrage erlaubt sind. Leer = "default". */
    val allowedGroups: Set<String> = setOf("default"),
    /** Aktueller Workflow-State fuer state-basiertes Filtering. */
    val workflowState: String = "undefined"
)
```

## Betroffene Dateien

### Backend

| Datei                                                                           | Aenderung                             |
|---------------------------------------------------------------------------------|---------------------------------------|
| `agent/src/main/kotlin/com/graphmesh/agent/tools/ToolGroup.kt`                  | Tool-Gruppen-Definition               |
| `agent/src/main/kotlin/com/graphmesh/agent/tools/ToolServiceRegistry.kt`        | Registry-Interface mit Filterung      |
| `agent/src/main/kotlin/com/graphmesh/agent/tools/DefaultToolServiceRegistry.kt` | Implementierung der Registry          |
| `agent/src/main/kotlin/com/graphmesh/agent/tools/ToolServiceDescriptor.kt`      | Dynamische Tool-Service-Konfiguration |
| `agent/src/main/kotlin/com/graphmesh/agent/tools/ToolServiceConfig.kt`          | Tool-Instanz-Konfiguration            |
| `agent/src/main/kotlin/com/graphmesh/agent/tools/FlowDefinition.kt`             | Flow Blueprint-Definition             |
| `agent/src/main/kotlin/com/graphmesh/agent/tools/FlowParameter.kt`              | Konfigurierbare Parameter             |
| `agent/src/main/kotlin/com/graphmesh/agent/tools/FlowInstance.kt`               | Instanz mit aufgeloesten Parametern   |
| `agent/src/main/kotlin/com/graphmesh/agent/DefaultAgentService.kt`              | Erweiterung um Gruppen-Filtering      |
| `agent/src/main/kotlin/com/graphmesh/agent/AgentRequest.kt`                     | Erweiterung um `allowedGroups`        |

### Frontend

Nicht direkt betroffen. UI-Integration fuer Flow-Parameter-Formulare ist ein separates Feature.

### Tests

| Datei                                                                        | Aenderung                           |
|------------------------------------------------------------------------------|-------------------------------------|
| `agent/src/test/kotlin/com/graphmesh/agent/tools/ToolServiceRegistryTest.kt` | Tests fuer Gruppen-Filterung        |
| `agent/src/test/kotlin/com/graphmesh/agent/tools/FlowDefinitionTest.kt`      | Tests fuer Blueprint-Instanziierung |
| `agent/src/test/kotlin/com/graphmesh/agent/tools/ToolServiceConfigTest.kt`   | Tests fuer dynamische Tool-Services |
| `agent/src/test/kotlin/com/graphmesh/agent/DefaultAgentServiceTest.kt`       | Erweiterung um Gruppen-Tests        |

## Platform-Einschraenkungen

| Plattform         | Unterstuetzt | Anmerkung                              |
|-------------------|--------------|----------------------------------------|
| Spring Boot (JVM) | Ja           | Primaere Zielplattform                 |
| KMP Library       | Nein         | Abhaengig von Spring-Kontext und Kafka |
| Ktor/Wasm         | Nein         | Server-seitige Tool-Registry           |

## Akzeptanzkriterien

- [ ] Tools koennen mehreren Gruppen gleichzeitig zugeordnet werden
- [ ] `filterTools(setOf("read-only"))` gibt nur Tools zurueck, die der Gruppe "read-only" angehoeren
- [ ] Wildcard-Gruppe `"*"` gibt alle registrierten Tools zurueck
- [ ] Leere Gruppenangabe resultiert in "default"-Gruppe
- [ ] State-basierte Filterung schraenkt Tools auf den aktuellen Agentenzustand ein
- [ ] Tool Service Descriptor kann per Konfiguration (ConfigService) registriert werden
- [ ] Mehrere Tool-Instanzen koennen denselben Tool Service mit unterschiedlicher Konfiguration referenzieren
- [ ] FlowDefinition kann mit Parameterwerten zu einer FlowInstance instanziiert werden
- [ ] Parameter-Defaults werden aus zentralen Definitionen aufgeloest
- [ ] `controlledBy`-Beziehung vererbt Parameterwerte im einfachen Modus
