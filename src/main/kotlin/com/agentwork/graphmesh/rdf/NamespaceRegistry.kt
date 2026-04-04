package com.agentwork.graphmesh.rdf

data class Namespace(
    val prefix: String,
    val uri: String
)

class NamespaceRegistry(
    private val namespaces: MutableMap<String, String> = mutableMapOf()
) {
    init {
        register("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
        register("rdfs", "http://www.w3.org/2000/01/rdf-schema#")
        register("xsd", "http://www.w3.org/2001/XMLSchema#")
        register("owl", "http://www.w3.org/2002/07/owl#")
        register("gm", "http://graphmesh.io/ontology/")
        register("gms", "http://graphmesh.io/schema/")
    }

    fun register(prefix: String, uri: String) {
        namespaces[prefix] = uri
    }

    fun expand(prefixedName: String): String {
        val parts = prefixedName.split(":", limit = 2)
        if (parts.size != 2) return prefixedName
        val ns = namespaces[parts[0]] ?: return prefixedName
        return "$ns${parts[1]}"
    }

    fun compact(uri: String): String {
        for ((prefix, nsUri) in namespaces) {
            if (uri.startsWith(nsUri)) {
                return "$prefix:${uri.removePrefix(nsUri)}"
            }
        }
        return uri
    }

    fun allNamespaces(): List<Namespace> =
        namespaces.map { (prefix, uri) -> Namespace(prefix, uri) }
}
