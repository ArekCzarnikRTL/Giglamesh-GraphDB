package com.agentwork.graphmesh.agent

data class AgentQueryConfig(
    val maxIterations: Int = 10,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """Du bist ein Wissensassistent. Beantworte die Frage des Benutzers.
Verwende die verfuegbaren Tools um Informationen zu sammeln:
- knowledge_query: Frage den Knowledge Graph ab
- document_query: Suche in Dokumenten
Wenn du genug Informationen hast, gib eine ausfuehrliche Antwort."""
    }
}

data class AgentQueryResult(
    val answer: String,
    val durationMs: Long
)

data class ToolInfo(
    val name: String,
    val description: String
)
