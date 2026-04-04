package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.librarian.LibrarianService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller
import java.util.Base64

@Controller
class DocumentController(
    private val librarianService: LibrarianService
) {

    @QueryMapping
    fun documents(@Argument collectionId: String, @Argument type: DocumentType?): List<Document> {
        return librarianService.findByCollection(collectionId, type)
    }

    @QueryMapping
    fun document(@Argument id: String): Document? {
        return librarianService.findById(id)
    }

    @SchemaMapping(typeName = "Document", field = "children")
    fun children(document: Document): List<Document> {
        return librarianService.findChildren(document.id)
    }

    @SchemaMapping(typeName = "Document", field = "metadata")
    fun metadata(document: Document): List<Map<String, String>> {
        return document.metadata.map { (k, v) -> mapOf("key" to k, "value" to v) }
    }

    @MutationMapping
    fun uploadDocument(@Argument input: UploadDocumentInput): Document {
        val content = Base64.getDecoder().decode(input.content)
        return librarianService.uploadDocument(
            collectionId = input.collectionId,
            title = input.title,
            mimeType = input.mimeType,
            content = content,
            metadata = input.metadata?.associate { it.key to it.value } ?: emptyMap()
        )
    }

    @MutationMapping
    fun deleteDocument(@Argument id: String): Boolean {
        librarianService.deleteDocument(id)
        return true
    }
}
