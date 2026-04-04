# Feature 05: LLM Provider Abstraction

## Problem

GraphMesh muss verschiedene LLM-Provider (OpenAI, Anthropic, Ollama) fuer Chat Completion und Embedding-Generierung
unterstuetzen. Ohne Abstraktionsschicht muesste jeder Service direkt mit Provider-spezifischen APIs arbeiten, was zu
Vendor Lock-in, inkonsistenter Fehlerbehandlung und schwieriger Testbarkeit fuehrt. Ein Providerwechsel wuerde
umfangreiche Code-Aenderungen erfordern. 

Wichtig:
Verwende Koog als Abstraktionsschicht.

## Ziel

Bereitstellung einer provider-agnostischen LLM-Schnittstelle mit austauschbaren Implementierungen als separate Spring
Boot Starters.

2. **ChatCompletionService** -- Text-in/Text-out mit konfigurierbaren Parametern (Modell, Temperatur, Max-Tokens)
3. **EmbeddingService** -- Text-in/Vector-out fuer Embedding-Generierung
4. **Provider-Implementierungen** -- OpenAI, Anthropic und Ollama als separate Spring Boot Starters
5. **Konfigurierbare Defaults** -- Modell, Temperatur und Max-Tokens ueber application.yml steuerbar

## Voraussetzungen

| Abhaengigkeit           | Status     | Blocker? |
|-------------------------|------------|----------|
| Spring Boot 3.x         | Verfuegbar | Nein     |
| OpenAI API (extern)     | Verfuegbar | Nein     |
| Anthropic API (extern)  | Verfuegbar | Nein     |
| Ollama (lokal)          | Verfuegbar | Nein     |
| Jackson (JSON)          | Verfuegbar | Nein     |
| Spring WebClient (HTTP) | Verfuegbar | Nein     |

## Architektur

### Core Interfaces

```kotlin
package com.graphmesh.llm

/**
 * Konfiguration fuer einen LLM-Provider.
 */
data class LlmConfig(
    val model: String,
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val topP: Double? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null
)

/**
 * Eine Chat-Nachricht mit Rolle und Inhalt.
 */
data class ChatMessage(
    val role: ChatRole,
    val content: String
)

enum class ChatRole {
    SYSTEM, USER, ASSISTANT
}

/**
 * Ergebnis einer Chat Completion.
 */
data class ChatCompletionResult(
    val content: String,
    val model: String,
    val usage: TokenUsage? = null,
    val finishReason: String? = null
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

/**
 * Ergebnis einer Embedding-Generierung.
 */
data class EmbeddingResult(
    val vector: FloatArray,
    val model: String,
    val usage: TokenUsage? = null
) {
    val dimension: Int get() = vector.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingResult) return false
        return vector.contentEquals(other.vector) && model == other.model
    }

    override fun hashCode(): Int = vector.contentHashCode()
}
```

### LlmProvider Interface

```kotlin
package com.graphmesh.llm

/**
 * Provider-agnostische Schnittstelle fuer LLM-Operationen.
 * Jeder Provider (OpenAI, Anthropic, Ollama) implementiert dieses Interface.
 */
interface LlmProvider {
    val name: String

    fun chatCompletion(): ChatCompletionService
    fun embedding(): EmbeddingService
}

/**
 * Chat Completion: Messages rein, Text raus.
 */
interface ChatCompletionService {

    /**
     * Fuehrt eine Chat Completion mit den uebergebenen Nachrichten aus.
     */
    suspend fun complete(
        messages: List<ChatMessage>,
        config: LlmConfig? = null
    ): ChatCompletionResult

    /**
     * Convenience: Einzelne Prompt-Nachricht mit optionalem System-Prompt.
     */
    suspend fun complete(
        prompt: String,
        systemPrompt: String? = null,
        config: LlmConfig? = null
    ): ChatCompletionResult {
        val messages = buildList {
            systemPrompt?.let { add(ChatMessage(ChatRole.SYSTEM, it)) }
            add(ChatMessage(ChatRole.USER, prompt))
        }
        return complete(messages, config)
    }
}

/**
 * Embedding-Generierung: Text rein, Vektor raus.
 */
interface EmbeddingService {

    /**
     * Generiert einen Embedding-Vektor fuer einen einzelnen Text.
     */
    suspend fun embed(text: String, model: String? = null): EmbeddingResult

    /**
     * Generiert Embedding-Vektoren fuer mehrere Texte (Batch).
     */
    suspend fun embedBatch(texts: List<String>, model: String? = null): List<EmbeddingResult>
}
```

### Provider Registry

```kotlin
package com.graphmesh.llm

import org.slf4j.LoggerFactory

/**
 * Registry fuer alle konfigurierten LLM-Provider.
 * Ermoeglicht den Zugriff auf Provider nach Name.
 */
class LlmProviderRegistry(
    private val providers: Map<String, LlmProvider>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Gibt den Provider mit dem angegebenen Namen zurueck.
     */
    fun provider(name: String): LlmProvider =
        providers[name] ?: throw IllegalArgumentException(
            "LLM-Provider '$name' nicht konfiguriert. Verfuegbar: ${providers.keys}"
        )

    /**
     * Gibt den Standard-Provider zurueck (erster konfigurierter).
     */
    fun defaultProvider(): LlmProvider =
        providers.values.firstOrNull()
            ?: throw IllegalStateException("Kein LLM-Provider konfiguriert")

    /**
     * Listet alle verfuegbaren Provider-Namen.
     */
    fun availableProviders(): Set<String> = providers.keys
}
```

### OpenAI Starter (Beispiel)

```kotlin
package com.graphmesh.llm.openai

import com.graphmesh.llm.*
import org.springframework.web.reactive.function.client.WebClient

class OpenAiProvider(
    private val webClient: WebClient,
    private val defaultConfig: LlmConfig
) : LlmProvider {

    override val name: String = "openai"

    override fun chatCompletion(): ChatCompletionService =
        OpenAiChatCompletionService(webClient, defaultConfig)

    override fun embedding(): EmbeddingService =
        OpenAiEmbeddingService(webClient)
}

class OpenAiChatCompletionService(
    private val webClient: WebClient,
    private val defaultConfig: LlmConfig
) : ChatCompletionService {

    override suspend fun complete(
        messages: List<ChatMessage>,
        config: LlmConfig?
    ): ChatCompletionResult {
        val effectiveConfig = config ?: defaultConfig
        // POST /v1/chat/completions
        // ... WebClient-Aufruf
        TODO("OpenAI API-Aufruf implementieren")
    }
}

class OpenAiEmbeddingService(
    private val webClient: WebClient
) : EmbeddingService {

    override suspend fun embed(text: String, model: String?): EmbeddingResult {
        // POST /v1/embeddings
        // ... WebClient-Aufruf
        TODO("OpenAI Embedding API-Aufruf implementieren")
    }

    override suspend fun embedBatch(texts: List<String>, model: String?): List<EmbeddingResult> {
        // Batch-Aufruf oder Einzelaufrufe
        return texts.map { embed(it, model) }
    }
}
```

### Spring Boot Auto-Configuration (OpenAI Starter)

```kotlin
package com.graphmesh.llm.openai.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

@ConfigurationProperties(prefix = "graphmesh.llm.openai")
data class OpenAiProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://api.openai.com",
    val model: String = "gpt-4o",
    val embeddingModel: String = "text-embedding-3-small",
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val timeout: Long = 60000
)

@AutoConfiguration
@ConditionalOnProperty(prefix = "graphmesh.llm.openai", name = ["api-key"])
class OpenAiAutoConfiguration {
    // ... Bean-Definitionen fuer OpenAiProvider
}
```

### application.yml Beispiel

```yaml
graphmesh:
  llm:
    default-provider: openai
    openai:
      api-key: ${OPENAI_API_KEY}
      model: gpt-4o
      embedding-model: text-embedding-3-small
      temperature: 0.7
      max-tokens: 4096
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      model: claude-sonnet-4-20250514
      max-tokens: 4096
    ollama:
      base-url: http://localhost:11434
      model: llama3
      embedding-model: nomic-embed-text
```

## Betroffene Dateien

### Backend

| Datei                                                                                                   | Aenderung                         |
|---------------------------------------------------------------------------------------------------------|-----------------------------------|
| `llm-core/src/main/kotlin/com/graphmesh/llm/LlmProvider.kt`                                             | NEU - Provider-Interface          |
| `llm-core/src/main/kotlin/com/graphmesh/llm/ChatCompletionService.kt`                                   | NEU - Chat-Completion-Interface   |
| `llm-core/src/main/kotlin/com/graphmesh/llm/EmbeddingService.kt`                                        | NEU - Embedding-Interface         |
| `llm-core/src/main/kotlin/com/graphmesh/llm/LlmConfig.kt`                                               | NEU - Konfigurationsmodell        |
| `llm-core/src/main/kotlin/com/graphmesh/llm/ChatMessage.kt`                                             | NEU - Chat-Datenmodelle           |
| `llm-core/src/main/kotlin/com/graphmesh/llm/LlmProviderRegistry.kt`                                     | NEU - Provider-Registry           |
| `llm-core/build.gradle.kts`                                                                             | NEU - Core-Modul (nur Interfaces) |
| `llm-openai/src/main/kotlin/com/graphmesh/llm/openai/OpenAiProvider.kt`                                 | NEU - OpenAI-Implementierung      |
| `llm-openai/src/main/kotlin/com/graphmesh/llm/openai/OpenAiChatCompletionService.kt`                    | NEU - Chat-Service                |
| `llm-openai/src/main/kotlin/com/graphmesh/llm/openai/OpenAiEmbeddingService.kt`                         | NEU - Embedding-Service           |
| `llm-openai/src/main/kotlin/com/graphmesh/llm/openai/autoconfigure/OpenAiAutoConfiguration.kt`          | NEU - Auto-Configuration          |
| `llm-openai/build.gradle.kts`                                                                           | NEU - OpenAI-Starter-Modul        |
| `llm-anthropic/src/main/kotlin/com/graphmesh/llm/anthropic/AnthropicProvider.kt`                        | NEU - Anthropic-Implementierung   |
| `llm-anthropic/src/main/kotlin/com/graphmesh/llm/anthropic/autoconfigure/AnthropicAutoConfiguration.kt` | NEU - Auto-Configuration          |
| `llm-anthropic/build.gradle.kts`                                                                        | NEU - Anthropic-Starter-Modul     |
| `llm-ollama/src/main/kotlin/com/graphmesh/llm/ollama/OllamaProvider.kt`                                 | NEU - Ollama-Implementierung      |
| `llm-ollama/src/main/kotlin/com/graphmesh/llm/ollama/autoconfigure/OllamaAutoConfiguration.kt`          | NEU - Auto-Configuration          |
| `llm-ollama/build.gradle.kts`                                                                           | NEU - Ollama-Starter-Modul        |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                    | Aenderung                                   |
|------------------------------------------------------------------------------------------|---------------------------------------------|
| `llm-core/src/test/kotlin/com/graphmesh/llm/LlmProviderRegistryTest.kt`                  | NEU - Registry-Tests                        |
| `llm-openai/src/test/kotlin/com/graphmesh/llm/openai/OpenAiProviderTest.kt`              | NEU - OpenAI-Unit-Tests (mit MockWebServer) |
| `llm-openai/src/test/kotlin/com/graphmesh/llm/openai/OpenAiChatCompletionServiceTest.kt` | NEU - Chat-Completion-Tests                 |
| `llm-openai/src/test/kotlin/com/graphmesh/llm/openai/OpenAiEmbeddingServiceTest.kt`      | NEU - Embedding-Tests                       |
| `llm-anthropic/src/test/kotlin/com/graphmesh/llm/anthropic/AnthropicProviderTest.kt`     | NEU - Anthropic-Unit-Tests                  |
| `llm-ollama/src/test/kotlin/com/graphmesh/llm/ollama/OllamaProviderTest.kt`              | NEU - Ollama-Unit-Tests                     |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                                       |
|-------------------|-------------|-------------------------------------------------------------|
| Spring Boot (JVM) | Ja          | WebClient und alle Provider-SDKs verfuegbar                 |
| KMP Library       | Nein        | Provider-SDKs sind JVM-only, WebClient nicht KMP-kompatibel |
| Ktor/Wasm         | Nein        | Kein HTTP-Client fuer Wasm mit Coroutines-Unterstuetzung    |

## Akzeptanzkriterien

- [ ] `ChatCompletionService.complete()` sendet Nachrichten und empfaengt Text-Antwort fuer alle drei Provider
- [ ] `EmbeddingService.embed()` generiert korrekte Vektoren fuer alle drei Provider
- [ ] `EmbeddingService.embedBatch()` verarbeitet mehrere Texte effizient
- [ ] `LlmConfig` (Modell, Temperatur, Max-Tokens) wird korrekt an den Provider uebergeben
- [ ] `LlmProviderRegistry` ermoeglicht Zugriff auf Provider nach Name
- [ ] Provider-Wechsel erfordert nur Konfigurationsaenderung, keinen Code-Change
- [ ] Jeder Provider ist ein separater Spring Boot Starter (nur bei Dependency im Classpath aktiv)
- [ ] API-Keys werden sicher aus Environment-Variablen gelesen
- [ ] Fehlerbehandlung: Timeouts, Rate-Limits und API-Fehler werden einheitlich als Exceptions geworfen
- [ ] `TokenUsage` wird korrekt aus der Provider-Antwort extrahiert
- [ ] Unit-Tests mit MockWebServer verifizieren korrekte API-Aufrufe
- [ ] Bestehende Funktionalitaet bleibt unberuehrt
