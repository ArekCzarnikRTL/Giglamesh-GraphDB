# Feature 05: LLM Provider Abstraction — Design Spec

## Entscheidung

Koog (JetBrains Kotlin AI Agent Framework) wird als LLM-Abstraktionsschicht direkt integriert. Keine eigenen Wrapper-Interfaces, keine Gradle-Submodule. Koog's `PromptExecutor` und `AIAgent` werden von downstream Features direkt genutzt.

## Dependencies

```kotlin
val koogVersion = "0.7.3"

// Koog Core + Spring Boot Auto-Config
implementation("ai.koog:koog-agents-jvm:$koogVersion")
implementation("ai.koog:koog-spring-boot-starter:$koogVersion")

// Provider-Clients (alle group ai.koog, auf Maven Central)
implementation("ai.koog:prompt-executor-openai-client:$koogVersion")
implementation("ai.koog:prompt-executor-anthropic-client:$koogVersion")
implementation("ai.koog:prompt-executor-ollama-client:$koogVersion")

// Embeddings via Spring AI Bridge
implementation("ai.koog:koog-spring-ai-starter-model-embedding:$koogVersion")
```

## Konfiguration

```yaml
# application.yml
ai:
  koog:
    openai:
      enabled: true
      api-key: ${OPENAI_API_KEY:}
    anthropic:
      enabled: true
      api-key: ${ANTHROPIC_API_KEY:}
    ollama:
      enabled: true
      base-url: http://localhost:11434

koog:
  spring:
    ai:
      embedding:
        enabled: true
```

Provider werden per `enabled: true` + vorhandenem API-Key aktiviert. Der `koog-spring-boot-starter` erzeugt automatisch `PromptExecutor`-Beans und die `MultiLLMPromptExecutor` Registry.

## Nutzung

### Chat Completion (via PromptExecutor)

```kotlin
@Service
class SomeExtractor(private val promptExecutor: PromptExecutor) {
    suspend fun extract(text: String): String {
        val p = prompt("extract") {
            system("You are an RDF triple extractor...")
            user(text)
        }
        val result = promptExecutor.execute(p, OpenAIModels.Chat.GPT4o)
        return result.first().content
    }
}
```

### Embeddings (via Spring AI EmbeddingModel)

```kotlin
@Service
class ChunkEmbedder(private val embeddingModel: EmbeddingModel) {
    fun embed(texts: List<String>): List<FloatArray> {
        return embeddingModel.embed(texts).map { it.output }
    }
}
```

### Agents (via AIAgent — fuer spaetere Features)

```kotlin
val agent = AIAgent(
    promptExecutor = promptExecutor,
    llmModel = AnthropicModels.Sonnet_4_5,
    systemPrompt = "You are a knowledge extraction agent."
)
val result = agent.run(userMessage)
```

## Was NICHT gebaut wird

- Keine eigenen Interfaces (ChatCompletionService, EmbeddingService, LlmProvider)
- Keine LlmProviderRegistry — MultiLLMPromptExecutor uebernimmt das
- Keine Gradle-Submodule — alles im Haupt-Build
- Keine Auto-Configuration Klassen — koog-spring-boot-starter uebernimmt das

## Test-Strategie

- **Unit-Tests**: Mock `PromptExecutor` (Interface, einfach mockbar)
- **Integration-Test**: Verifiziert dass Auto-Configuration Beans erzeugt mit aktiven Providern
- **application-test.yml**: Deaktiviert Provider die externen API-Key brauchen

```yaml
# application-test.yml (Ergaenzung)
ai:
  koog:
    openai:
      enabled: false
    anthropic:
      enabled: false
    ollama:
      enabled: false

koog:
  spring:
    ai:
      embedding:
        enabled: false
```

## Betroffene Dateien

| Datei | Aenderung |
|---|---|
| `build.gradle.kts` | Koog Dependencies hinzufuegen |
| `application.yml` | `ai.koog.*` Provider-Config |
| `application-test.yml` | Provider deaktiviert fuer Tests |

## Downstream-Abhaengigkeiten

Features die `PromptExecutor` nutzen werden:
- Feature 12: Relationship Extractor
- Feature 13: Document Embeddings
- Feature 15: Graph RAG
- Feature 16: Document RAG
- Feature 17: MCP Tool Interface
- Feature 19: Definition Extractor
- Feature 24: Agent-based Extractor
- Feature 25: Agent System
