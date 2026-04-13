package com.agentwork.graphmesh.storage

import com.agentwork.graphmesh.provenance.ProvenanceNamespaces
import com.agentwork.graphmesh.rdf.QuadConverter

const val RDF_TYPE_URI = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"

interface QuadStore {
    fun insert(collection: String, quad: StoredQuad)
    fun insertBatch(collection: String, quads: List<StoredQuad>)
    fun delete(collection: String, quad: StoredQuad)
    fun deleteCollection(collection: String)
    fun query(collection: String, query: QuadQuery, limit: Int? = null): List<StoredQuad>

    /**
     * Returns up to [limit] distinct subject URIs from [collection] whose
     * URI string contains [substringMatch] (case-insensitive). Result order
     * is implementation-defined; callers should not rely on it.
     */
    fun findSubjects(collection: String, substringMatch: String, limit: Int): List<String>

    /**
     * Returns distinct datasets, predicates, and entity types
     * (objects of `rdf:type` triples) for [collection]. Each list is
     * alphabetically sorted and capped at 200 entries.
     */
    fun aggregateMetadata(collection: String): GraphMetadataView

    /** Returns all quads in [collection]. Use for export only. */
    fun scrollAll(collection: String): List<StoredQuad>

    /** Returns true if [collection] has no quads. */
    fun isEmpty(collection: String): Boolean

    fun findByEntities(collection: String, entityIds: List<String>): List<StoredQuad> {
        return entityIds.flatMap { id ->
            query(collection, QuadQuery(subject = id)) +
            query(collection, QuadQuery(objectValue = id))
        }.distinct()
    }

    /**
     * Finds all provenance subgraph URIs whose `prov:wasDerivedFrom` points
     * at one of the given chunk URNs (e.g. `urn:chunk:doc-<uuid>/p1/c1`).
     *
     * Result is deduplicated.
     */
    fun findSubgraphsForChunks(collection: String, chunkUrns: List<String>): List<String> {
        if (chunkUrns.isEmpty()) return emptyList()
        return chunkUrns.flatMap { urn ->
            query(
                collection,
                QuadQuery(
                    predicate = ProvenanceNamespaces.PROV_WAS_DERIVED_FROM,
                    objectValue = urn
                )
            ).map { it.subject }
        }.distinct()
    }

    /**
     * Returns the inner knowledge triples (`<<s|p|o>>`) embedded in the
     * `tg:contains` rows of the given subgraph URIs, unpacked back into
     * regular [StoredQuad] form (objectType = URI, dataset = "").
     *
     * Note: the unpacked quads are lossy with respect to the inner object's
     * RDF type — see [QuadConverter.unpackQuotedTriple] for the warning.
     */
    fun findQuotedTriplesForSubgraphs(
        collection: String,
        subgraphUris: List<String>
    ): List<StoredQuad> {
        if (subgraphUris.isEmpty()) return emptyList()
        return subgraphUris.flatMap { sg ->
            query(
                collection,
                QuadQuery(
                    subject = sg,
                    predicate = ProvenanceNamespaces.TG_CONTAINS
                )
            ).mapNotNull { QuadConverter.unpackQuotedTriple(it) }
        }
    }

    /** Deletes all quads in [collection] with the given [dataset]. Returns count of deleted quads. */
    fun deleteByDataset(collection: String, dataset: String): Long

    /** Returns triple count, entity count, predicate count, and dataset list for [collection]. */
    fun stats(collection: String): QuadStoreStats
}

data class QuadStoreStats(
    val tripleCount: Long,
    val entityCount: Long,
    val predicateCount: Long,
    val datasets: List<String>
)
