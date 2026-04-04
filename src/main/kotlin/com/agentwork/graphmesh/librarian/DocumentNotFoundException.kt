package com.agentwork.graphmesh.librarian

class DocumentNotFoundException(id: String) : RuntimeException("Document not found: $id")
