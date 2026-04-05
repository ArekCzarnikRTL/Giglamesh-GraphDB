package com.agentwork.graphmesh.provenance

object ProvenanceNamespaces {
    const val PROV = "http://www.w3.org/ns/prov#"
    const val RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
    const val RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label"
    const val TG = "http://graphmesh.io/ontology/"
    const val TG_CONTAINS = "${TG}contains"
    const val TG_LLM_MODEL = "${TG}llmModel"
    const val PROV_ENTITY = "${PROV}Entity"
    const val PROV_ACTIVITY = "${PROV}Activity"
    const val PROV_AGENT = "${PROV}Agent"
    const val PROV_WAS_DERIVED_FROM = "${PROV}wasDerivedFrom"
    const val PROV_WAS_GENERATED_BY = "${PROV}wasGeneratedBy"
    const val PROV_USED = "${PROV}used"
    const val PROV_WAS_ASSOCIATED_WITH = "${PROV}wasAssociatedWith"
}
