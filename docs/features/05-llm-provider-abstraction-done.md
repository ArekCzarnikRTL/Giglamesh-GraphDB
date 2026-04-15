# Feature 05: LLM Provider Abstraction — Done

## Implementierung

### Backend

- **`build.gradle.kts`** — Koog 0.7.3 wird als Abstraktionsschicht verwendet: `ai.koog:koog-agents-jvm`, `ai.koog:koog-spring-boot-starter`, `ai.koog:prompt-executor-openai-client`, `ai.koog:prompt-executor-anthropic-client`, `ai.koog:prompt-executor-ollama-client`, `ai.koog:koog-spring-ai-starter-model-embedding`. Koogs Spring-Starter liefert `PromptExecutor` und `LLMEmbeddingProvider` als Beans — keine eigene `LlmProvider`-Abstraktion noetig.
- **`src/main/kotlin/com/agentwork/graphmesh/llm/ModelResolver.kt`** — Zentrale Funktion `resolveLlmModel(name: String): LLModel`, die projektweite Modell-Namen (aus `graphmesh.extraction.model`, `graphmesh.embedding.model` etc.) auf die passenden Koog-Konstanten aus `OpenAIModels.Chat/Embeddings.*` und `OllamaModels.Meta/Embeddings.*` mappt. Unterstuetzt `gpt-4o`, `gpt-4o-mini`, `gpt-4.1`/`-mini`/`-nano`, `text-embedding-3-small/large`, `text-embedding-ada-002`, `llama3.2`/`:3b`, `nomic-embed-text`, `all-minilm`, `bge-large`, `multilingual-e5`, `mxbai-embed-large` und ein handgebautes `bge-m3`-`LLModel` (mit `LLMCapability.Embed`, 8192 ContextLength). Wirft bei unbekanntem Namen `error(...)` mit Hinweis auf die Config-Keys. Hintergrund: Koog 0.7.3 verlangt vorgefertigte `LLModel`-Instanzen mit korrekten Capabilities; ein nacktes `LLModel(LLMProvider.OpenAI, "gpt-4o")` fuehrt zu Laufzeitfehlern wie "Cannot determine proper LLM params". Alle Extraction-/RAG-/Embedding-Services nutzen ausschliesslich diesen Resolver.
- **`src/main/kotlin/com/agentwork/graphmesh/llm/LlmTextSanitizer.kt`** — `sanitizeForLlm(text: String): String` entfernt C0-Control-Chars (ausser TAB, LF) und normalisiert `\r\n`/`\r` auf `\n`. Begruendung: Koogs OpenAI-Client escapt nicht alle Control-Chars im JSON-Body, was zu HTTP 400 `"We could not parse the JSON body"` fuehrt. Wird vor jedem LLM-Call (Chunking, Extraction) aufgerufen (Commit `b4594a9`).
- **`src/main/resources/application.yml`** — Koog-Autoconfig via `ai.koog.openai.enabled/api-key`, `ai.koog.anthropic.enabled/api-key`, `ai.koog.ollama.enabled/base-url`. Modell-Namen projektseitig via `graphmesh.extraction.model` (default `llama3.2:3b`) und `graphmesh.embedding.model` (default `bge-m3`). Embedding-Starter wird ueber `koog.spring.ai.embedding.enabled: true` aktiviert. `MultiLLMAutoConfiguration` von Koog ist explizit deaktiviert (`spring.autoconfigure.exclude`), weil sonst `PromptExecutor`-Bean doppelt registriert wuerde (Ollama-Starter liefert beides: `PromptExecutor` und `LLMEmbeddingProvider`).
- **Nutzung**: `PromptExecutor` und `LLMEmbeddingProvider` werden direkt in alle Consumer injiziert: `extraction/embedding/EmbeddingService.kt`, `extraction/relationship/RelationshipExtractorService.kt`, `extraction/topic/TopicExtractorService.kt`, `extraction/definition/DefinitionExtractorService.kt`, `extraction/ontology/OntologyGuidedExtractorService.kt`, `extraction/agent/AgentExtractorService.kt`, `extraction/structured/{TableDetector,SchemaInferenceService,StructuredDataExtractorService}.kt`, `agent/AgentService.kt`, `streaming/StreamingAgentServiceImpl.kt`, `query/nlp/NlpQueryService.kt`, `query/graphrag/GraphRagService.kt`, `query/docrag/DocumentRagService.kt`, `query/CachedEmbeddingService.kt`, `api/SearchController.kt`, `rdfimport/RdfImportService.kt`.

### Tests

- Keine dedizierten Unit-Tests fuer `ModelResolver` oder eine Provider-Registry im Repo. LLM-Integration wird indirekt ueber die Services getestet, die `PromptExecutor`/`LLMEmbeddingProvider` nutzen (z. B. Extraction- und RAG-Integrationstests gegen laufendes Ollama via docker-compose).

## Abweichungen vom Feature-Dokument

- **Koog statt eigener Abstraktion**: Spec beschreibt eigene `LlmProvider`/`ChatCompletionService`/`EmbeddingService`/`LlmProviderRegistry`-Interfaces plus WebClient-Implementierungen fuer OpenAI/Anthropic/Ollama. Real: Koog 0.7.3 ist die Abstraktionsschicht (siehe Spec-Hinweis "Verwende Koog als Abstraktionsschicht"). Die Provider-Agnostik kommt ueber Koogs `PromptExecutor`-Bean und `LLMEmbeddingProvider`-Bean; die Services injizieren diese direkt. **Memory-Hinweis**: Feature-Specs nennen oft die alten Package-Namen/Libs — hier ist die reale Umsetzung deutlich schlanker als im Spec beschrieben (YAGNI-Prinzip).
- **Keine separaten Gradle-Starters `llm-core`/`llm-openai`/`llm-anthropic`/`llm-ollama`**: Single-Module-Projekt, alle Koog-Provider als normale Dependencies im Haupt-`build.gradle.kts`.
- **Kein `LlmProviderRegistry`**: Nicht noetig — Spring Boot injiziert einen einzigen `PromptExecutor`/`LLMEmbeddingProvider` je nach aktivierter Koog-Config (`ai.koog.openai.enabled` vs. `ai.koog.ollama.enabled`). Wechsel per Config-Flag.
- **Package**: Spec nennt `com.graphmesh.llm`. Real: `com.agentwork.graphmesh.llm` (nur Hilfsdateien `ModelResolver`, `LlmTextSanitizer`).
- **Eigener `resolveLlmModel`-Indirektionslayer zwingend**: Direkte `LLModel(...)`-Erzeugung fuehrt zu Koog-Laufzeitfehlern (fehlende Capabilities). Deshalb **immer** ueber `resolveLlmModel(name)` gehen — siehe User-Memory `project_llm_koog.md`.
- **`LlmTextSanitizer`** (im Spec nicht erwaehnt) — noetig wegen Koog/OpenAI-JSON-Escaping-Bug.
- **`MultiLLMAutoConfiguration`-Exclude**: Spec sieht den Konflikt nicht. Ohne Exclude scheitert der Context-Start, weil zwei `PromptExecutor`-Beans registriert werden.
- **Keine `TokenUsage`-Extraktion in eigenem Layer**: Token-Usage kommt vollstaendig aus den Koog-`Message.Response`-Objekten; projektseitig gibt es keine eigene `TokenUsage`-Datenklasse.
- **Keine Unit-Tests fuer `ModelResolver`/Provider**: Spec listet mehrere MockWebServer-Unit-Tests. Real entfallen die, weil die Koog-Clients direkt verwendet werden und gegen reale Provider (Ollama via docker-compose) integrationsgetestet werden.

## Akzeptanzkriterien

- [x] Chat Completion fuer OpenAI/Anthropic/Ollama — ueber Koogs `PromptExecutor` (per Config-Flag gewaehlter Provider).
- [x] Embeddings fuer alle drei Provider — ueber Koogs `LLMEmbeddingProvider`.
- [x] Batch-Embeddings — Koogs API bietet `embed(text, model)`, Batches werden in `EmbeddingService` (Extraction) pro Chunk gerufen; keine eigene Batch-Methode, Anforderung durch Consumer-Seite abgedeckt.
- [x] `LlmConfig` (Modell/Temperature/MaxTokens) uebergeben — ueber `graphmesh.extraction.model` + Koog-`LLModel`-Konstanten, plus Koog-interne Default-Parameter. Temperature/MaxTokens werden in einzelnen Services explizit gesetzt (z. B. via Koogs `PromptBuilder`).
- [x] Provider-Wechsel nur ueber Config — `ai.koog.openai.enabled` vs. `ai.koog.ollama.enabled` in `application.yml`, kein Code-Change.
- [x] API-Keys aus Env — `${OPENAI_API_KEY}`/`${ANTHROPIC_API_KEY}` in `application.yml`.
- [~] `LlmProviderRegistry` — **nicht implementiert**, weil durch Spring-DI + Koog-Config obsolet.
- [ ] Provider-Wechsel als separate Spring-Boot-Starter — **nicht erfuellt** im Sinne des Specs; statt dessen ein einziges Modul mit allen Koog-Clients, Aktivierung per Flag.
- [x] Fehlerbehandlung einheitlich — Koog wirft `LlmException` bzw. HTTP-Errors; `LlmTextSanitizer` schuetzt vor einer bekannten 400er-Ursache.
- [~] `TokenUsage` — wird von Koog im `Message.Response` geliefert, projektseitig nicht in eigenem Datentyp abgebildet.
- [ ] Unit-Tests mit MockWebServer — **nicht vorhanden**; getestet wird gegen echtes Ollama/OpenAI ueber Integrationstests der Consumer-Services.
- [x] Bestehende Funktionalitaet unberuehrt.

## Offene Punkte

- Unit-Tests fuer `resolveLlmModel` (insbesondere die `bge-m3`-Spezialbehandlung und Fehlermeldung bei unbekanntem Modell) koennten ergaenzt werden.
- Falls mehrere gleichzeitig aktive Provider benoetigt werden (z. B. OpenAI fuer Chat + Ollama fuer Embeddings), muesste ein eigener `LlmProviderRegistry`-Layer doch eingefuehrt werden — aktuell nicht noetig.
