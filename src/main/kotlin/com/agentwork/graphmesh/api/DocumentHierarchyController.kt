package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.librarian.Document
import com.agentwork.graphmesh.librarian.LibrarianService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class DocumentHierarchyController(
    private val librarianService: LibrarianService
) {

    @QueryMapping
    fun documentHierarchy(
        @Argument collectionId: String,
        @Argument documentId: String
    ): DocumentNodeView? {
        val root = librarianService.findById(documentId) ?: return null
        if (root.collectionId != collectionId) return null
        return build(root, visited = mutableSetOf(), depth = 0)
    }

    private fun build(doc: Document, visited: MutableSet<String>, depth: Int): DocumentNodeView {
        if (depth >= MAX_DEPTH) {
            return DocumentNodeView(doc.id, doc.title, doc.type.name, emptyList())
        }
        visited += doc.id
        val children = librarianService.findChildren(doc.id)
            .filter { it.id !in visited }
            .map { build(it, visited, depth + 1) }
        return DocumentNodeView(
            id = doc.id,
            title = doc.title,
            type = doc.type.name,
            children = children
        )
    }

    companion object {
        private const val MAX_DEPTH = 10
    }
}

data class DocumentNodeView(
    val id: String,
    val title: String,
    val type: String,
    val children: List<DocumentNodeView>
)
