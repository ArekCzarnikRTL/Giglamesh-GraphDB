package com.agentwork.graphmesh.extraction.topic

import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.rdf.RdfTerm
import com.agentwork.graphmesh.skos.SkosService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class TopicOntologyMatcher(
    private val ontologyService: OntologyService,
    private val skosService: SkosService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun getHints(collectionId: String): List<String> {
        val labels = mutableSetOf<String>()

        // OWL classes
        val ontology = ontologyService.get(collectionId)
        if (ontology != null) {
            for ((_, cls) in ontology.classes) {
                cls.labels.firstOrNull()?.let { labels.add(it.value) }
            }
        }

        // SKOS concepts
        val schemes = skosService.getConceptSchemes(collectionId)
        for (scheme in schemes) {
            val concepts = skosService.getConcepts(collectionId, scheme.uri)
            for (concept in concepts) {
                concept.prefLabels.firstOrNull()?.let { labels.add(it.value) }
            }
        }

        return labels.take(50)
    }

    fun resolveOrCreate(label: String, collectionId: String): RdfTerm.Uri {
        val normalized = normalize(label)
        val matches = skosService.findByLabel(collectionId, normalized)
        val exactMatch = matches.firstOrNull { concept ->
            concept.prefLabels.any { it.value.lowercase().trim() == normalized }
        }
        if (exactMatch != null) {
            logger.debug("Matched topic '{}' to existing concept {}", label, exactMatch.uri)
            return RdfTerm.Uri(exactMatch.uri)
        }
        return EntityIdGenerator.generate(normalized)
    }

    private fun normalize(topic: String): String =
        topic.trim().lowercase().replace(Regex("\\s+"), " ")
}
