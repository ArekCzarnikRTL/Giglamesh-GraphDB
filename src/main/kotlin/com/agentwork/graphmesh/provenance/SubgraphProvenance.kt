package com.agentwork.graphmesh.provenance

import com.agentwork.graphmesh.rdf.Triple

data class SubgraphProvenance(
    val extractedTriples: List<Triple>,
    val chunkUri: String,
    val agentLabel: String,
    val modelName: String? = null
)
