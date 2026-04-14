package com.agentwork.graphmesh.extraction.agent

import com.agentwork.graphmesh.llm.resolveLlmModel

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.reActStrategy
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.provenance.ProvenanceService
import com.agentwork.graphmesh.provenance.SubgraphProvenance
import com.agentwork.graphmesh.query.graphrag.GraphRagService
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
class AgentExtractorService(
    private val promptExecutor: PromptExecutor,
    private val graphRagService: GraphRagService,
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
        private const val ONTOLOGY_NS = "http://graphmesh.io/ontology/"
        val DEFAULT_STRATEGY = ExtractionStrategy(
            name = "default-extraction",
            systemPrompt = """
                Du bist ein Wissensextraktions-Agent. Extrahiere Wissen aus dem gegebenen Text.

                Verwende die verfuegbaren Tools um:
                1. Zu pruefen, ob Entitaeten bereits im Graph existieren (validate_entity)
                2. Existierende Beziehungen zu konsultieren (graph_query)
                3. Den Kontext bei Bedarf zu erweitern (context_expand)

                Wenn du fertig bist, gib die extrahierten Ergebnisse im JSONL-Format aus.
                Jede Zeile ist ein JSON-Objekt mit einem "type"-Feld:
                {"type": "relationship", "subject": "...", "predicate": "...", "object": "...", "object_entity": true}
                {"type": "definition", "entity": "...", "definition": "..."}
                {"type": "entity", "entity": "...", "entity_type": "..."}
                {"type": "attribute", "entity": "...", "attribute": "...", "value": "..."}

                Ziel: Hochqualitative, nicht-redundante Triples.
            """.trimIndent(),
            maxIterations = 5
        )
    }

    fun extract(chunkId: String, collectionId: String): AgentExtractionResult {
        return extract(chunkId, collectionId, DEFAULT_STRATEGY)
    }

    fun extract(chunkId: String, collectionId: String, strategy: ExtractionStrategy): AgentExtractionResult {
        val content = librarianService.getContent(chunkId)
        val chunkText = com.agentwork.graphmesh.llm.sanitizeForLlm(String(content, Charsets.UTF_8))

        if (chunkText.isBlank()) {
            return AgentExtractionResult(chunkId = chunkId, extractedItems = emptyList(), strategy = strategy.name)
        }

        val toolRegistry = ToolRegistry {
            tool(GraphQueryTool(graphRagService, collectionId))
            tool(ValidateEntityTool(quadStore, collectionId))
            tool(ContextExpandTool(librarianService, chunkId))
        }

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = resolveLlmModel(modelName),
            strategy = reActStrategy(reasoningInterval = 1, name = "extraction_agent"),
            toolRegistry = toolRegistry,
            systemPrompt = strategy.systemPrompt
        )

        val agentResult = runBlocking {
            agent.run("Extrahiere Wissen aus folgendem Text:\n\n$chunkText")
        }

        val items = parseFinalOutput(agentResult)

        val knowledgeQuads = items.flatMap { convertToKnowledgeQuads(it) }
        val provenanceQuads = provenanceService.buildSubgraphQuads(
            SubgraphProvenance(
                extractedTriples = knowledgeQuads.map { it.triple },
                chunkUri = "urn:chunk:$chunkId",
                agentLabel = "AgentExtractor",
                modelName = modelName
            )
        )
        val storedQuads = knowledgeQuads.map { QuadConverter.toStoredQuad(it) } +
            provenanceQuads.map { QuadConverter.toStoredQuad(it) }
        if (storedQuads.isNotEmpty()) {
            quadStore.insertBatch(collectionId, storedQuads)
        }

        logger.info(
            "Agent extraction complete: chunkId={}, items={}, strategy={}",
            chunkId, items.size, strategy.name
        )

        return AgentExtractionResult(
            chunkId = chunkId,
            extractedItems = items,
            strategy = strategy.name
        )
    }

    @Suppress("UNCHECKED_CAST")
    internal fun parseFinalOutput(response: String): List<ExtractedItem> {
        return response.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val map = objectMapper.readValue<Map<String, Any?>>(line)
                    when (map["type"]) {
                        "definition" -> ExtractedItem.Definition(
                            entity = map["entity"] as String,
                            definition = map["definition"] as String
                        )
                        "relationship" -> ExtractedItem.Relationship(
                            subject = map["subject"] as String,
                            predicate = map["predicate"] as String,
                            objectValue = map["object"] as String,
                            objectIsEntity = map["object_entity"] as? Boolean ?: true
                        )
                        "entity" -> ExtractedItem.Entity(
                            name = map["entity"] as String,
                            entityType = map["entity_type"] as? String
                        )
                        "attribute" -> ExtractedItem.Attribute(
                            entity = map["entity"] as String,
                            attribute = map["attribute"] as String,
                            value = map["value"] as String
                        )
                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }
            }
    }

    internal fun convertToKnowledgeQuads(item: ExtractedItem): List<Quad> {
        return when (item) {
            is ExtractedItem.Definition -> listOf(
                Quad(EntityIdGenerator.generate(item.entity), RdfTerm.Uri(RDFS_COMMENT), RdfTerm.Literal(item.definition), NamedGraph.DEFAULT),
                Quad(EntityIdGenerator.generate(item.entity), RdfTerm.Uri(RDFS_LABEL), RdfTerm.Literal(item.entity), NamedGraph.DEFAULT)
            )
            is ExtractedItem.Relationship -> {
                val objectTerm = if (item.objectIsEntity) EntityIdGenerator.generate(item.objectValue) else RdfTerm.Literal(item.objectValue)
                listOf(Quad(EntityIdGenerator.generate(item.subject), RdfTerm.Uri("${ONTOLOGY_NS}${item.predicate}"), objectTerm, NamedGraph.DEFAULT))
            }
            is ExtractedItem.Entity -> listOf(
                Quad(EntityIdGenerator.generate(item.name), RdfTerm.Uri(RDFS_LABEL), RdfTerm.Literal(item.name), NamedGraph.DEFAULT)
            )
            is ExtractedItem.Attribute -> listOf(
                Quad(EntityIdGenerator.generate(item.entity), RdfTerm.Uri("${ONTOLOGY_NS}${item.attribute}"), RdfTerm.Literal(item.value), NamedGraph.DEFAULT)
            )
        }
    }
}
