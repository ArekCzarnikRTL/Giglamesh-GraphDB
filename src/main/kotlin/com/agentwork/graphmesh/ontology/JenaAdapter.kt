package com.agentwork.graphmesh.ontology

import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.Resource
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.vocabulary.OWL2
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.RDFS
import org.apache.jena.vocabulary.XSD
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.StringWriter

@Component
class JenaAdapter {

    fun toJenaModel(ontology: Ontology): Model {
        val model = ModelFactory.createDefaultModel()
        model.setNsPrefix("owl", OWL2.NS)
        model.setNsPrefix("rdfs", RDFS.uri)
        model.setNsPrefix("rdf", RDF.uri)
        model.setNsPrefix("xsd", XSD.NS)

        val ns = ontology.metadata.namespace
        if (ns.isNotBlank()) {
            model.setNsPrefix("", ns)
        }

        for ((_, cls) in ontology.classes) {
            val resource = model.createResource(cls.uri)
            resource.addProperty(RDF.type, OWL2.Class)

            for (parentId in cls.subClassOf) {
                ontology.classes[parentId]?.let { parent ->
                    resource.addProperty(RDFS.subClassOf, model.createResource(parent.uri))
                }
            }

            for (eqId in cls.equivalentClasses) {
                ontology.classes[eqId]?.let { eq ->
                    resource.addProperty(OWL2.equivalentClass, model.createResource(eq.uri))
                }
            }

            for (disjId in cls.disjointWith) {
                ontology.classes[disjId]?.let { disj ->
                    resource.addProperty(OWL2.disjointWith, model.createResource(disj.uri))
                }
            }

            addLabelsAndComment(model, resource, cls.labels, cls.comment)
        }

        for ((_, prop) in ontology.objectProperties) {
            val resource = model.createResource(prop.uri)
            resource.addProperty(RDF.type, OWL2.ObjectProperty)

            if (prop.functional) {
                resource.addProperty(RDF.type, OWL2.FunctionalProperty)
            }
            if (prop.inverseFunctional) {
                resource.addProperty(RDF.type, OWL2.InverseFunctionalProperty)
            }

            prop.domain?.let { domainId ->
                ontology.classes[domainId]?.let { domainCls ->
                    resource.addProperty(RDFS.domain, model.createResource(domainCls.uri))
                }
            }
            prop.range?.let { rangeId ->
                ontology.classes[rangeId]?.let { rangeCls ->
                    resource.addProperty(RDFS.range, model.createResource(rangeCls.uri))
                }
            }
            prop.inverseOf?.let { inverseId ->
                ontology.objectProperties[inverseId]?.let { inverseProp ->
                    resource.addProperty(OWL2.inverseOf, model.createResource(inverseProp.uri))
                }
            }

            addLabelsAndComment(model, resource, prop.labels, prop.comment)
        }

        for ((_, prop) in ontology.datatypeProperties) {
            val resource = model.createResource(prop.uri)
            resource.addProperty(RDF.type, OWL2.DatatypeProperty)

            if (prop.functional) {
                resource.addProperty(RDF.type, OWL2.FunctionalProperty)
            }

            prop.domain?.let { domainId ->
                ontology.classes[domainId]?.let { domainCls ->
                    resource.addProperty(RDFS.domain, model.createResource(domainCls.uri))
                }
            }

            resource.addProperty(RDFS.range, model.createResource(prop.range))

            addLabelsAndComment(model, resource, prop.labels, prop.comment)
        }

        return model
    }

    fun fromJenaModel(model: Model, metadata: OntologyMetadata): Ontology {
        val classes = mutableMapOf<String, OntologyClass>()
        val objectProperties = mutableMapOf<String, ObjectProperty>()
        val datatypeProperties = mutableMapOf<String, DatatypeProperty>()

        val classResources = model.listResourcesWithProperty(RDF.type, OWL2.Class)
        while (classResources.hasNext()) {
            val resource = classResources.next()
            val uri = resource.uri ?: continue
            val id = extractId(uri)

            val subClassOf = model.listStatements(resource, RDFS.subClassOf, null as Resource?)
                .toList().mapNotNull { it.`object`.asResource().uri?.let { u -> extractId(u) } }

            val equivalentClasses = model.listStatements(resource, OWL2.equivalentClass, null as Resource?)
                .toList().mapNotNull { it.`object`.asResource().uri?.let { u -> extractId(u) } }

            val disjointWith = model.listStatements(resource, OWL2.disjointWith, null as Resource?)
                .toList().mapNotNull { it.`object`.asResource().uri?.let { u -> extractId(u) } }

            val labels = extractLabels(model, resource)
            val comment = model.listStatements(resource, RDFS.comment, null as org.apache.jena.rdf.model.RDFNode?)
                .toList().firstOrNull()?.`object`?.asLiteral()?.string

            classes[id] = OntologyClass(
                id = id, uri = uri, labels = labels, comment = comment,
                subClassOf = subClassOf, equivalentClasses = equivalentClasses, disjointWith = disjointWith
            )
        }

        val objPropResources = model.listResourcesWithProperty(RDF.type, OWL2.ObjectProperty)
        while (objPropResources.hasNext()) {
            val resource = objPropResources.next()
            val uri = resource.uri ?: continue
            val id = extractId(uri)

            val domain = model.listStatements(resource, RDFS.domain, null as Resource?)
                .toList().firstOrNull()?.`object`?.asResource()?.uri?.let { extractId(it) }
            val range = model.listStatements(resource, RDFS.range, null as Resource?)
                .toList().firstOrNull()?.`object`?.asResource()?.uri?.let { extractId(it) }
            val inverseOf = model.listStatements(resource, OWL2.inverseOf, null as Resource?)
                .toList().firstOrNull()?.`object`?.asResource()?.uri?.let { extractId(it) }
            val functional = model.contains(resource, RDF.type, OWL2.FunctionalProperty)
            val inverseFunctional = model.contains(resource, RDF.type, OWL2.InverseFunctionalProperty)
            val labels = extractLabels(model, resource)
            val comment = model.listStatements(resource, RDFS.comment, null as org.apache.jena.rdf.model.RDFNode?)
                .toList().firstOrNull()?.`object`?.asLiteral()?.string

            objectProperties[id] = ObjectProperty(
                id = id, uri = uri, labels = labels, comment = comment,
                domain = domain, range = range, inverseOf = inverseOf,
                functional = functional, inverseFunctional = inverseFunctional
            )
        }

        val dtPropResources = model.listResourcesWithProperty(RDF.type, OWL2.DatatypeProperty)
        while (dtPropResources.hasNext()) {
            val resource = dtPropResources.next()
            val uri = resource.uri ?: continue
            val id = extractId(uri)

            val domain = model.listStatements(resource, RDFS.domain, null as Resource?)
                .toList().firstOrNull()?.`object`?.asResource()?.uri?.let { extractId(it) }
            val range = model.listStatements(resource, RDFS.range, null as Resource?)
                .toList().firstOrNull()?.`object`?.asResource()?.uri
                ?: "http://www.w3.org/2001/XMLSchema#string"
            val functional = model.contains(resource, RDF.type, OWL2.FunctionalProperty)
            val labels = extractLabels(model, resource)
            val comment = model.listStatements(resource, RDFS.comment, null as org.apache.jena.rdf.model.RDFNode?)
                .toList().firstOrNull()?.`object`?.asLiteral()?.string

            datatypeProperties[id] = DatatypeProperty(
                id = id, uri = uri, labels = labels, comment = comment,
                domain = domain, range = range, functional = functional
            )
        }

        return Ontology(
            metadata = metadata,
            classes = classes,
            objectProperties = objectProperties,
            datatypeProperties = datatypeProperties
        )
    }

    fun parseTurtle(content: String): Model {
        val model = ModelFactory.createDefaultModel()
        ByteArrayInputStream(content.toByteArray()).use { stream ->
            RDFDataMgr.read(model, stream, Lang.TURTLE)
        }
        return model
    }

    fun serializeTurtle(model: Model): String {
        val writer = StringWriter()
        RDFDataMgr.write(writer, model, Lang.TURTLE)
        return writer.toString()
    }

    fun parseNTriples(content: String): Model {
        val model = ModelFactory.createDefaultModel()
        ByteArrayInputStream(content.toByteArray()).use { stream ->
            RDFDataMgr.read(model, stream, Lang.NTRIPLES)
        }
        return model
    }

    fun parseRdfXml(content: String): Model {
        val model = ModelFactory.createDefaultModel()
        ByteArrayInputStream(content.toByteArray()).use { stream ->
            RDFDataMgr.read(model, stream, Lang.RDFXML)
        }
        return model
    }

    fun serializeRdfXml(model: Model): String {
        val writer = StringWriter()
        RDFDataMgr.write(writer, model, Lang.RDFXML)
        return writer.toString()
    }

    private fun extractId(uri: String): String {
        val fragment = uri.substringAfterLast('#', "")
        if (fragment.isNotEmpty()) return fragment
        return uri.substringAfterLast('/')
    }

    private fun extractLabels(model: Model, resource: Resource): List<LangLabel> =
        model.listStatements(resource, RDFS.label, null as org.apache.jena.rdf.model.RDFNode?)
            .toList()
            .map { stmt ->
                val literal = stmt.`object`.asLiteral()
                LangLabel(
                    value = literal.string,
                    lang = literal.language.ifEmpty { "en" }
                )
            }

    private fun addLabelsAndComment(model: Model, resource: Resource, labels: List<LangLabel>, comment: String?) {
        for (label in labels) {
            resource.addProperty(RDFS.label, model.createLiteral(label.value, label.lang))
        }
        comment?.let {
            resource.addProperty(RDFS.comment, model.createLiteral(it, "en"))
        }
    }
}
