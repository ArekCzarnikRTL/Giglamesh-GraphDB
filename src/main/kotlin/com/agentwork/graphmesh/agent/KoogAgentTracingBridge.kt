package com.agentwork.graphmesh.agent

import com.agentwork.graphmesh.provenance.query.AgentIterationCollector
import org.slf4j.LoggerFactory

/**
 * Bridges Koog Tracing feature events to an AgentIterationCollector.
 *
 * Koog 0.7.3 event types are read via reflection so the bridge survives Koog API drift.
 * If Koog 0.8+ provides typed visitor APIs, this class should be refactored to use them.
 *
 * Event contract:
 *  - LLM call completion  -> collector.recordThought(response text)
 *  - Tool execution start -> collector.recordToolStart(tool name, args)
 *  - Tool execution end   -> collector.recordToolEnd(result)
 *
 * Any unrecognized event is ignored (logged at DEBUG level).
 */
class KoogAgentTracingBridge(private val collector: AgentIterationCollector) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun handle(event: Any) {
        try {
            val className = event.javaClass.simpleName
            when {
                className.contains("LLMCallCompleted", ignoreCase = true) ||
                className.contains("LLMCallEnd", ignoreCase = true) -> {
                    val text = readField(event, "responseText")
                        ?: readField(event, "response")
                        ?: readField(event, "content")
                        ?: ""
                    if (text.isNotBlank()) collector.recordThought(text)
                }
                className.contains("ToolExecutionStart", ignoreCase = true) ||
                className.contains("ToolCallStart", ignoreCase = true) -> {
                    val toolName = readField(event, "toolName")
                        ?: readField(event, "tool")
                        ?: ""
                    val args = readMapField(event, "args")
                        ?: readMapField(event, "arguments")
                    collector.recordToolStart(toolName, args)
                }
                className.contains("ToolExecutionCompleted", ignoreCase = true) ||
                className.contains("ToolCallEnd", ignoreCase = true) -> {
                    val result = readField(event, "result")
                        ?: readField(event, "output")
                    collector.recordToolEnd(result)
                }
                else -> logger.debug("Ignoring untracked Koog event: {}", className)
            }
        } catch (e: Exception) {
            logger.debug("Failed to read Koog event {}: {}", event.javaClass.simpleName, e.message)
        }
    }

    private fun readField(target: Any, name: String): String? = try {
        val field = target.javaClass.declaredFields.firstOrNull { it.name == name }
        field?.isAccessible = true
        field?.get(target)?.toString()
    } catch (_: Exception) { null }

    @Suppress("UNCHECKED_CAST")
    private fun readMapField(target: Any, name: String): Map<String, String>? = try {
        val field = target.javaClass.declaredFields.firstOrNull { it.name == name }
        field?.isAccessible = true
        val raw = field?.get(target)
        when (raw) {
            is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value.toString() }
            null -> null
            else -> mapOf("value" to raw.toString())
        }
    } catch (_: Exception) { null }
}
