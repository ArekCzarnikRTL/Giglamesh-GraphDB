package com.agentwork.graphmesh.rdfimport

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.stereotype.Controller
import java.util.Base64

@Controller
class RdfImportController(
    private val rdfImportService: RdfImportService,
) {

    @MutationMapping
    fun importRdf(@Argument input: ImportRdfInput): ImportRdfResultPayload {
        val content = String(Base64.getDecoder().decode(input.content), Charsets.UTF_8)
        val result = rdfImportService.importRdf(
            collectionId = input.collectionId,
            content = content,
            format = input.format,
            dataset = input.dataset,
            generateEmbeddings = input.generateEmbeddings,
        )
        return ImportRdfResultPayload(
            tripleCount = result.tripleCount,
            skippedCount = result.skippedCount,
            durationMs = result.durationMs,
            embeddingsGenerated = result.embeddingsGenerated,
        )
    }
}

data class ImportRdfInput(
    val collectionId: String,
    val content: String,
    val format: RdfFormat,
    val dataset: String? = null,
    val generateEmbeddings: Boolean = false,
)

data class ImportRdfResultPayload(
    val tripleCount: Int,
    val skippedCount: Int,
    val durationMs: Long,
    val embeddingsGenerated: Int,
)
