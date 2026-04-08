# Feature 39: LLM Observability & Cost Tracking

## Problem

GraphMesh fuehrt im Hintergrund zahllose LLM-Aufrufe aus: Relationship-Extractor,
Definition-Extractor, Topic-Extractor, Ontology-guided Extractor, GraphRAG, DocRAG,
NLP Query Service, Agent-System, Streaming-Agent. Jeder dieser Aufrufe kostet Zeit,
Tokens und Geld -- aber es gibt bisher keine zentrale Sicht auf:

1. **Latenz pro Modell/Operation** (`p50`, `p95`, `p99`)
2. **Token-Verbrauch** (Prompt vs. Completion, getrennt nach Modell)
3. **Kosten** (aggregiert pro Zeitraum/Operation/Collection/Tenant)
4. **Fehlerraten** (Timeouts, Rate-Limits, Parsing-Fehler)
5. **Queue-Backlogs** (Kafka-Lags der Extraktoren)

Ohne diese Metriken sind Performance-Regressionen, Kosten-Explosionen und fehlerhafte
Modellwechsel nicht frueh genug erkennbar. Gerade nach Memory-Notizen zum
Embedding-Provider-Switch (dimension-in-name, similarityThreshold) waere ein
Dashboard wertvoll, das Trends sichtbar macht.

## Ziel

Einfuehrung einer Cross-Cutting Observability-Schicht fuer alle LLM-Aufrufe, exportiert
als Prometheus-Metriken und visualisiert in einem mitgelieferten Grafana-Dashboard.

1. **`LlmMetricsRecorder`** -- zentrale Bean, die jeder LLM-Aufruf aufruft (start/stop).
2. **Tagging** -- Jede Metrik traegt Labels: `model`, `operation`, `provider`, `collection`,
   `outcome` (`ok`/`error`/`timeout`/`rate_limited`).
3. **Micrometer + Prometheus Registry** -- bereits in Spring Boot vorhanden,
   nur explizit konfigurieren.
4. **Token-Zaehlung** -- aus Koog-`LLMResponse` (Prompt-/Completion-Tokens); fuer Modelle
   ohne Usage-Header Fallback: Tokenizer-Estimate.
5. **Cost-Model** -- Preis pro 1k Tokens aus `application.yml` (je Modell ein Eintrag).
6. **Error-Klassifikation** -- HTTP 429, 5xx, Parsing-Fehler, JSONL-Parsing-Misses,
   Tool-Binding-Fehler (Streaming-Agent).
7. **Kafka-Lag-Exporter** -- Gauges fuer Consumer-Lag pro Topic/Group.
8. **Grafana-Dashboard** -- JSON-Import in `ops/grafana/llm-observability.json`.

## Voraussetzungen

| Abhaengigkeit                                                        | Status    | Blocker? |
|----------------------------------------------------------------------|-----------|----------|
| Feature 05: LLM Provider Abstraction (`PromptExecutor`)              | Vorhanden | Ja       |
| Spring Boot Actuator + Micrometer Prometheus Registry                | Verfuegbar| Ja       |
| Alle LLM-nutzenden Services (#12, #18, #19, #21, #23, #25, #27, ...) | Vorhanden | Empfohlen|

## Architektur

### LlmMetricsRecorder

```kotlin
package com.agentwork.graphmesh.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Zentrale Metriken-Fassade fuer LLM-Aufrufe. Alle Services, die das LLM nutzen,
 * umhuellen ihren Aufruf in {@link #record}:
 *
 *     metrics.record("relationship-extractor", "gpt-4o") {
 *         val response = promptExecutor.execute(prompt, model)
 *         LlmCallOutcome(
 *             promptTokens = response.usage.promptTokens,
 *             completionTokens = response.usage.completionTokens
 *         )
 *     }
 */
@Component
class LlmMetricsRecorder(
    private val registry: MeterRegistry,
    private val costModel: LlmCostModel
) {

    fun <T> record(
        operation: String,
        model: String,
        provider: String = inferProvider(model),
        collection: String? = null,
        block: (Bucket) -> T
    ): T {
        val bucket = Bucket()
        val sample = Timer.start(registry)
        var outcome = "ok"
        return try {
            block(bucket)
        } catch (e: LlmRateLimitException) {
            outcome = "rate_limited"
            throw e
        } catch (e: LlmTimeoutException) {
            outcome = "timeout"
            throw e
        } catch (e: Exception) {
            outcome = "error"
            throw e
        } finally {
            val tags = Tags.of(
                "operation", operation,
                "model", model,
                "provider", provider,
                "outcome", outcome,
                "collection", collection ?: "_none_"
            )
            sample.stop(registry.timer("graphmesh.llm.latency", tags))
            registry.counter("graphmesh.llm.calls", tags).increment()
            if (bucket.promptTokens > 0) {
                registry.counter("graphmesh.llm.tokens.prompt", tags).increment(bucket.promptTokens.toDouble())
            }
            if (bucket.completionTokens > 0) {
                registry.counter("graphmesh.llm.tokens.completion", tags).increment(bucket.completionTokens.toDouble())
            }
            val costUsd = costModel.estimate(model, bucket.promptTokens, bucket.completionTokens)
            if (costUsd > 0.0) {
                registry.counter("graphmesh.llm.cost.usd", tags).increment(costUsd)
            }
        }
    }

    class Bucket {
        var promptTokens: Int = 0
        var completionTokens: Int = 0
    }

    private fun inferProvider(model: String): String = when {
        model.startsWith("gpt") || model.startsWith("text-embedding") -> "openai"
        model.startsWith("claude") -> "anthropic"
        else -> "ollama"
    }
}

class LlmRateLimitException(cause: Throwable) : RuntimeException(cause)
class LlmTimeoutException(cause: Throwable) : RuntimeException(cause)
```

### LlmCostModel

```kotlin
package com.agentwork.graphmesh.observability

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties("graphmesh.observability.cost")
class LlmCostModel {
    /** Preise pro 1k Tokens in USD. Konfigurierbar in application.yml. */
    var prompt: MutableMap<String, Double> = mutableMapOf()
    var completion: MutableMap<String, Double> = mutableMapOf()

    fun estimate(model: String, promptTokens: Int, completionTokens: Int): Double {
        val promptRate = prompt[model] ?: 0.0
        val compRate = completion[model] ?: 0.0
        return (promptTokens / 1000.0) * promptRate + (completionTokens / 1000.0) * compRate
    }
}
```

### Integration in bestehende Services

Beispiel: `DefinitionExtractorService` umhuellt den Koog-Aufruf:

```kotlin
val llmResponse = metrics.record("definition-extractor", modelName, collection = collectionId) { bucket ->
    val response = runBlocking { promptExecutor.execute(extractionPrompt, resolveLlmModel(modelName)) }
    bucket.promptTokens = response.usage?.promptTokens ?: estimateTokens(extractionPrompt)
    bucket.completionTokens = response.usage?.completionTokens ?: estimateTokens(response.first().content)
    response
}
```

Analog werden umgestellt:

- `RelationshipExtractorService`
- `DefinitionExtractorService`
- `TopicExtractorService` (Feature 38)
- `OntologyGuidedExtractor` (#21)
- `DocumentRagService`
- `GraphRagService`
- `NlpQueryService`
- `StreamingAgentServiceImpl`
- `AgentService`
- `EmbeddingService` (Provider-Label `embed`)

### KafkaLagExporter

```kotlin
package com.agentwork.graphmesh.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions
import org.apache.kafka.common.TopicPartition
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * Exportiert Consumer-Lag pro Topic/Group als Prometheus-Gauge.
 * Wird alle 15 Sekunden ueber einen Scheduler aktualisiert.
 */
@Component
class KafkaLagExporter(
    private val admin: AdminClient,
    private val registry: MeterRegistry
) {

    private val gauges = mutableMapOf<Pair<String, TopicPartition>, AtomicLong>()

    @Scheduled(fixedDelayString = "\${graphmesh.observability.kafka.lagIntervalMs:15000}")
    fun updateLags() {
        val groups = admin.listConsumerGroups().all().get()
            .map { it.groupId() }
            .filter { it.startsWith("graphmesh-") }

        for (groupId in groups) {
            val offsets = admin.listConsumerGroupOffsets(groupId, ListConsumerGroupOffsetsOptions())
                .partitionsToOffsetAndMetadata().get()
            val endOffsets = admin.listOffsets(offsets.keys.associateWith {
                org.apache.kafka.clients.admin.OffsetSpec.latest()
            }).all().get()

            offsets.forEach { (tp, meta) ->
                val end = endOffsets[tp]?.offset() ?: return@forEach
                val lag = (end - meta.offset()).coerceAtLeast(0)
                val key = groupId to tp
                val gauge = gauges.getOrPut(key) {
                    val holder = AtomicLong(0)
                    registry.gauge(
                        "graphmesh.kafka.consumer.lag",
                        Tags.of("group", groupId, "topic", tp.topic(), "partition", tp.partition().toString()),
                        holder
                    ) { it.get().toDouble() }!!
                    holder
                }
                gauge.set(lag)
            }
        }
    }
}
```

### Exportierte Metriken (Cheat Sheet)

| Metrik                                  | Typ      | Labels                                                 |
|-----------------------------------------|----------|--------------------------------------------------------|
| `graphmesh_llm_latency_seconds`         | Timer    | operation, model, provider, outcome, collection        |
| `graphmesh_llm_calls_total`             | Counter  | operation, model, provider, outcome, collection        |
| `graphmesh_llm_tokens_prompt_total`     | Counter  | operation, model, provider, outcome, collection        |
| `graphmesh_llm_tokens_completion_total` | Counter  | operation, model, provider, outcome, collection        |
| `graphmesh_llm_cost_usd_total`          | Counter  | operation, model, provider, outcome, collection        |
| `graphmesh_kafka_consumer_lag`          | Gauge    | group, topic, partition                                |

### Konfiguration (`application.yml`)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    distribution:
      percentiles-histogram:
        graphmesh.llm.latency: true
      percentiles:
        graphmesh.llm.latency: 0.5, 0.95, 0.99

graphmesh:
  observability:
    kafka:
      lagIntervalMs: 15000
    cost:
      prompt:
        gpt-4o: 0.0025
        gpt-4o-mini: 0.00015
        text-embedding-3-small: 0.00002
      completion:
        gpt-4o: 0.010
        gpt-4o-mini: 0.0006
```

### Grafana-Dashboard

Ein vorkonfiguriertes Dashboard liegt unter `ops/grafana/llm-observability.json`. Panels:

1. **LLM Latency p95 by operation** -- Timer-Histogramm.
2. **Calls/sec by model & outcome** -- Counter-Rate.
3. **Token Burn Rate** -- `rate(tokens_prompt + tokens_completion)`.
4. **Cost/hour by collection** -- `increase(cost_usd[1h])` gruppiert nach Collection.
5. **Error Rate** -- `calls{outcome!="ok"} / calls` pro Operation.
6. **Kafka Consumer Lag** -- Gauge je Group.
7. **Top-10 teuerste Collections in 24h** -- Table-Panel.

## Betroffene Dateien

### Backend

| Datei                                                                                             | Aenderung                                           |
|---------------------------------------------------------------------------------------------------|-----------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/observability/LlmMetricsRecorder.kt`                     | NEU - Timer/Counter-Fassade                         |
| `src/main/kotlin/com/agentwork/graphmesh/observability/LlmCostModel.kt`                           | NEU - Preistabelle pro Modell                       |
| `src/main/kotlin/com/agentwork/graphmesh/observability/LlmExceptions.kt`                          | NEU - `LlmRateLimitException`, `LlmTimeoutException`|
| `src/main/kotlin/com/agentwork/graphmesh/observability/KafkaLagExporter.kt`                       | NEU - Consumer-Lag-Gauge                            |
| `src/main/kotlin/com/agentwork/graphmesh/observability/TokenEstimator.kt`                         | NEU - Fallback-Tokenizer (grob: chars / 4)          |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/relationship/RelationshipExtractorService.kt` | UPDATE - Aufruf in `metrics.record { ... }` huellen |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorService.kt`     | UPDATE - dito                                       |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorService.kt`               | UPDATE - dito (Feature 38)                          |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/embedding/EmbeddingService.kt`                | UPDATE - `operation=embed`                          |
| `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt`                      | UPDATE - `operation=doc-rag`                        |
| `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`                       | UPDATE - `operation=graph-rag`                      |
| `src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt`                            | UPDATE - `operation=nlp-query`                      |
| `src/main/kotlin/com/agentwork/graphmesh/streaming/StreamingAgentServiceImpl.kt`                  | UPDATE - `operation=agent-stream`                   |
| `src/main/resources/application.yml`                                                              | UPDATE - Actuator + `graphmesh.observability.*`     |
| `build.gradle.kts`                                                                                | UPDATE - `micrometer-registry-prometheus` Dependency|

### Frontend

Nicht betroffen. Dashboard laeuft in Grafana (extern).

### Ops

| Datei                                 | Aenderung                                          |
|---------------------------------------|----------------------------------------------------|
| `ops/grafana/llm-observability.json`  | NEU - Dashboard-Export                             |
| `ops/docker-compose.yml`              | UPDATE - optional: Prometheus + Grafana Services   |
| `ops/prometheus/prometheus.yml`       | NEU - Scrape-Config fuer Spring Boot Actuator      |

### Tests

| Datei                                                                                       | Aenderung                                          |
|---------------------------------------------------------------------------------------------|----------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/observability/LlmMetricsRecorderTest.kt`           | NEU - Outcome-Klassifikation, Token-Counter        |
| `src/test/kotlin/com/agentwork/graphmesh/observability/LlmCostModelTest.kt`                 | NEU - Preisberechnung (Prompt + Completion)        |
| `src/test/kotlin/com/agentwork/graphmesh/observability/KafkaLagExporterTest.kt`             | NEU - Gauge-Update mit Fake AdminClient            |
| `src/test/kotlin/com/agentwork/graphmesh/observability/PrometheusEndpointTest.kt`           | NEU - Actuator-Endpoint liefert Metriken           |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                           |
|-------------------|-------------|-------------------------------------------------|
| Spring Boot (JVM) | Ja          | Micrometer/Actuator sind JVM-native             |
| KMP Library       | Nein        | Micrometer ist JVM-only                         |

## Akzeptanzkriterien

- [ ] `GET /actuator/prometheus` liefert alle sechs Metrik-Namen oben.
- [ ] Jeder LLM-nutzende Service ist in `LlmMetricsRecorder.record { ... }` gehuellt.
- [ ] Outcomes `ok`, `error`, `timeout`, `rate_limited` werden korrekt klassifiziert.
- [ ] Prompt- und Completion-Tokens werden aus der Koog-Response uebernommen, wenn verfuegbar.
- [ ] Fallback-Tokenizer liefert einen Schaetzwert, wenn die Response keine Usage-Daten hat.
- [ ] `graphmesh_llm_cost_usd_total` berechnet sich aus `LlmCostModel.estimate` und stimmt mit den konfigurierten Preisen ueberein.
- [ ] Kafka-Consumer-Lag wird alle 15s aktualisiert und pro Group/Topic/Partition als Gauge exportiert.
- [ ] Latency-Histogramm exportiert p50/p95/p99.
- [ ] Das mitgelieferte Grafana-Dashboard importiert ohne Fehler und zeigt alle Panels mit Live-Daten.
- [ ] Unit-Test verifiziert, dass ein Timeout-Wrap den Outcome `timeout` setzt und die Exception weiterwirft.
- [ ] Fehlende Modell-Eintraege in `cost.prompt`/`cost.completion` ergeben Kosten `0.0` ohne Exception.
