package com.agentwork.graphmesh.extraction.definition

import com.agentwork.graphmesh.llm.resolveLlmModel

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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@OptIn(kotlin.time.ExperimentalTime::class)
@Service
class DefinitionExtractorService(
    private val promptExecutor: PromptExecutor,
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService,
    private val provenanceService: ProvenanceService,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val modelName: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    companion object {
        private const val RDFS_COMMENT = "http://www.w3.org/2000/01/rdf-schema#comment"
        private const val RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label"
    }

    fun extract(chunkId: String, collectionId: String): DefinitionExtractionResult {
        val content = librarianService.getContent(chunkId)
        val chunkText = com.agentwork.graphmesh.llm.sanitizeForLlm(String(content, Charsets.UTF_8))

        if (chunkText.isBlank()) {
            return DefinitionExtractionResult(chunkId, 0, emptyList())
        }

        val extractionPrompt = prompt("definition-extraction") {
            system(DefinitionPromptTemplate.systemPrompt())
            user(DefinitionPromptTemplate.userPrompt(chunkText))
        }

        val llmResponse = runBlocking {
            promptExecutor.execute(extractionPrompt, resolveLlmModel(modelName))
        }
        val responseText = llmResponse.first().content

        val definitions = parseJsonlDefinitions(responseText)

        if (definitions.isEmpty()) {
            logger.debug("No definitions extracted from chunk {}", chunkId)
            return DefinitionExtractionResult(chunkId, 0, emptyList())
        }

        // Knowledge quads: (entityId, rdfs:comment, definition) in DEFAULT graph
        val knowledgeQuads = definitions.map { result ->
            Quad(
                subject = EntityIdGenerator.generate(result.entity),
                predicate = RdfTerm.Uri(RDFS_COMMENT),
                objectTerm = RdfTerm.Literal(result.definition),
                graph = NamedGraph.DEFAULT
            )
        }

        // Label quads: (entityId, rdfs:label, entityName) in DEFAULT graph, deduplicated
        val labelQuads = definitions.map { result ->
            Quad(
                subject = EntityIdGenerator.generate(result.entity),
                predicate = RdfTerm.Uri(RDFS_LABEL),
                objectTerm = RdfTerm.Literal(result.entity),
                graph = NamedGraph.DEFAULT
            )
        }.distinctBy { it.subject.toNTriples() }

        // Provenance quads via subgraph compression
        val provenanceQuads = provenanceService.buildSubgraphQuads(
            SubgraphProvenance(
                extractedTriples = knowledgeQuads.map { it.triple },
                chunkUri = "urn:chunk:$chunkId",
                agentLabel = "DefinitionExtractor",
                modelName = modelName
            )
        )

        val allStoredQuads = (knowledgeQuads + labelQuads).map { QuadConverter.toStoredQuad(it) } +
            provenanceQuads.map { QuadConverter.toStoredQuad(it) }
        quadStore.insertBatch(collectionId, allStoredQuads)

        logger.info("Extracted {} definitions from chunk {}", definitions.size, chunkId)

        return DefinitionExtractionResult(
            chunkId = chunkId,
            definitionsExtracted = definitions.size,
            entitiesFound = definitions.map { it.entity }
        )
    }

    internal fun parseJsonlDefinitions(llmResponse: String): List<DefinitionResult> {
        return llmResponse.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val map = objectMapper.readValue<Map<String, String>>(line)
                    val entity = map["entity"]?.takeIf { it.isNotBlank() }
                    val definition = map["definition"]?.takeIf { it.isNotBlank() }
                    if (entity != null && definition != null) DefinitionResult(entity, definition) else null
                } catch (_: Exception) {
                    null
                }
            }
    }
}
