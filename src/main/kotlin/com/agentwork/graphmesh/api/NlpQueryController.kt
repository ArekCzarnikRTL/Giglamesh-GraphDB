package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.query.nlp.NlpQuery
import com.agentwork.graphmesh.query.nlp.NlpQueryResult
import com.agentwork.graphmesh.query.nlp.NlpQueryService
import com.agentwork.graphmesh.query.nlp.QueryIntent
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class NlpQueryController(
    private val nlpQueryService: NlpQueryService
) {

    @QueryMapping
    fun nlpQuery(@Argument input: NlpQueryInput): NlpQueryResult {
        val query = NlpQuery(
            question = input.question,
            collectionId = input.collectionId,
            forceIntent = input.forceIntent?.let { QueryIntent.valueOf(it) }
        )
        return nlpQueryService.query(query)
    }
}

data class NlpQueryInput(
    val question: String,
    val collectionId: String,
    val forceIntent: String?
)
