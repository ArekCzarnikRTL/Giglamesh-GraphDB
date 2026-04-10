package com.agentwork.graphmesh.rdfimport

import com.agentwork.graphmesh.ontology.JenaAdapter
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.RDFNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class RdfImportService(
    private val jenaAdapter: JenaAdapter,
    private val quadStore: QuadStore,
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

        logger.info("RDF import into collection '{}': {} triples, {} skipped, {}ms",
            collectionId, imported, skipped, System.currentTimeMillis() - start)

        return ImportResult(
            tripleCount = imported,
            skippedCount = skipped,
            durationMs = System.currentTimeMillis() - start,
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
