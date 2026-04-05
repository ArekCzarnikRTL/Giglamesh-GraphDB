package com.agentwork.graphmesh.structured

import com.agentwork.graphmesh.config.ConfigItem
import com.agentwork.graphmesh.config.ConfigService
import com.agentwork.graphmesh.config.ConfigType
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SchemaStore(
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper
) {

    fun save(schema: TableSchema): ConfigItem {
        val json = objectMapper.writeValueAsString(schema)
        val existing = configService.findByTypeAndKey(ConfigType.SCHEMA, schema.name)
        val item = ConfigItem(
            id = existing?.id ?: UUID.randomUUID().toString(),
            type = ConfigType.SCHEMA,
            key = schema.name,
            value = json,
            description = schema.description ?: ""
        )
        return configService.save(item)
    }

    fun load(name: String): TableSchema? {
        val item = configService.findByTypeAndKey(ConfigType.SCHEMA, name) ?: return null
        return objectMapper.readValue(item.value, TableSchema::class.java)
    }

    fun listNames(): List<String> =
        configService.findByType(ConfigType.SCHEMA).map { it.key }

    fun delete(name: String) {
        val item = configService.findByTypeAndKey(ConfigType.SCHEMA, name) ?: return
        configService.delete(item.id)
    }
}
