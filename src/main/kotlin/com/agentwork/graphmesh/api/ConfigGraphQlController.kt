package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.config.ConfigItem
import com.agentwork.graphmesh.config.ConfigService
import com.agentwork.graphmesh.config.ConfigType
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class ConfigGraphQlController(
    private val configService: ConfigService
) {

    @QueryMapping
    fun configKeys(@Argument type: String?): List<ConfigEntryView> {
        val typeEnum = type?.let { runCatching { ConfigType.valueOf(it) }.getOrNull() }
        return configService.findAll(typeEnum).map { ConfigEntryView.from(it) }
    }

    @QueryMapping
    fun configValue(@Argument key: String, @Argument type: String): ConfigEntryView? {
        val typeEnum = runCatching { ConfigType.valueOf(type) }.getOrNull() ?: return null
        return configService.findByTypeAndKey(typeEnum, key)?.let { ConfigEntryView.from(it) }
    }

    @MutationMapping
    fun setConfig(
        @Argument key: String,
        @Argument value: String,
        @Argument type: String
    ): ConfigEntryView {
        val typeEnum = ConfigType.valueOf(type)
        val existing = configService.findByTypeAndKey(typeEnum, key)
        val item = (existing ?: ConfigItem(
            id = "$type:$key",
            type = typeEnum,
            key = key,
            value = value
        )).copy(value = value)
        return ConfigEntryView.from(configService.save(item))
    }
}

data class ConfigEntryView(
    val id: String,
    val type: String,
    val key: String,
    val value: String,
    val version: Int
) {
    companion object {
        fun from(item: ConfigItem) = ConfigEntryView(
            id = item.id,
            type = item.type.name,
            key = item.key,
            value = item.value,
            version = item.version
        )
    }
}
