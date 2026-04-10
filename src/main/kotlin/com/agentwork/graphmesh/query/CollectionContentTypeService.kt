package com.agentwork.graphmesh.query

import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class CollectionContentTypeService(
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService,
) {

    data class ContentFlags(val hasTriples: Boolean, val hasDocuments: Boolean)

    private val cache: Cache<String, ContentFlags> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(Duration.ofMinutes(10))
        .build()

    fun hasTriples(collectionId: String): Boolean =
        getFlags(collectionId).hasTriples

    fun hasDocuments(collectionId: String): Boolean =
        getFlags(collectionId).hasDocuments

    fun isMixed(collectionId: String): Boolean =
        getFlags(collectionId).let { it.hasTriples && it.hasDocuments }

    fun invalidate(collectionId: String) = cache.invalidate(collectionId)

    private fun getFlags(collectionId: String): ContentFlags =
        cache.get(collectionId) { cid ->
            ContentFlags(
                hasTriples = quadStore.query(cid, QuadQuery(), limit = 1).isNotEmpty(),
                hasDocuments = librarianService.findByCollection(cid).isNotEmpty()
            )
        }
}
