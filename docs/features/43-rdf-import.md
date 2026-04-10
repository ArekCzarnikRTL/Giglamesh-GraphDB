# Feature 43: RDF Data Import

## Problem

GraphMesh kann aktuell Wissen nur durch LLM-basierte Extraktion aus Dokumenten gewinnen.
Bereits strukturierte Daten in Standard-RDF-Formaten (Turtle, RDF/XML, N-Triples) — z.B. Exporte
aus Wikidata, DBpedia oder internen Triple-Stores — koennen nicht direkt importiert werden.
Der bestehende Ontologie-Import (`OntologyService`) verarbeitet nur Schemadefinitionen (Klassen, Properties),
keine Daten-Triples. Nutzer muessen ihre RDF-Daten umstaendlich manuell konvertieren oder auf die
LLM-Pipeline umleiten, die fuer unstrukturierten Text konzipiert ist.

## Ziel

Direkter Import von RDF-Datendateien in den Knowledge Graph einer Collection, unter Umgehung der
LLM-Extraktionspipeline.

1. **GraphQL-Mutation `importRdf`** — Nimmt base64-kodierten RDF-Content, Format und Collection-ID entgegen
2. **RDF-Parsing mit Apache Jena** — Unterstuetzt Turtle, RDF/XML und N-Triples ueber den vorhandenen `JenaAdapter`
3. **Direkte Persistenz in QuadStore** — Geparste Triples werden als `StoredQuad`-Objekte per `QuadStore.insertBatch()` gespeichert
4. **Optionale Embedding-Generierung** — Fuer importierte Entities koennen nachtraeglich Embeddings erzeugt werden (via Kafka-Event)
5. **Import-Statistik** — Rueckgabe von Anzahl importierter Triples, uebersprungener Eintraege und Dauer

## Voraussetzungen

| Abhaengigkeit                                            | Status        | Blocker? |
|----------------------------------------------------------|---------------|----------|
| Feature 02: Cassandra Storage Layer (QuadStore)          | Implementiert | Ja       |
| Feature 07: RDF Graph Model (StoredQuad, ObjectType)     | Implementiert | Ja       |
| Feature 08: Collection Management                        | Implementiert | Ja       |
| Feature 14: GraphQL API                                  | Implementiert | Ja       |
| Apache Jena 6.0.0 (JenaAdapter)                         | Verfuegbar    | Nein     |
| Feature 13: Document Embeddings (optional, fuer Vektoren)| Implementiert | Nein     |

## Architektur

### Unterstuetzte Formate

| Format     | Jena Lang       | MIME-Type                    | Dateiendung |
|------------|-----------------|------------------------------|-------------|
| Turtle     | `Lang.TURTLE`   | `text/turtle`                | `.ttl`      |
| RDF/XML    | `Lang.RDFXML`   | `application/rdf+xml`        | `.rdf`      |
| N-Triples  | `Lang.NTRIPLES` | `application/n-triples`      | `.nt`       |

### GraphQL Schema

```graphql
enum RdfFormat {
    TURTLE
    RDFXML
    NTRIPLES
}

input ImportRdfInput {
    collectionId: ID!
    content: String!
    format: RdfFormat!
    dataset: String
    generateEmbeddings: Boolean = false
}

type ImportRdfResult {
    tripleCount: Int!
    skippedCount: Int!
    durationMs: Long!
}

type Mutation {
    importRdf(input: ImportRdfInput!): ImportRdfResult!
}
```

### RdfImportService

```kotlin
package com.agentwork.graphmesh.rdfimport

import com.agentwork.graphmesh.ontology.JenaAdapter
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.StoredQuad
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.riot.Lang
import org.springframework.stereotype.Service

@Service
class RdfImportService(
    private val jenaAdapter: JenaAdapter,
    private val quadStore: QuadStore,
) {

    data class ImportResult(
        val tripleCount: Int,
        val skippedCount: Int,
        val durationMs: Long,
    )

    fun importRdf(
        collectionId: String,
        content: String,
        format: RdfFormat,
        dataset: String?,
    ): ImportResult {
        val start = System.currentTimeMillis()
        val model = parseContent(content, format)
        val ds = dataset ?: ""
        var imported = 0
        var skipped = 0

        val quads = mutableListOf<StoredQuad>()
        val stmtIter = model.listStatements()
        while (stmtIter.hasNext()) {
            val stmt = stmtIter.next()
            val subjectUri = stmt.subject.uri
            if (subjectUri == null) {
                skipped++
                continue
            }
            val predicateUri = stmt.predicate.uri
            val obj: RDFNode = stmt.`object`
            val (objectValue, objectType, datatype, language) = resolveObject(obj)
            if (objectValue == null) {
                skipped++
                continue
            }
            quads += StoredQuad(
                subject = subjectUri,
                predicate = predicateUri,
                objectValue = objectValue,
                dataset = ds,
                objectType = objectType,
                datatype = datatype,
                language = language,
            )
            imported++
        }

        if (quads.isNotEmpty()) {
            quadStore.insertBatch(collectionId, quads)
        }

        return ImportResult(
            tripleCount = imported,
            skippedCount = skipped,
            durationMs = System.currentTimeMillis() - start,
        )
    }

    private fun parseContent(content: String, format: RdfFormat): Model = when (format) {
        RdfFormat.TURTLE -> jenaAdapter.parseTurtle(content)
        RdfFormat.RDFXML -> jenaAdapter.parseRdfXml(content)
        RdfFormat.NTRIPLES -> jenaAdapter.parseNTriples(content)
    }

    private data class ResolvedObject(
        val value: String?,
        val type: ObjectType,
        val datatype: String,
        val language: String,
    )

    private fun resolveObject(node: RDFNode): ResolvedObject = when {
        node.isURIResource -> ResolvedObject(
            node.asResource().uri, ObjectType.URI, "", ""
        )
        node.isLiteral -> {
            val lit = node.asLiteral()
            ResolvedObject(
                lit.string, ObjectType.LITERAL,
                lit.datatypeURI ?: "", lit.language ?: ""
            )
        }
        else -> ResolvedObject(null, ObjectType.URI, "", "")
    }
}
```

### JenaAdapter Erweiterung

Der bestehende `JenaAdapter` wird um N-Triples-Parsing ergaenzt:

```kotlin
fun parseNTriples(content: String): Model {
    val model = ModelFactory.createDefaultModel()
    ByteArrayInputStream(content.toByteArray()).use { stream ->
        RDFDataMgr.read(model, stream, Lang.NTRIPLES)
    }
    return model
}
```

### GraphQL DataFetcher

```kotlin
package com.agentwork.graphmesh.rdfimport

import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.InputArgument

@DgsComponent
class RdfImportDataFetcher(
    private val rdfImportService: RdfImportService,
) {

    @DgsMutation
    fun importRdf(@InputArgument input: ImportRdfInput): ImportRdfResult {
        val result = rdfImportService.importRdf(
            collectionId = input.collectionId,
            content = input.content,
            format = input.format,
            dataset = input.dataset,
        )
        return ImportRdfResult(
            tripleCount = result.tripleCount,
            skippedCount = result.skippedCount,
            durationMs = result.durationMs,
        )
    }
}
```

### Optionale Embedding-Generierung

Wenn `generateEmbeddings = true`, wird nach dem Import ein Kafka-Event `rdf.imported`
publiziert. Ein bestehender oder neuer Consumer kann dann fuer alle neuen Subject-URIs
Embeddings generieren und in Qdrant speichern.

## Betroffene Dateien

### Backend

| Datei                                                                             | Aenderung                                 |
|-----------------------------------------------------------------------------------|-------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfFormat.kt`                 | NEU — Enum fuer Turtle/RDF-XML/N-Triples  |
| `src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt`          | NEU — Parsing + QuadStore-Insert          |
| `src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportDataFetcher.kt`      | NEU — GraphQL Mutation                    |
| `src/main/kotlin/com/agentwork/graphmesh/rdfimport/ImportRdfInput.kt`            | NEU — Input DTO (falls nicht DGS-codegen) |
| `src/main/kotlin/com/agentwork/graphmesh/ontology/JenaAdapter.kt`                | `parseNTriples()` hinzufuegen             |
| `src/main/resources/graphql/rdf-import.graphqls`                                  | NEU — Schema fuer Mutation + Typen        |

### Tests

| Datei                                                                             | Aenderung                                   |
|-----------------------------------------------------------------------------------|---------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportServiceTest.kt`      | NEU — Unit-Tests mit InMemoryQuadStore      |
| `src/test/kotlin/com/agentwork/graphmesh/ontology/JenaAdapterTest.kt`            | Test fuer `parseNTriples()` hinzufuegen     |

## Akzeptanzkriterien

- [ ] `importRdf`-Mutation akzeptiert Turtle, RDF/XML und N-Triples
- [ ] Geparste Triples werden korrekt als `StoredQuad` im `QuadStore` gespeichert
- [ ] URIs werden als `ObjectType.URI`, Literale als `ObjectType.LITERAL` mit Datatype und Language gespeichert
- [ ] Blank Nodes werden uebersprungen und in `skippedCount` gezaehlt
- [ ] Die Rueckgabe enthaelt `tripleCount`, `skippedCount` und `durationMs`
- [ ] Importierte Triples sind sofort ueber bestehende GraphQL-Queries (`triples`, `vectorSearch` nach Embedding) abrufbar
- [ ] Unit-Tests decken alle drei Formate mit Beispieldaten ab
- [ ] Bestehende Funktionalitaet (Document-Upload, LLM-Extraktion, Ontologie-Import) bleibt unberuehrt
