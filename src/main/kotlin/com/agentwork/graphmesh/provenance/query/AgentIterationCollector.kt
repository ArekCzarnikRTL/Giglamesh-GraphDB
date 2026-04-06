package com.agentwork.graphmesh.provenance.query

/**
 * Collects think/act/observe iterations from a Koog agent run.
 *
 * Two usage modes:
 *  1. Manual recording (used in tests) via recordThought/recordToolStart/recordToolEnd.
 *  2. Koog Tracing integration: a thin adapter (see AgentService in Task 9) bridges
 *     Koog events to these methods. The exact Koog event types are inspected at
 *     wiring time — this collector is intentionally agnostic of them.
 *
 * Thread-safety: instances are session-scoped (one per agent.run() call).
 * Not safe for concurrent runs — create a new instance per session.
 */
class AgentIterationCollector {

    private val iterations = mutableListOf<AgentIterationRecord>()
    private var pendingThought: String? = null
    private var pendingAction: String? = null
    private var pendingArgs: Map<String, String>? = null

    fun recordThought(text: String) {
        if (pendingThought != null && pendingAction == null) {
            iterations += AgentIterationRecord(
                thought = pendingThought!!,
                action = null,
                arguments = null,
                observation = null,
            )
        }
        pendingThought = text
    }

    fun recordToolStart(toolName: String, args: Map<String, String>?) {
        pendingAction = toolName
        pendingArgs = args
    }

    fun recordToolEnd(observation: String?) {
        iterations += AgentIterationRecord(
            thought = pendingThought ?: "",
            action = pendingAction,
            arguments = pendingArgs,
            observation = observation,
        )
        pendingThought = null
        pendingAction = null
        pendingArgs = null
    }

    fun snapshot(): List<AgentIterationRecord> {
        if (pendingThought != null && pendingAction == null) {
            iterations += AgentIterationRecord(
                thought = pendingThought!!,
                action = null,
                arguments = null,
                observation = null,
            )
            pendingThought = null
        }
        return iterations.toList()
    }
}
