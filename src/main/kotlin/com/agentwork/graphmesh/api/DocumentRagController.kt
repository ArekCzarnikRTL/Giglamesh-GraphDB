package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.query.docrag.DocumentRagQuery
import com.agentwork.graphmesh.query.docrag.DocumentRagResult
import com.agentwork.graphmesh.query.docrag.DocumentRagService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class DocumentRagController(
    private val documentRagService: DocumentRagService
) {

    @QueryMapping
    fun documentRag(@Argument input: DocumentRagInput): DocumentRagResult {
        // Build the query using DocumentRagQuery's defaults so that any change to
        // the default similarityThreshold (e.g. when switching embedding providers)
        // automatically propagates here as well, instead of being shadowed by a
        // hard-coded fallback.
        val defaults = DocumentRagQuery(question = input.question, collectionId = input.collectionId)
        val query = defaults.copy(
            topK = input.topK ?: defaults.topK,
            similarityThreshold = input.similarityThreshold ?: defaults.similarityThreshold
        )
        return documentRagService.query(query)
    }
}

data class DocumentRagInput(
    val question: String,
    val collectionId: String,
    val topK: Int?,
    val similarityThreshold: Float?
)
