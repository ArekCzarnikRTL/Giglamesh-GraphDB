package com.agentwork.graphmesh.storage.vector

object CollectionNaming {
    fun physicalName(logicalName: String, dimension: Int): String = "${logicalName}_${dimension}"
    fun prefixPattern(logicalName: String): String = "${logicalName}_"
    fun extractDimension(physicalName: String): Int? = physicalName.substringAfterLast('_').toIntOrNull()
}
