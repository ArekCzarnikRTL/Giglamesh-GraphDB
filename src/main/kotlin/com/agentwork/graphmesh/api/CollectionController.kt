package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.collection.Collection
import com.agentwork.graphmesh.collection.CollectionService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

@Controller
class CollectionController(
    private val collectionService: CollectionService
) {

    @QueryMapping
    fun collections(@Argument tags: List<String>?): List<Collection> {
        return collectionService.findAll(tags?.toSet() ?: emptySet())
    }

    @QueryMapping
    fun collection(@Argument id: String): Collection? {
        return collectionService.findById(id)
    }

    @SchemaMapping(typeName = "Collection", field = "metadata")
    fun metadata(collection: Collection): List<Map<String, String>> {
        return collection.metadata.map { (k, v) -> mapOf("key" to k, "value" to v) }
    }

    @MutationMapping
    fun createCollection(@Argument input: CreateCollectionInput): Collection {
        return collectionService.create(
            name = input.name,
            description = input.description ?: "",
            tags = input.tags?.toSet() ?: emptySet(),
            metadata = input.metadata?.associate { it.key to it.value } ?: emptyMap()
        )
    }

    @MutationMapping
    fun updateCollection(@Argument id: String, @Argument input: UpdateCollectionInput): Collection {
        return collectionService.update(
            id = id,
            name = input.name,
            description = input.description,
            tags = input.tags?.toSet(),
            metadata = input.metadata?.associate { it.key to it.value }
        )
    }

    @MutationMapping
    fun deleteCollection(@Argument id: String): Boolean {
        collectionService.delete(id)
        return true
    }
}
