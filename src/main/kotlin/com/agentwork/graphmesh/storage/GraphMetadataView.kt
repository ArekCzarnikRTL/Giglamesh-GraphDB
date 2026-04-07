package com.agentwork.graphmesh.storage

/**
 * Aggregate view over a collection's quads, used by the
 * `graphMetadata` GraphQL query to populate filter dropdowns.
 *
 * Each list is alphabetically sorted and capped to 200 entries.
 */
data class GraphMetadataView(
    val datasets: List<String>,
    val predicates: List<String>,
    val entityTypes: List<String>
)
