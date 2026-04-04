package com.agentwork.graphmesh.storage

data class StoredQuad(
    val subject: String,
    val predicate: String,
    val objectValue: String,
    val dataset: String,
    val objectType: ObjectType = ObjectType.URI,
    val datatype: String = "",
    val language: String = ""
)

enum class ObjectType(val code: String) {
    URI("U"),
    LITERAL("L"),
    QUOTED_TRIPLE("T");

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: String): ObjectType = byCode.getValue(code)
    }
}

data class QuadQuery(
    val subject: String? = null,
    val predicate: String? = null,
    val objectValue: String? = null,
    val dataset: String? = null,
)
