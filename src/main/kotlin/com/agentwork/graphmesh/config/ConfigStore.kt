package com.agentwork.graphmesh.config

interface ConfigStore {
    fun save(item: ConfigItem): ConfigItem
    fun findById(id: String): ConfigItem?
    fun findByType(type: ConfigType): List<ConfigItem>
    fun findByTypeAndKey(type: ConfigType, key: String): ConfigItem?
    fun delete(id: String)
    fun history(id: String, limit: Int = 10): List<ConfigItem>
}
