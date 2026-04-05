package com.agentwork.graphmesh.ontology

import com.agentwork.graphmesh.config.ConfigItem
import com.agentwork.graphmesh.config.ConfigService
import com.agentwork.graphmesh.config.ConfigType
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OntologyStore(
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper
) {

    fun save(key: String, ontology: Ontology): ConfigItem {
        val json = objectMapper.writeValueAsString(ontology)
        val existing = configService.findByTypeAndKey(ConfigType.ONTOLOGY, key)
        val item = ConfigItem(
            id = existing?.id ?: UUID.randomUUID().toString(),
            type = ConfigType.ONTOLOGY,
            key = key,
            value = json,
            description = ontology.metadata.description ?: ""
        )
        return configService.save(item)
    }

    fun load(key: String): Ontology? {
        val item = configService.findByTypeAndKey(ConfigType.ONTOLOGY, key) ?: return null
        return objectMapper.readValue(item.value, Ontology::class.java)
    }

    fun listKeys(): List<String> =
        configService.findByType(ConfigType.ONTOLOGY).map { it.key }

    fun delete(key: String) {
        val item = configService.findByTypeAndKey(ConfigType.ONTOLOGY, key) ?: return
        configService.delete(item.id)
    }
}
