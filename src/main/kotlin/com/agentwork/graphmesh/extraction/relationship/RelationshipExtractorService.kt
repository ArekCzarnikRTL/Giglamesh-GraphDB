package com.agentwork.graphmesh.extraction.relationship

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.provenance.ProvenanceService
import com.agentwork.graphmesh.provenance.SubgraphProvenance
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.Quad
import com.agentwork.graphmesh.rdf.QuadConverter
import com.agentwork.graphmesh.rdf.RdfTerm
import com.agentwork.graphmesh.storage.QuadStore
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@OptIn(kotlin.time.ExperimentalTime::class)
@Service
class RelationshipExtractorService(
    private val promptExecutor: PromptExecutor,
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService,
    private val provenanceService: ProvenanceService,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val modelName: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun extract(chunkId: String, collectionId: String): ExtractionResult {
        val content = librarianService.getContent(chunkId)
        val chunkText = String(content, Charsets.UTF_8)

        if (chunkText.isBlank()) {
            return ExtractionResult(chunkId, 0, 0)
        }

        // LLM extraction via Koog PromptExecutor
        val extractionPrompt = prompt("relationship-extraction") {
            system(ExtractionPromptTemplate.systemPrompt())
            user(ExtractionPromptTemplate.userPrompt(chunkText))
        }

        val llmResponse = runBlocking {
            promptExecutor.execute(extractionPrompt, LLModel(LLMProvider.OpenAI, modelName))
        }
        val responseText = llmResponse.first().content

        // Parse triples from LLM response
        val rawTriples = parseTriples(responseText)

        if (rawTriples.isEmpty()) {
            logger.debug("No triples extracted from chunk {}", chunkId)
            return ExtractionResult(chunkId, 0, 0)
        }

        // Generate knowledge quads in default graph
        val knowledgeQuads = rawTriples.map { (subject, predicate, objectValue) ->
            Quad(
                subject = EntityIdGenerator.generate(subject),
                predicate = RdfTerm.Uri("http://graphmesh.io/ontology/${normalizePredicateName(predicate)}"),
                objectTerm = EntityIdGenerator.generate(objectValue),
                graph = NamedGraph.DEFAULT
            )
        }

        // Generate provenance quads via subgraph compression
        val provenanceQuads = provenanceService.buildSubgraphQuads(
            SubgraphProvenance(
                extractedTriples = knowledgeQuads.map { it.triple },
                chunkUri = "urn:chunk:$chunkId",
                agentLabel = "RelationshipExtractor",
                modelName = modelName
            )
        )

        // Generate label quads for entity resolution
        val labelQuads = rawTriples.flatMap { (subject, _, objectValue) ->
            listOf(
                Quad(
                    subject = EntityIdGenerator.generate(subject),
                    predicate = RdfTerm.Uri("http://www.w3.org/2000/01/rdf-schema#label"),
                    objectTerm = RdfTerm.Literal(subject),
                    graph = NamedGraph.DEFAULT
                ),
                Quad(
                    subject = EntityIdGenerator.generate(objectValue),
                    predicate = RdfTerm.Uri("http://www.w3.org/2000/01/rdf-schema#label"),
                    objectTerm = RdfTerm.Literal(objectValue),
                    graph = NamedGraph.DEFAULT
                )
            )
        }.distinctBy { it.subject.toNTriples() + it.objectTerm.toNTriples() }

        // Persist all quads
        val allStoredQuads = (knowledgeQuads + labelQuads).map { QuadConverter.toStoredQuad(it) } +
            provenanceQuads.map { QuadConverter.toStoredQuad(it) }
        quadStore.insertBatch(collectionId, allStoredQuads)

        logger.info("Extracted {} triples from chunk {}", rawTriples.size, chunkId)

        return ExtractionResult(
            chunkId = chunkId,
            triplesExtracted = knowledgeQuads.size,
            entitiesFound = rawTriples.flatMap { listOf(it.first, it.third) }.distinct().size
        )
    }

    internal fun parseTriples(llmResponse: String): List<Triple<String, String, String>> {
        return llmResponse.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("|") }
            .mapNotNull { line ->
                val parts = line.split("|").map { it.trim() }
                if (parts.size == 3 && parts.all { it.isNotBlank() }) {
                    Triple(parts[0], parts[1], parts[2])
                } else {
                    null
                }
            }
    }

    internal fun normalizePredicateName(predicate: String): String {
        return predicate.trim()
            .split(Regex("\\s+"))
            .mapIndexed { index, word ->
                if (index == 0) word.lowercase()
                else word.replaceFirstChar { it.uppercase() }
            }
            .joinToString("")
    }
}
