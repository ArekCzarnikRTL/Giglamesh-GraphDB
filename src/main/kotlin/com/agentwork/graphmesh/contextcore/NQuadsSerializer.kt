package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.StoredQuad
import org.apache.jena.graph.NodeFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.sparql.core.DatasetGraphFactory
import org.apache.jena.sparql.core.Quad
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class NQuadsSerializer {

    fun serialize(quads: List<StoredQuad>): String {
        if (quads.isEmpty()) return ""

        val dsg = DatasetGraphFactory.create()
        quads.forEach { sq ->
            val s = NodeFactory.createURI(sq.subject)
            val p = NodeFactory.createURI(sq.predicate)
            val o = when (sq.objectType) {
                ObjectType.URI -> NodeFactory.createURI(sq.objectValue)
                ObjectType.LITERAL -> when {
                    sq.language.isNotEmpty() -> NodeFactory.createLiteralLang(sq.objectValue, sq.language)
                    sq.datatype.isNotEmpty() -> NodeFactory.createLiteral(
                        sq.objectValue,
                        "",
                        org.apache.jena.datatypes.TypeMapper.getInstance().getTypeByName(sq.datatype)
                    )
                    else -> NodeFactory.createLiteralString(sq.objectValue)
                }
                ObjectType.QUOTED_TRIPLE -> NodeFactory.createLiteralString(sq.objectValue)
            }
            val g = if (sq.dataset.isNotEmpty()) NodeFactory.createURI("urn:dataset:${sq.dataset}") else Quad.defaultGraphIRI
            dsg.add(Quad(g, s, p, o))
        }

        val out = ByteArrayOutputStream()
        RDFDataMgr.write(out, dsg, Lang.NQUADS)
        return out.toString(Charsets.UTF_8)
    }

    fun deserialize(nquads: String): List<StoredQuad> {
        if (nquads.isBlank()) return emptyList()

        val dsg = DatasetGraphFactory.create()
        RDFDataMgr.read(dsg, ByteArrayInputStream(nquads.toByteArray(Charsets.UTF_8)), Lang.NQUADS)

        val result = mutableListOf<StoredQuad>()
        dsg.find().forEach { quad ->
            val dataset = if (quad.graph == Quad.defaultGraphIRI || quad.graph == null) ""
            else quad.graph.uri?.removePrefix("urn:dataset:") ?: ""

            val obj = quad.`object`
            val (objectValue, objectType, datatype, language) = when {
                obj.isURI -> Quadruple(obj.uri, ObjectType.URI, "", "")
                obj.isLiteral -> {
                    val lang = obj.literalLanguage ?: ""
                    val dt = if (lang.isEmpty()) (obj.literalDatatypeURI ?: "") else ""
                    Quadruple(obj.literalLexicalForm, ObjectType.LITERAL, dt, lang)
                }
                else -> Quadruple(obj.toString(), ObjectType.LITERAL, "", "")
            }

            result.add(StoredQuad(
                subject = quad.subject.uri,
                predicate = quad.predicate.uri,
                objectValue = objectValue,
                dataset = dataset,
                objectType = objectType,
                datatype = datatype,
                language = language
            ))
        }
        return result
    }

    private data class Quadruple(val value: String, val type: ObjectType, val datatype: String, val language: String)
}
