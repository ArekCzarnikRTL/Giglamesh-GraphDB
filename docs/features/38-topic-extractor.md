# Feature 38: Topic Extractor

## Problem

GraphMesh extrahiert derzeit zwei Arten von Wissen aus Textchunks:

- **Relationships** (Feature 12) -- Triples zwischen Entitaeten (`A arbeitet bei B`)
- **Definitions** (Feature 19) -- beschreibende Aussagen ueber einzelne Entitaeten

Was fehlt, ist eine **themenzentrierte Sicht**: worum *geht es* in einem Dokument/Chunk?
Themen ("Topics") sind keine Entitaeten, sondern abstrakte Konzepte -- "Insolvenzrecht",
"Photosynthese", "Wertpapierregulierung in der EU". Sie sind extrem hilfreich fuer:

1. **Dokumenten-Clustering** -- "zeige mir alle Chunks zum Thema X"
2. **Facettierte Suche** -- Topics als Query-Filter neben Volltext
3. **GraphRAG-Navigation** -- Topics als Einstiegspunkte in den Graphen
4. **Summarization** -- "Worum geht es in Collection Y insgesamt?"

XGraph hat hierfuer einen dedizierten `kg-extract-topics`-Prozessor. In GraphMesh
existiert das Konzept bislang nicht.

## Ziel

Implementierung eines `TopicExtractorService`, der Textchunks auf Themen untersucht und
diese als `(topic, rdf:type, skos:Concept)` plus `(chunk, dct:subject, topic)` im
Knowledge Graph verankert.

1. **Kafka-Consumer** -- Empfaengt `graphmesh.chunk.created`-Events (analog zu #12/#19).
2. **LLM-basierte Themenextraktion** -- Nutzt `PromptExecutor` + `resolveLlmModel` aus
   Feature 05, liefert JSONL mit `{topic, confidence, rationale}`.
3. **SKOS-konforme Speicherung** -- Topics als `skos:Concept`-Entities, Chunks via
   `dct:subject` verlinkt.
4. **Deduplikation** -- Gleiche Topic-Label (case/whitespace-normalisiert) fuehren zur
   selben `EntityIdGenerator`-ID.
5. **Provenance** -- Jede Topic-Zuordnung traegt ein Evidenz-Quad im `SOURCE`-Graph.

## Voraussetzungen

| Abhaengigkeit                                                             | Status    | Blocker? |
|---------------------------------------------------------------------------|-----------|----------|
| Feature 01: Kafka Messaging Infrastructure                                | Vorhanden | Ja       |
| Feature 05: LLM Provider Abstraction (`PromptExecutor`, `resolveLlmModel`)| Vorhanden | Ja       |
| Feature 07: RDF Graph Model (`Quad`, `RdfTerm`, `EntityIdGenerator`)      | Vorhanden | Ja       |
| Feature 11: Document Chunker (`graphmesh.chunk.created`)                  | Vorhanden | Ja       |
| Feature 29: Extraction-Time Provenance (`ProvenanceService`)              | Vorhanden | Ja       |

## Architektur

### Datenklassen

```kotlin
package com.agentwork.graphmesh.extraction.topic

data class TopicResult(
    val topic: String,
    val confidence: Double,  // 0.0..1.0
    val rationale: String? = null
)

data class TopicExtractionResult(
    val chunkId: String,
    val topicsExtracted: Int,
    val topics: List<String>
)
```

### Prompt-Template

```kotlin
package com.agentwork.graphmesh.extraction.topic

object TopicPromptTemplate {

    fun systemPrompt(): String = """
        Du bist ein Wissensextraktions-Assistent. Deine Aufgabe ist es,
        die **Themen** zu identifizieren, die einen Text inhaltlich pragen.

        Themen sind abstrakte Konzepte oder Sachgebiete -- NICHT einzelne
        Entitaeten. Beispiele:
          richtig:  "Insolvenzrecht", "Photosynthese", "EU-Datenschutz"
          falsch:   "Angela Merkel", "Berlin", "BMW AG" (das sind Entitaeten)

        Extrahiere fuer jedes Thema ein JSON-Objekt pro Zeile im JSONL-Format:
          {"topic": "<Thema>", "confidence": <0.0..1.0>, "rationale": "<kurzer Grund>"}

        Regeln:
          - Maximal 5 Themen pro Text, nur die wichtigsten.
          - `confidence` spiegelt wider, wie deutlich das Thema im Text auftritt.
          - `rationale` ist ein kurzer Halbsatz (max. 10 Woerter).
          - Verwende kanonische, wiederverwendbare Bezeichnungen.
          - KEINE Entitaeten, Personen, Orte, Firmen.
          - Jedes JSON-Objekt auf einer eigenen Zeile, kein Markdown.
    """.trimIndent()

    fun userPrompt(chunkText: String): String = """
        Extrahiere die Themen aus folgendem Text:

        ---
        $chunkText
        ---

        Antworte NUR mit JSON-Objekten im JSONL-Format, eines pro Zeile.
    """.trimIndent()
}
```

### TopicExtractorService

```kotlin
package com.agentwork.graphmesh.extraction.topic

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.llm.resolveLlmModel
import com.agentwork.graphmesh.provenance.ProvenanceService
import com.agentwork.graphmesh.provenance.SubgraphProvenance
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.Quad
import com.agentwork.graphmesh.rdf.QuadConverter
import com.agentwork.graphmesh.rdf.RdfTerm
import com.agentwork.graphmesh.storage.QuadStore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class TopicExtractorService(
    private val promptExecutor: PromptExecutor,
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService,
    private val provenanceService: ProvenanceService,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val modelName: String,
    @Value("\${graphmesh.extraction.topic.minConfidence:0.5}") private val minConfidence: Double
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    companion object {
        private const val RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
        private const val RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label"
        private const val SKOS_CONCEPT = "http://www.w3.org/2004/02/skos/core#Concept"
        private const val DCT_SUBJECT = "http://purl.org/dc/terms/subject"
        private const val TOPIC_CONFIDENCE = "http://graphmesh.io/ontology/topicConfidence"
    }

    fun extract(chunkId: String, collectionId: String): TopicExtractionResult {
        val content = librarianService.getContent(chunkId)
        val chunkText = String(content, Charsets.UTF_8)
        if (chunkText.isBlank()) {
            return TopicExtractionResult(chunkId, 0, emptyList())
        }

        val extractionPrompt = prompt("topic-extraction") {
            system(TopicPromptTemplate.systemPrompt())
            user(TopicPromptTemplate.userPrompt(chunkText))
        }

        val llmResponse = runBlocking {
            promptExecutor.execute(extractionPrompt, resolveLlmModel(modelName))
        }
        val responseText = llmResponse.first().content

        val topics = parseJsonlTopics(responseText)
            .filter { it.confidence >= minConfidence }
            .distinctBy { normalize(it.topic) }

        if (topics.isEmpty()) {
            logger.debug("No topics extracted from chunk {}", chunkId)
            return TopicExtractionResult(chunkId, 0, emptyList())
        }

        val chunkUri = RdfTerm.Uri("urn:chunk:$chunkId")
        val knowledgeQuads = mutableListOf<Quad>()

        for (t in topics) {
            val topicId = EntityIdGenerator.generate(normalize(t.topic))

            // topic rdf:type skos:Concept
            knowledgeQuads += Quad(topicId, RdfTerm.Uri(RDF_TYPE), RdfTerm.Uri(SKOS_CONCEPT), NamedGraph.DEFAULT)
            // topic rdfs:label "<Original>"
            knowledgeQuads += Quad(topicId, RdfTerm.Uri(RDFS_LABEL), RdfTerm.Literal(t.topic), NamedGraph.DEFAULT)
            // chunk dct:subject topic
            knowledgeQuads += Quad(chunkUri, RdfTerm.Uri(DCT_SUBJECT), topicId, NamedGraph.DEFAULT)
            // quoted triple mit Confidence
            val assignment = Quad(chunkUri, RdfTerm.Uri(DCT_SUBJECT), topicId, NamedGraph.DEFAULT)
            knowledgeQuads += Quad(
                subject = RdfTerm.QuotedTriple(assignment.triple),
                predicate = RdfTerm.Uri(TOPIC_CONFIDENCE),
                objectTerm = RdfTerm.Literal(t.confidence.toString()),
                graph = NamedGraph.DEFAULT
            )
        }

        val dedupedKnowledge = knowledgeQuads.distinctBy {
            "${it.subject.toNTriples()}|${it.predicate.toNTriples()}|${it.objectTerm.toNTriples()}|${it.graph}"
        }

        val provenanceQuads = provenanceService.buildSubgraphQuads(
            SubgraphProvenance(
                extractedTriples = dedupedKnowledge.map { it.triple },
                chunkUri = "urn:chunk:$chunkId",
                agentLabel = "TopicExtractor",
                modelName = modelName
            )
        )

        val allStored = dedupedKnowledge.map { QuadConverter.toStoredQuad(it) } +
            provenanceQuads.map { QuadConverter.toStoredQuad(it) }
        quadStore.insertBatch(collectionId, allStored)

        logger.info("Extracted {} topics from chunk {}", topics.size, chunkId)
        return TopicExtractionResult(
            chunkId = chunkId,
            topicsExtracted = topics.size,
            topics = topics.map { it.topic }
        )
    }

    private fun normalize(topic: String): String =
        topic.trim().lowercase().replace(Regex("\\s+"), " ")

    internal fun parseJsonlTopics(llmResponse: String): List<TopicResult> =
        llmResponse.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val map = objectMapper.readValue<Map<String, Any>>(line)
                    val topic = (map["topic"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val confidence = (map["confidence"] as? Number)?.toDouble() ?: 1.0
                    val rationale = map["rationale"] as? String
                    TopicResult(topic, confidence.coerceIn(0.0, 1.0), rationale)
                } catch (_: Exception) {
                    null
                }
            }
}
```

### TopicExtractorConsumer

```kotlin
package com.agentwork.graphmesh.extraction.topic

import com.agentwork.graphmesh.librarian.DocumentNotFoundException
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class TopicExtractorConsumer(
    private val extractorService: TopicExtractorService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.chunk.created"], groupId = "graphmesh-topic-extractor")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val chunkId = value["chunkId"].toString()
        val collectionId = value["collectionId"].toString()

        try {
            val result = extractorService.extract(chunkId, collectionId)
            logger.info("Topic extraction complete: chunkId={}, topics={}", chunkId, result.topicsExtracted)
        } catch (e: DocumentNotFoundException) {
            logger.info("Skip topic extraction for chunk {}: deleted before processing", chunkId)
        } catch (e: Exception) {
            logger.error("Topic extraction failed for chunk {}", chunkId, e)
        }
    }
}
```

### Kafka-Topics

| Topic                     | Richtung  | Schema              |
|---------------------------|-----------|---------------------|
| `graphmesh.chunk.created` | Eingehend | `ChunkCreatedEvent` |

Die Extraktion benoetigt kein eigenes Output-Topic; die Quads fliessen direkt in
Cassandra, und nachgelagerte Dienste (GraphRAG, Explainability) finden sie ueber den
`QuadStore`.

### Konfiguration

```yaml
graphmesh:
  extraction:
    model: gpt-4o
    topic:
      minConfidence: 0.5    # Topics darunter werden verworfen
      maxTopicsPerChunk: 5  # LLM-seitig im Prompt ebenfalls genannt
```

## Betroffene Dateien

### Backend

| Datei                                                                                    | Aenderung                                    |
|------------------------------------------------------------------------------------------|----------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorService.kt`      | NEU - LLM-basierte Topic-Extraktion          |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorConsumer.kt`     | NEU - Kafka-Consumer auf `chunk.created`     |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicPromptTemplate.kt`        | NEU - Prompt mit Topic-vs-Entity-Regeln      |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorModels.kt`       | NEU - `TopicResult`, `TopicExtractionResult` |
| `src/main/resources/application.yml`                                                     | UPDATE - `graphmesh.extraction.topic.*`      |

### Frontend

| Datei                                                             | Aenderung                                          |
|-------------------------------------------------------------------|----------------------------------------------------|
| `frontend/src/components/query/TopicFacet.tsx`                    | NEU - Topics als Filter in der Query-UI            |
| `frontend/src/app/query/page.tsx`                                 | UPDATE - Topic-Facet in Seitenleiste einbinden     |

### Tests

| Datei                                                                                        | Aenderung                                       |
|----------------------------------------------------------------------------------------------|-------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/extraction/topic/TopicExtractorServiceTest.kt`      | NEU - Unit-Tests (Fake PromptExecutor, Quads)   |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/topic/TopicPromptTemplateTest.kt`        | NEU - Template-Konsistenz                       |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/topic/TopicJsonlParsingTest.kt`          | NEU - JSONL-Edge-Cases (Truncation, ungueltig)  |
| `src/test/kotlin/com/agentwork/graphmesh/extraction/topic/TopicDeduplicationTest.kt`         | NEU - Normalisierung + Deduplikation            |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                        |
|-------------------|-------------|----------------------------------------------|
| Spring Boot (JVM) | Ja          | Koog, Kafka, Cassandra-Clients sind JVM-only |

## Akzeptanzkriterien

- [ ] Consumer verarbeitet `graphmesh.chunk.created`-Events und extrahiert Topics pro Chunk.
- [ ] LLM-Antwort wird als JSONL geparst; ungueltige Zeilen werden stillschweigend uebersprungen.
- [ ] Topics mit `confidence < minConfidence` werden verworfen.
- [ ] Topic-Label werden case/whitespace-normalisiert, bevor `EntityIdGenerator` aufgerufen wird (Dedup).
- [ ] Jedes Topic wird als `(topicId, rdf:type, skos:Concept)` im Default-Graph gespeichert.
- [ ] Chunk-zu-Topic-Zuordnung als `(urn:chunk:<id>, dct:subject, topicId)`.
- [ ] Confidence wird als Quoted Triple `<<chunk dct:subject topic>> graphmesh:topicConfidence "0.87"` im Default-Graph abgelegt.
- [ ] Provenance-Quads im Source-Graph via `ProvenanceService.buildSubgraphQuads`.
- [ ] Quads vor `insertBatch` dedupliziert (Subject|Predicate|Object|Graph).
- [ ] Frontend-TopicFacet zeigt Top-N Topics einer Collection mit Count und erlaubt Filter-Anwendung.
- [ ] Integrationstest: nach Ingest von 3 Beispielchunks existieren die erwarteten `skos:Concept`-Triples.
