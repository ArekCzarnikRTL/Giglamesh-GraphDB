package com.agentwork.graphmesh.collection

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service

@Service
@DependsOn("collectionSchemaInitializer")
class CassandraCollectionStore(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) : CollectionStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    private lateinit var insertCollection: PreparedStatement
    private lateinit var insertByName: PreparedStatement
    private lateinit var selectById: PreparedStatement
    private lateinit var selectByName: PreparedStatement
    private lateinit var selectAll: PreparedStatement
    private lateinit var deleteById: PreparedStatement
    private lateinit var deleteByName: PreparedStatement

    @PostConstruct
    fun prepareStatements() {
        insertCollection = session.prepare("""
            INSERT INTO $keyspace.collections (id, name, description, tags, metadata, tenant_id, owner_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())

        insertByName = session.prepare("""
            INSERT INTO $keyspace.collections_by_name (name, id, description, tags, metadata, tenant_id, owner_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())

        selectById = session.prepare(
            "SELECT id, name, description, tags, metadata, tenant_id, owner_id, created_at, updated_at FROM $keyspace.collections WHERE id = ?"
        )

        selectByName = session.prepare(
            "SELECT id, name, description, tags, metadata, tenant_id, owner_id, created_at, updated_at FROM $keyspace.collections_by_name WHERE name = ?"
        )

        selectAll = session.prepare(
            "SELECT id, name, description, tags, metadata, tenant_id, owner_id, created_at, updated_at FROM $keyspace.collections"
        )

        deleteById = session.prepare("DELETE FROM $keyspace.collections WHERE id = ?")
        deleteByName = session.prepare("DELETE FROM $keyspace.collections_by_name WHERE name = ?")
    }

    override fun save(collection: Collection) {
        session.execute(insertCollection.bind(
            collection.id, collection.name, collection.description,
            collection.tags, collection.metadata,
            collection.tenantId, collection.ownerId,
            collection.createdAt, collection.updatedAt
        ))
        session.execute(insertByName.bind(
            collection.name, collection.id, collection.description,
            collection.tags, collection.metadata,
            collection.tenantId, collection.ownerId,
            collection.createdAt, collection.updatedAt
        ))
        logger.debug("Saved collection: id={}, name={}, tenantId={}", collection.id, collection.name, collection.tenantId)
    }

    override fun findById(id: String): Collection? {
        val row = session.execute(selectById.bind(id)).one() ?: return null
        return mapRow(row)
    }

    override fun findByName(name: String): Collection? {
        val row = session.execute(selectByName.bind(name)).one() ?: return null
        return mapRow(row)
    }

    override fun findAll(): List<Collection> {
        return session.execute(selectAll.bind()).map { mapRow(it) }.toList()
    }

    override fun delete(id: String) {
        val collection = findById(id) ?: return
        session.execute(deleteById.bind(id))
        session.execute(deleteByName.bind(collection.name))
        logger.debug("Deleted collection: id={}, name={}", id, collection.name)
    }

    override fun exists(id: String): Boolean {
        return session.execute(selectById.bind(id)).one() != null
    }

    private fun mapRow(row: com.datastax.oss.driver.api.core.cql.Row): Collection {
        return Collection(
            id = row.getString("id")!!,
            name = row.getString("name")!!,
            description = row.getString("description") ?: "",
            tags = row.getSet("tags", String::class.java) ?: emptySet(),
            metadata = row.getMap("metadata", String::class.java, String::class.java) ?: emptyMap(),
            tenantId = row.getString("tenant_id"),
            ownerId = row.getString("owner_id"),
            createdAt = row.getInstant("created_at")!!,
            updatedAt = row.getInstant("updated_at")!!
        )
    }
}
