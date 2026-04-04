package com.agentwork.graphmesh.collection

interface CollectionStore {
    fun save(collection: Collection)
    fun findById(id: String): Collection?
    fun findByName(name: String): Collection?
    fun findAll(): List<Collection>
    fun delete(id: String)
    fun exists(id: String): Boolean
}
