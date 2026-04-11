package com.agentwork.graphmesh.query

import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import com.agentwork.graphmesh.extraction.embedding.EmbeddingConfig
import com.agentwork.graphmesh.llm.resolveLlmModel
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

@Service
class CachedEmbeddingService(
    private val embeddingProvider: LLMEmbeddingProvider,
    private val embeddingConfig: EmbeddingConfig,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val cache: Cache<String, FloatArray> = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofMinutes(30))
        .recordStats()
        .build()

    private val totalCalls = AtomicLong(0)

    fun embed(text: String): FloatArray {
        val model = resolveLlmModel(embeddingConfig.model)
        val key = "${model.id}:${text.hashCode()}"

        val cached = cache.getIfPresent(key)
        val result: FloatArray
        val hit: Boolean
        if (cached != null) {
            result = cached
            hit = true
        } else {
            hit = false
            result = cache.get(key) { _ ->
                val embedding = runBlocking { embeddingProvider.embed(text, model) }
                FloatArray(embedding.size) { embedding[it].toFloat() }
            }!!
        }

        logger.debug(
            "Embedding {} for model={} textHash={} dim={}",
            if (hit) "HIT" else "MISS",
            model.id,
            text.hashCode(),
            result.size
        )

        val calls = totalCalls.incrementAndGet()
        if (calls % STATS_LOG_INTERVAL == 0L) {
            logStats()
        }

        return result
    }

    /**
     * Writes current Caffeine cache statistics to the log (INFO level).
     * Called automatically every [STATS_LOG_INTERVAL] embeds and can be
     * triggered externally (e.g. from an admin endpoint) if desired.
     */
    fun logStats() {
        val stats = cache.stats()
        val size = cache.estimatedSize()
        val requests = stats.requestCount()
        val hitRate = if (requests > 0) stats.hitRate() * 100.0 else 0.0
        logger.info(
            "EmbeddingCache stats: size={}/{} requests={} hits={} misses={} hitRate={}% " +
                "evictions={} avgLoadPenalty={}ms",
            size,
            CACHE_MAX_SIZE,
            requests,
            stats.hitCount(),
            stats.missCount(),
            String.format("%.1f", hitRate),
            stats.evictionCount(),
            String.format("%.1f", stats.averageLoadPenalty() / 1_000_000.0)
        )
    }

    companion object {
        private const val CACHE_MAX_SIZE = 1000L
        private const val STATS_LOG_INTERVAL = 10L
    }
}
