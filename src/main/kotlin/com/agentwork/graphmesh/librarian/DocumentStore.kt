package com.agentwork.graphmesh.librarian

interface DocumentStore {
    fun save(document: Document)
    fun findById(id: String): Document?
    fun findByCollection(collectionId: String, type: DocumentType? = null): List<Document>
    fun findChildren(parentId: String): List<Document>
    fun updateState(id: String, state: DocumentState)
    fun delete(id: String)
    fun deleteWithChildren(id: String)
}
