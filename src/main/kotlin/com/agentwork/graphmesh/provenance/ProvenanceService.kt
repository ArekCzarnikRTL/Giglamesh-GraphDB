package com.agentwork.graphmesh.provenance

import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.Quad
import com.agentwork.graphmesh.rdf.RdfTerm
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProvenanceService {

    fun buildSubgraphQuads(provenance: SubgraphProvenance): List<Quad> {
        val subgraphUri = "urn:graphmesh:subgraph:${UUID.randomUUID()}"
        val activityUri = "urn:graphmesh:activity:${UUID.randomUUID()}"
        val agentUri = "urn:graphmesh:agent:${provenance.agentLabel.replace(" ", "")}"

        val quads = mutableListOf<Quad>()

        // tg:contains for each extracted triple (N quads)
        for (triple in provenance.extractedTriples) {
            quads.add(Quad(
                subject = RdfTerm.Uri(subgraphUri),
                predicate = RdfTerm.Uri(ProvenanceNamespaces.TG_CONTAINS),
                objectTerm = RdfTerm.QuotedTriple(triple),
                graph = NamedGraph.SOURCE
            ))
        }

        // Subgraph metadata (2 quads)
        quads.add(Quad(
            subject = RdfTerm.Uri(subgraphUri),
            predicate = RdfTerm.Uri(ProvenanceNamespaces.PROV_WAS_DERIVED_FROM),
            objectTerm = RdfTerm.Uri(provenance.chunkUri),
            graph = NamedGraph.SOURCE
        ))
        quads.add(Quad(
            subject = RdfTerm.Uri(subgraphUri),
            predicate = RdfTerm.Uri(ProvenanceNamespaces.PROV_WAS_GENERATED_BY),
            objectTerm = RdfTerm.Uri(activityUri),
            graph = NamedGraph.SOURCE
        ))

        // Activity metadata (4-5 quads)
        quads.add(Quad(
            subject = RdfTerm.Uri(activityUri),
            predicate = RdfTerm.Uri(ProvenanceNamespaces.RDF_TYPE),
            objectTerm = RdfTerm.Uri(ProvenanceNamespaces.PROV_ACTIVITY),
            graph = NamedGraph.SOURCE
        ))
        quads.add(Quad(
            subject = RdfTerm.Uri(activityUri),
            predicate = RdfTerm.Uri(ProvenanceNamespaces.PROV_USED),
            objectTerm = RdfTerm.Uri(provenance.chunkUri),
            graph = NamedGraph.SOURCE
        ))
        quads.add(Quad(
            subject = RdfTerm.Uri(activityUri),
            predicate = RdfTerm.Uri(ProvenanceNamespaces.PROV_WAS_ASSOCIATED_WITH),
            objectTerm = RdfTerm.Uri(agentUri),
            graph = NamedGraph.SOURCE
        ))
        quads.add(Quad(
            subject = RdfTerm.Uri(activityUri),
            predicate = RdfTerm.Uri(ProvenanceNamespaces.RDFS_LABEL),
            objectTerm = RdfTerm.Literal("${provenance.agentLabel} extraction"),
            graph = NamedGraph.SOURCE
        ))

        if (provenance.modelName != null) {
            quads.add(Quad(
                subject = RdfTerm.Uri(activityUri),
                predicate = RdfTerm.Uri(ProvenanceNamespaces.TG_LLM_MODEL),
                objectTerm = RdfTerm.Literal(provenance.modelName),
                graph = NamedGraph.SOURCE
            ))
        }

        // Agent metadata (2 quads)
        quads.add(Quad(
            subject = RdfTerm.Uri(agentUri),
            predicate = RdfTerm.Uri(ProvenanceNamespaces.RDF_TYPE),
            objectTerm = RdfTerm.Uri(ProvenanceNamespaces.PROV_AGENT),
            graph = NamedGraph.SOURCE
        ))
        quads.add(Quad(
            subject = RdfTerm.Uri(agentUri),
            predicate = RdfTerm.Uri(ProvenanceNamespaces.RDFS_LABEL),
            objectTerm = RdfTerm.Literal(provenance.agentLabel),
            graph = NamedGraph.SOURCE
        ))

        return quads
    }
}
