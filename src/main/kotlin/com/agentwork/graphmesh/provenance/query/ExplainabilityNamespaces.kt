package com.agentwork.graphmesh.provenance.query

object ExplainabilityNamespaces {
    const val TG = "http://graphmesh.io/ontology/"
    const val TG_QUESTION    = "${TG}Question"
    const val TG_EXPLORATION = "${TG}Exploration"
    const val TG_FOCUS       = "${TG}Focus"
    const val TG_SYNTHESIS   = "${TG}Synthesis"
    const val TG_ANALYSIS    = "${TG}Analysis"
    const val TG_CONCLUSION  = "${TG}Conclusion"

    const val TG_QUERY_TEXT  = "${TG}queryText"
    const val TG_REASONING   = "${TG}reasoning"
    const val TG_THOUGHT     = "${TG}thought"
    const val TG_ACTION      = "${TG}action"
    const val TG_OBSERVATION = "${TG}observation"
    const val TG_ANSWER_TEXT = "${TG}answerText"
    const val TG_EDGE_COUNT  = "${TG}edgeCount"
    const val TG_MECHANISM   = "${TG}mechanism"
    const val TG_TIMESTAMP   = "${TG}timestamp"
    const val TG_ARG_KEY     = "${TG}argKey"
    const val TG_ARG_VALUE   = "${TG}argValue"
    const val TG_ITERATION_INDEX = "${TG}iterationIndex"
    const val TG_HAS_SELECTED_EDGE = "${TG}hasSelectedEdge"
}
