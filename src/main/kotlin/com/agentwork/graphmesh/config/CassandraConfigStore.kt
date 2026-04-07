package com.agentwork.graphmesh.config

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service
import java.time.Instant

@Service
@DependsOn("configSchemaInitializer")
class CassandraConfigStore(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) : ConfigStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    private lateinit var insertItem: PreparedStatement
    private lateinit var insertByType: PreparedStatement
    private lateinit var insertHistory: PreparedStatement
    private lateinit var selectById: PreparedStatement
    private lateinit var selectByType: PreparedStatement
    private lateinit var selectByTypeAndKey: PreparedStatement
    private lateinit var deleteItem: PreparedStatement
    private lateinit var deleteByType: PreparedStatement
    private lateinit var selectHistory: PreparedStatement

    @PostConstruct
    fun prepareStatements() {
        insertItem = session.prepare("""
            INSERT INTO $keyspace.config_items (id, type, key, value, version, created_at, updated_at, created_by, description)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())

        insertByType = session.prepare("""
            INSERT INTO $keyspace.config_by_type (type, key, id, value, version, created_at, updated_at, created_by, description)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())

        insertHistory = session.prepare("""
            INSERT INTO $keyspace.config_history (id, version, value, updated_at, updated_by)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent())

        selectById = session.prepare("""
            SELECT id, type, key, value, version, created_at, updated_at, created_by, description
            FROM $keyspace.config_items WHERE id = ?
        """.trimIndent())

        selectByType = session.prepare("""
            SELECT id, type, key, value, version, created_at, updated_at, created_by, description
            FROM $keyspace.config_by_type WHERE type = ?
        """.trimIndent())

        selectByTypeAndKey = session.prepare("""
            SELECT id, type, key, value, version, created_at, updated_at, created_by, description
            FROM $keyspace.config_by_type WHERE type = ? AND key = ?
        """.trimIndent())

        deleteItem = session.prepare("DELETE FROM $keyspace.config_items WHERE id = ?")

        deleteByType = session.prepare("DELETE FROM $keyspace.config_by_type WHERE type = ? AND key = ?")

        selectHistory = session.prepare("""
            SELECT id, version, value, updated_at, updated_by
            FROM $keyspace.config_history WHERE id = ? LIMIT ?
        """.trimIndent())
    }

    override fun save(item: ConfigItem): ConfigItem {
        val now = Instant.now()
        val saved = item.copy(updatedAt = now)

        session.execute(insertItem.bind(
            saved.id, saved.type.name, saved.key, saved.value, saved.version,
            saved.createdAt, saved.updatedAt, saved.createdBy, saved.description
        ))

        session.execute(insertByType.bind(
            saved.type.name, saved.key, saved.id, saved.value, saved.version,
            saved.createdAt, saved.updatedAt, saved.createdBy, saved.description
        ))

        session.execute(insertHistory.bind(
            saved.id, saved.version, saved.value, saved.updatedAt, saved.createdBy
        ))

        logger.debug("Saved config item: id={}, type={}, key={}, version={}", saved.id, saved.type, saved.key, saved.version)
        return saved
    }

    override fun findById(id: String): ConfigItem? {
        val row = session.execute(selectById.bind(id)).one() ?: return null
        return ConfigItem(
            id = row.getString("id")!!,
            type = ConfigType.valueOf(row.getString("type")!!),
            key = row.getString("key")!!,
            value = row.getString("value")!!,
            version = row.getInt("version"),
            createdAt = row.getInstant("created_at")!!,
            updatedAt = row.getInstant("updated_at")!!,
            createdBy = row.getString("created_by") ?: "system",
            description = row.getString("description") ?: ""
        )
    }

    override fun findByType(type: ConfigType): List<ConfigItem> {
        val rows = session.execute(selectByType.bind(type.name))
        return rows.map { row ->
            ConfigItem(
                id = row.getString("id")!!,
                type = ConfigType.valueOf(row.getString("type")!!),
                key = row.getString("key")!!,
                value = row.getString("value")!!,
                version = row.getInt("version"),
                updatedAt = row.getInstant("updated_at")!!
            )
        }.toList()
    }

    override fun findByTypeAndKey(type: ConfigType, key: String): ConfigItem? {
        val row = session.execute(selectByTypeAndKey.bind(type.name, key)).one() ?: return null
        return ConfigItem(
            id = row.getString("id")!!,
            type = ConfigType.valueOf(row.getString("type")!!),
            key = row.getString("key")!!,
            value = row.getString("value")!!,
            version = row.getInt("version"),
            updatedAt = row.getInstant("updated_at")!!
        )
    }

    override fun delete(id: String) {
        val item = findById(id) ?: return
        session.execute(deleteItem.bind(id))
        session.execute(deleteByType.bind(item.type.name, item.key))
        logger.debug("Deleted config item: id={}", id)
    }

    override fun history(id: String, limit: Int): List<ConfigItem> {
        val rows = session.execute(selectHistory.bind(id, limit))
        return rows.map { row ->
            ConfigItem(
                id = row.getString("id")!!,
                type = ConfigType.PARAMETER,  // history table doesn't store type
                key = "",
                value = row.getString("value")!!,
                version = row.getInt("version"),
                updatedAt = row.getInstant("updated_at")!!,
                createdBy = row.getString("updated_by") ?: "system"
            )
        }.toList()
    }
}
