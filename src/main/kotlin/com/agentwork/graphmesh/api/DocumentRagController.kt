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
        val query = DocumentRagQuery(
            question = input.question,
            collectionId = input.collectionId,
            topK = input.topK ?: 10,
            similarityThreshold = input.similarityThreshold ?: 0.5f
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
