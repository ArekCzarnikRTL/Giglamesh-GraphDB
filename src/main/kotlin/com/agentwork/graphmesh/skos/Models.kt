package com.agentwork.graphmesh.skos

import com.agentwork.graphmesh.ontology.LangLabel

data class SkosConcept(
    val uri: String,
    val prefLabels: List<LangLabel>,
    val altLabels: List<LangLabel> = emptyList(),
    val broader: List<String> = emptyList(),
    val narrower: List<String> = emptyList(),
    val related: List<String> = emptyList(),
    val inScheme: String? = null,
    val scopeNote: String? = null,
    val definition: String? = null
)

data class SkosConceptScheme(
    val uri: String,
    val prefLabels: List<LangLabel>,
    val topConcepts: List<String> = emptyList()
)
