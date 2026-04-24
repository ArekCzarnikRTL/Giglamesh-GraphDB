package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.collection.CollectionService
import graphql.ExecutionInput
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class DynamicGraphQlController(
    private val registry: DynamicGraphQlRegistry,
    private val collectionService: CollectionService,
) {

    @PostMapping("/graphql/{collectionName}")
    fun execute(
        @PathVariable collectionName: String,
        @RequestBody body: Map<String, Any>,
    ): ResponseEntity<Map<String, Any>> {
        val collection = collectionService.findByName(collectionName)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "Collection '$collectionName' not found"))

        val graphql = registry.get(collection.id)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "No GraphQL schema generated for collection '$collectionName'"))

        val query = body["query"] as? String
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Missing 'query' field in request body"))

        @Suppress("UNCHECKED_CAST")
        val variables = body["variables"] as? Map<String, Any> ?: emptyMap()
        val operationName = body["operationName"] as? String

        val executionInput = ExecutionInput.newExecutionInput()
            .query(query)
            .variables(variables)
            .operationName(operationName)
            .build()

        val result = graphql.execute(executionInput)

        val responseBody = mutableMapOf<String, Any>()
        responseBody["data"] = result.getData<Any>()
        if (result.errors.isNotEmpty()) {
            responseBody["errors"] = result.errors.map { it.toSpecification() }
        }

        return ResponseEntity.ok(responseBody)
    }
}
