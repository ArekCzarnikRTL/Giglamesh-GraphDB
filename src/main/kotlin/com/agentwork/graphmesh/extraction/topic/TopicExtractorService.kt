package com.agentwork.graphmesh.extraction.topic

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.llm.resolveLlmModel
import com.agentwork.graphmesh.provenance.ProvenanceService
import com.agentwork.graphmesh.provenance.SubgraphProvenance
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.Quad
import com.agentwork.graphmesh.rdf.QuadConverter
import com.agentwork.graphmesh.rdf.RdfTerm
import com.agentwork.graphmesh.rdf.SkosTypes
import com.agentwork.graphmesh.storage.QuadStore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@OptIn(kotlin.time.ExperimentalTime::class)
@Service
class TopicExtractorService(
    private val promptExecutor: PromptExecutor,
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService,
    private val provenanceService: ProvenanceService,
    private val ontologyMatcher: TopicOntologyMatcher,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val modelName: String,
    @Value("\${graphmesh.extraction.topic.minConfidence:0.5}") private val minConfidence: Double
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    companion object {
        private const val RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
        private const val RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label"
        private const val DCT_SUBJECT = "http://purl.org/dc/terms/subject"
        private const val TOPIC_CONFIDENCE = "http://graphmesh.io/ontology/topicConfidence"
    }

    fun extract(chunkId: String, collectionId: String): TopicExtractionResult {
        val content = librarianService.getContent(chunkId)
        val chunkText = String(content, Charsets.UTF_8)
        if (chunkText.isBlank()) {
            return TopicExtractionResult(chunkId, 0, emptyList())
        }

        val hints = ontologyMatcher.getHints(collectionId)

        val extractionPrompt = prompt("topic-extraction") {
            system(TopicPromptTemplate.systemPrompt(hints))
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
            val topicId = ontologyMatcher.resolveOrCreate(t.topic, collectionId)

            knowledgeQuads += Quad(topicId, RdfTerm.Uri(RDF_TYPE), RdfTerm.Uri(SkosTypes.CONCEPT), NamedGraph.DEFAULT)
            knowledgeQuads += Quad(topicId, RdfTerm.Uri(RDFS_LABEL), RdfTerm.Literal(t.topic), NamedGraph.DEFAULT)
            knowledgeQuads += Quad(chunkUri, RdfTerm.Uri(DCT_SUBJECT), topicId, NamedGraph.DEFAULT)

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
