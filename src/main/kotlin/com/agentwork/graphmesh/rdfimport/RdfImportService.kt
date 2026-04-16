package com.agentwork.graphmesh.rdfimport

import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import com.agentwork.graphmesh.extraction.embedding.EmbeddingConfig
import com.agentwork.graphmesh.llm.resolveLlmModel
import com.agentwork.graphmesh.ontology.JenaAdapter
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import com.agentwork.graphmesh.storage.vector.VectorPayload
import com.agentwork.graphmesh.storage.vector.VectorPoint
import com.agentwork.graphmesh.storage.vector.VectorStore
import kotlinx.coroutines.runBlocking
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.RDFNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class RdfImportService(
    private val jenaAdapter: JenaAdapter,
    private val quadStore: QuadStore,
    private val embeddingProvider: LLMEmbeddingProvider,
    private val vectorStore: VectorStore,
    private val embeddingConfig: EmbeddingConfig,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    data class ImportResult(
        val tripleCount: Int,
        val skippedCount: Int,
        val durationMs: Long,
        val embeddingsGenerated: Int = 0,
    )

    fun importRdf(
        collectionId: String,
        content: String,
        format: RdfFormat,
        dataset: String?,
        generateEmbeddings: Boolean = false,
    ): ImportResult {
        val start = System.currentTimeMillis()
        val model = parseContent(content, format)
        val ds = dataset ?: ""
        var imported = 0
        var skipped = 0

        val quads = mutableListOf<StoredQuad>()
        val stmtIter = model.listStatements()
        while (stmtIter.hasNext()) {
            val stmt = stmtIter.next()
            val subjectUri = stmt.subject.uri
            if (subjectUri == null) {
                skipped++
                continue
            }
            val predicateUri = stmt.predicate.uri
            val obj: RDFNode = stmt.`object`
            val resolved = resolveObject(obj)
            if (resolved.value == null) {
                skipped++
                continue
            }
            quads += StoredQuad(
                subject = subjectUri,
                predicate = predicateUri,
                objectValue = resolved.value,
                dataset = ds,
                objectType = resolved.type,
                datatype = resolved.datatype,
                language = resolved.language,
            )
            imported++
        }

        if (quads.isNotEmpty()) {
            quadStore.insertBatch(collectionId, quads)
        }

        var embeddingsGenerated = 0
        if (generateEmbeddings && quads.isNotEmpty()) {
            embeddingsGenerated = generateEntityEmbeddings(collectionId, quads)
        }

        logger.info("RDF import into collection '{}': {} triples, {} skipped, {}ms",
            collectionId, imported, skipped, System.currentTimeMillis() - start)

        return ImportResult(
            tripleCount = imported,
            skippedCount = skipped,
            durationMs = System.currentTimeMillis() - start,
            embeddingsGenerated = embeddingsGenerated,
        )
    }

    private fun parseContent(content: String, format: RdfFormat): Model = when (format) {
        RdfFormat.TURTLE -> jenaAdapter.parseTurtle(content)
        RdfFormat.RDFXML -> jenaAdapter.parseRdfXml(content)
        RdfFormat.NTRIPLES -> jenaAdapter.parseNTriples(content)
    }

    private data class ResolvedObject(
        val value: String?,
        val type: ObjectType,
        val datatype: String,
        val language: String,
    )

    private fun generateEntityEmbeddings(collectionId: String, quads: List<StoredQuad>): Int {
        val bySubject = quads.groupBy { it.subject }
        val model = resolveLlmModel(embeddingConfig.model)

        val points = mutableListOf<VectorPoint>()
        for ((subjectUri, triples) in bySubject) {
            val text = buildEntityText(subjectUri, triples)
            if (text.isBlank()) continue
            try {
                val embedding = runBlocking { embeddingProvider.embed(text, model) }
                val vector = FloatArray(embedding.size) { embedding[it].toFloat() }
                points += VectorPoint(
                    id = subjectUri,
                    vector = vector,
                    payload = VectorPayload(
                        collection = collectionId,
                        entityUri = subjectUri,
                        source = "rdf-import"
                    )
                )
            } catch (e: Exception) {
                logger.warn("Failed to embed entity '{}': {}", subjectUri, e.message)
            }
        }

        if (points.isNotEmpty()) {
            vectorStore.upsert(collectionId, points)
        }

        logger.info("Generated {} entity embeddings for collection '{}'", points.size, collectionId)
        return points.size
    }

    companion object {
        fun buildEntityText(subjectUri: String, triples: List<StoredQuad>): String {
            val localName = extractLocalName(subjectUri)
            return triples.joinToString(". ") { quad ->
                val predName = extractLocalName(quad.predicate)
                val objName = if (quad.objectType == ObjectType.URI) {
                    extractLocalName(quad.objectValue)
                } else {
                    quad.objectValue
                }
                "$localName $predName $objName"
            }
        }

        fun extractLocalName(uri: String): String {
            val fragment = uri.substringAfterLast('#', "")
            if (fragment.isNotEmpty()) return fragment
            return uri.substringAfterLast('/')
        }
    }

    private fun resolveObject(node: RDFNode): ResolvedObject = when {
        node.isURIResource -> ResolvedObject(
            node.asResource().uri, ObjectType.URI, "", ""
        )
        node.isLiteral -> {
            val lit = node.asLiteral()
            ResolvedObject(
                lit.string, ObjectType.LITERAL,
                lit.datatypeURI ?: "", lit.language ?: ""
            )
        }
        else -> ResolvedObject(null, ObjectType.URI, "", "")
    }
}

enum class RdfFormat { TURTLE, RDFXML, NTRIPLES }
