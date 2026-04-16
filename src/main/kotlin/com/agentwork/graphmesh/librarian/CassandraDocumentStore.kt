package com.agentwork.graphmesh.librarian

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.Row
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service
import java.time.Instant

@Service
@DependsOn("documentSchemaInitializer")
class CassandraDocumentStore(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) : DocumentStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    private lateinit var insertDoc: PreparedStatement
    private lateinit var insertByCollection: PreparedStatement
    private lateinit var insertByParent: PreparedStatement
    private lateinit var selectById: PreparedStatement
    private lateinit var selectByCollection: PreparedStatement
    private lateinit var selectByCollectionType: PreparedStatement
    private lateinit var selectByParent: PreparedStatement
    private lateinit var updateStateStmt: PreparedStatement
    private lateinit var updateStateByCollectionStmt: PreparedStatement
    private lateinit var updateStateByParentStmt: PreparedStatement
    private lateinit var deleteDoc: PreparedStatement
    private lateinit var deleteByCollection: PreparedStatement
    private lateinit var deleteByParent: PreparedStatement

    @PostConstruct
    fun prepareStatements() {
        val cols = "id, collection_id, parent_id, type, state, title, mime_type, content_uri, metadata, created_at, updated_at"

        insertDoc = session.prepare("""
            INSERT INTO $keyspace.documents ($cols) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())

        insertByCollection = session.prepare("""
            INSERT INTO $keyspace.documents_by_collection ($cols) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())

        insertByParent = session.prepare("""
            INSERT INTO $keyspace.documents_by_parent ($cols) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())

        selectById = session.prepare("SELECT $cols FROM $keyspace.documents WHERE id = ?")

        selectByCollectionType = session.prepare(
            "SELECT $cols FROM $keyspace.documents_by_collection WHERE collection_id = ? AND type = ?"
        )

        selectByCollection = session.prepare(
            "SELECT $cols FROM $keyspace.documents_by_collection WHERE collection_id = ? AND type = 'SOURCE'"
        )

        selectByParent = session.prepare(
            "SELECT $cols FROM $keyspace.documents_by_parent WHERE parent_id = ?"
        )

        updateStateStmt = session.prepare(
            "UPDATE $keyspace.documents SET state = ?, updated_at = ? WHERE id = ?"
        )
        updateStateByCollectionStmt = session.prepare(
            "UPDATE $keyspace.documents_by_collection SET state = ?, updated_at = ? WHERE collection_id = ? AND type = ? AND id = ?"
        )
        updateStateByParentStmt = session.prepare(
            "UPDATE $keyspace.documents_by_parent SET state = ?, updated_at = ? WHERE parent_id = ? AND id = ?"
        )

        deleteDoc = session.prepare("DELETE FROM $keyspace.documents WHERE id = ?")
        deleteByCollection = session.prepare(
            "DELETE FROM $keyspace.documents_by_collection WHERE collection_id = ? AND type = ? AND id = ?"
        )
        deleteByParent = session.prepare(
            "DELETE FROM $keyspace.documents_by_parent WHERE parent_id = ? AND id = ?"
        )
    }

    override fun save(document: Document) {
        val params = arrayOf(
            document.id, document.collectionId, document.parentId,
            document.type.name, document.state.name, document.title,
            document.mimeType, document.contentUri, document.metadata,
            document.createdAt, document.updatedAt
        )
        session.execute(insertDoc.bind(*params))
        session.execute(insertByCollection.bind(*params))
        if (document.parentId != null) {
            session.execute(insertByParent.bind(*params))
        }
        logger.debug("Saved document: id={}, type={}, collection={}", document.id, document.type, document.collectionId)
    }

    override fun findById(id: String): Document? {
        val row = session.execute(selectById.bind(id)).one() ?: return null
        return mapRow(row)
    }

    override fun findByCollection(collectionId: String, type: DocumentType?): List<Document> {
        val effectiveType = type ?: DocumentType.SOURCE
        return session.execute(selectByCollectionType.bind(collectionId, effectiveType.name))
            .map { mapRow(it) }.toList()
    }

    override fun findChildren(parentId: String): List<Document> {
        return session.execute(selectByParent.bind(parentId))
            .map { mapRow(it) }.toList()
    }

    override fun updateState(id: String, state: DocumentState) {
        val now = Instant.now()
        session.execute(updateStateStmt.bind(state.name, now, id))

        val doc = findById(id)
        if (doc != null) {
            session.execute(updateStateByCollectionStmt.bind(state.name, now, doc.collectionId, doc.type.name, id))
            if (doc.parentId != null) {
                session.execute(updateStateByParentStmt.bind(state.name, now, doc.parentId, id))
            }
        }
        logger.debug("Updated document state: id={}, state={}", id, state)
    }

    override fun delete(id: String) {
        val doc = findById(id) ?: return
        session.execute(deleteDoc.bind(id))
        session.execute(deleteByCollection.bind(doc.collectionId, doc.type.name, id))
        if (doc.parentId != null) {
            session.execute(deleteByParent.bind(doc.parentId, id))
        }
        logger.debug("Deleted document: id={}", id)
    }

    override fun deleteWithChildren(id: String) {
        val children = findChildren(id)
        for (child in children) {
            deleteWithChildren(child.id)
        }
        delete(id)
    }

    private fun mapRow(row: Row): Document {
        return Document(
            id = row.getString("id")!!,
            collectionId = row.getString("collection_id")!!,
            parentId = row.getString("parent_id"),
            type = DocumentType.valueOf(row.getString("type")!!),
            state = DocumentState.valueOf(row.getString("state")!!),
            title = row.getString("title") ?: "",
            mimeType = row.getString("mime_type") ?: "application/octet-stream",
            contentUri = row.getString("content_uri") ?: "",
            metadata = row.getMap("metadata", String::class.java, String::class.java) ?: emptyMap(),
            createdAt = row.getInstant("created_at")!!,
            updatedAt = row.getInstant("updated_at")!!
        )
    }
}
