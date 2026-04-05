package com.agentwork.graphmesh.streaming

data class StreamToken(
    val content: String,
    val type: StreamTokenType,
    val endOfMessage: Boolean = false,
    val endOfStream: Boolean = false
)

enum class StreamTokenType {
    TEXT,
    THOUGHT,
    ACTION,
    OBSERVATION,
    ANSWER,
    ERROR
}
