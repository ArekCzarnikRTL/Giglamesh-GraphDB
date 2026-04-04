package com.agentwork.graphmesh.collection

class CollectionNotFoundException(id: String) : RuntimeException("Collection not found: $id")
