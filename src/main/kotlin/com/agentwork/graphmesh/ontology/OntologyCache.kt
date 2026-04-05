package com.agentwork.graphmesh.ontology

import com.agentwork.graphmesh.config.ConfigChangedEvent
import com.agentwork.graphmesh.config.ConfigType
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class OntologyCache(private val store: OntologyStore) {

    private val cache = ConcurrentHashMap<String, Ontology>()

    fun get(key: String): Ontology? =
        cache[key] ?: store.load(key)?.also { cache[key] = it }

    @EventListener
    fun onConfigChanged(event: ConfigChangedEvent) {
        if (event.configType != ConfigType.ONTOLOGY) return
        cache.remove(event.key)
    }
}
