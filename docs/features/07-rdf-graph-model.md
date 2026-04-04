# Feature 07: RDF Graph Model

## Problem

GraphMesh braucht ein formal definiertes Datenmodell fuer die Wissensrepraesentation. Ohne ein klares RDF-Modell mit
Quad-Unterstuetzung, Named Graphs und RDF-Star (Quoted Triples) koennen weder Provenance-Tracking noch temporale
Metadaten noch Veracity-Assertions ueber Fakten abgebildet werden. Die bisherige Analyse zeigt, dass Reification (
Aussagen ueber Aussagen) der Schluessel fuer diese Anforderungen ist.

## Ziel

Definition des zentralen RDF-Datenmodells als Kotlin-Domain-Modell mit Quad-Unterstuetzung, Named Graphs, RDF-Star
Quoted Triples und deterministischer Entity-ID-Generierung.

1. **Quad-Modell** -- `Quad(Subject, Predicate, Object, Graph)` als zentrale Datenstruktur
2. **RdfTerm Sealed Class** -- `Uri`, `Literal`, `BlankNode`, `QuotedTriple` als typsichere Term-Hierarchie
3. **Named Graphs** -- `""` (Default), `urn:graph:source` (Provenance), `urn:graph:retrieval` (Explainability)
4. **Namespace Management** -- Prefix-Verwaltung und URI-Expansion/Komprimierung
5. **Deterministic Entity IDs** -- Content-Hash-basierte, reproduzierbare Entity-Identifikatoren

## Voraussetzungen

| Abhaengigkeit                                         | Status     | Blocker? |
|-------------------------------------------------------|------------|----------|
| Feature 02: Cassandra Storage Layer (fuer Persistenz) | Geplant    | Nein     |
| Kotlin Stdlib                                         | Verfuegbar | Nein     |

## Architektur

### RdfTerm Sealed Class Hierarchie

```kotlin
package com.graphmesh.rdf

/**
 * Repraesentiert einen RDF-Term.
 * Kann ein URI, ein Literal, ein Blank Node oder ein Quoted Triple sein.
 *
 * Entspricht dem RDF 1.2 Term-Konzept.
 */
sealed class RdfTerm {

    /**
     * Ein URI/IRI — ein benannter Knoten im Graphen.
     * Beispiel: "http://example.org/Alice"
     */
    data class Uri(val value: String) : RdfTerm() {
        override fun toNTriples(): String = "<$value>"
    }

    /**
     * Ein Literal — ein Datenwert mit optionalem Datentyp oder Sprach-Tag.
     *
     * Regeln (RDF-Spezifikation):
     * - Ein Literal hat ENTWEDER einen Datentyp ODER ein Sprach-Tag, nie beides
     * - Standard-Datentyp ist xsd:string
     */
    data class Literal(
        val value: String,
        val datatype: String = XsdTypes.STRING,
        val language: String? = null
    ) : RdfTerm() {
        init {
            require(language == null || datatype == XsdTypes.STRING || datatype == RdfTypes.LANG_STRING) {
                "Literal mit Sprach-Tag darf nur xsd:string oder rdf:langString als Datentyp haben"
            }
        }

        override fun toNTriples(): String = when {
            language != null -> "\"$value\"@$language"
            datatype != XsdTypes.STRING -> "\"$value\"^^<$datatype>"
            else -> "\"$value\""
        }
    }

    /**
     * Ein Blank Node — ein anonymer Knoten ohne globale Identitaet.
     * Unterstuetzt fuer Kompatibilitaet beim Laden externer RDF-Daten.
     */
    data class BlankNode(val id: String) : RdfTerm() {
        override fun toNTriples(): String = "_:$id"
    }

    /**
     * Ein Quoted Triple (RDF-Star) — ein Triple das selbst als Term verwendet wird.
     * Ermoeglicht Aussagen ueber Aussagen (Reification).
     */
    data class QuotedTriple(val triple: Triple) : RdfTerm() {
        override fun toNTriples(): String =
            "<< ${triple.subject.toNTriples()} ${triple.predicate.toNTriples()} ${triple.objectTerm.toNTriples()} >>"
    }

    /**
     * Serialisiert den Term im N-Triples-Format.
     */
    abstract fun toNTriples(): String
}
```

### Triple und Quad

```kotlin
package com.graphmesh.rdf

/**
 * Ein RDF-Triple: Subject-Predicate-Object.
 */
data class Triple(
    val subject: RdfTerm,
    val predicate: RdfTerm.Uri,    // Praedikat ist immer ein URI
    val objectTerm: RdfTerm
) {
    fun toNTriples(): String =
        "${subject.toNTriples()} ${predicate.toNTriples()} ${objectTerm.toNTriples()} ."
}

/**
 * Ein RDF-Quad: Subject-Predicate-Object-Graph.
 * Erweitert ein Triple um den Graph-Kontext.
 */
data class Quad(
    val subject: RdfTerm,
    val predicate: RdfTerm.Uri,
    val objectTerm: RdfTerm,
    val graph: String = NamedGraph.DEFAULT
) {
    val triple: Triple get() = Triple(subject, predicate, objectTerm)

    fun toNQuads(): String =
        if (graph == NamedGraph.DEFAULT)
            "${subject.toNTriples()} ${predicate.toNTriples()} ${objectTerm.toNTriples()} ."
        else
            "${subject.toNTriples()} ${predicate.toNTriples()} ${objectTerm.toNTriples()} <$graph> ."
}
```

### Named Graphs

```kotlin
package com.graphmesh.rdf

/**
 * Vordefinierte Named Graphs fuer GraphMesh.
 *
 * Named Graphs sind KEINE Sicherheitsfeature. Sie dienen der
 * Datenorganisation und Reification-Unterstuetzung.
 */
object NamedGraph {

    /**
     * Default-Graph: Kern-Wissensbasis.
     * Enthaelt extrahierte Fakten und Beziehungen.
     */
    const val DEFAULT = ""

    /**
     * Source-Graph: Extraktions-Provenance.
     * Verknuepft Fakten mit ihren Quelldokumenten.
     *
     * Beispiel: << Alice knows Bob >> supportedBy document-123
     */
    const val SOURCE = "urn:graph:source"

    /**
     * Retrieval-Graph: Query-Explainability.
     * Dokumentiert, welche Fakten bei einer Abfrage verwendet wurden.
     *
     * Beispiel: << Alice knows Bob >> usedInQuery query-456
     */
    const val RETRIEVAL = "urn:graph:retrieval"

    /**
     * Prueft ob ein Graph-Name ein bekannter Standard-Graph ist.
     */
    fun isStandardGraph(graph: String): Boolean =
        graph in setOf(DEFAULT, SOURCE, RETRIEVAL)
}
```

### XSD Datentypen und RDF-Typen

```kotlin
package com.graphmesh.rdf

/**
 * Gaengige XSD-Datentypen fuer Literals.
 */
object XsdTypes {
    const val STRING = "http://www.w3.org/2001/XMLSchema#string"
    const val INTEGER = "http://www.w3.org/2001/XMLSchema#integer"
    const val LONG = "http://www.w3.org/2001/XMLSchema#long"
    const val DOUBLE = "http://www.w3.org/2001/XMLSchema#double"
    const val FLOAT = "http://www.w3.org/2001/XMLSchema#float"
    const val BOOLEAN = "http://www.w3.org/2001/XMLSchema#boolean"
    const val DATE = "http://www.w3.org/2001/XMLSchema#date"
    const val DATE_TIME = "http://www.w3.org/2001/XMLSchema#dateTime"
    const val ANY_URI = "http://www.w3.org/2001/XMLSchema#anyURI"
}

object RdfTypes {
    const val LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"
}
```

### Namespace Management

```kotlin
package com.graphmesh.rdf

/**
 * Verwaltet Namespace-Prefixes fuer kompakte URI-Darstellung.
 */
data class Namespace(
    val prefix: String,
    val uri: String
)

class NamespaceRegistry(
    private val namespaces: MutableMap<String, String> = mutableMapOf()
) {
    init {
        // Standard-Prefixes
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

    /**
     * Expandiert einen Prefix:LocalName zu einem vollen URI.
     * Beispiel: "rdfs:label" -> "http://www.w3.org/2000/01/rdf-schema#label"
     */
    fun expand(prefixedName: String): String {
        val parts = prefixedName.split(":", limit = 2)
        if (parts.size != 2) return prefixedName
        val ns = namespaces[parts[0]] ?: return prefixedName
        return "$ns${parts[1]}"
    }

    /**
     * Komprimiert einen vollen URI zu einem Prefix:LocalName.
     * Beispiel: "http://www.w3.org/2000/01/rdf-schema#label" -> "rdfs:label"
     */
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
```

### Deterministic Entity ID Generation

```kotlin
package com.graphmesh.rdf

import java.security.MessageDigest

/**
 * Generiert deterministische Entity-IDs basierend auf dem Inhalt.
 *
 * Gleiche Entitaeten in verschiedenen Extraktionsprozessen erhalten
 * die gleiche ID (Foundation 4: Distributed Entity Resolution).
 */
object EntityIdGenerator {

    private const val NAMESPACE = "http://graphmesh.io/entity/"

    /**
     * Generiert eine deterministische URI fuer eine Entitaet basierend auf ihrem Label.
     * Normalisiert den Input (Lowercase, Trim, Whitespace-Collapse).
     */
    fun generate(label: String): RdfTerm.Uri {
        val normalized = label.trim().lowercase().replace(Regex("\\s+"), " ")
        val hash = sha256(normalized)
        return RdfTerm.Uri("${NAMESPACE}${hash}")
    }

    /**
     * Generiert eine deterministische URI basierend auf mehreren Feldern.
     * Nuetzlich fuer zusammengesetzte Entitaeten.
     */
    fun generate(vararg fields: String): RdfTerm.Uri {
        val combined = fields.joinToString("|") { it.trim().lowercase() }
        val hash = sha256(combined)
        return RdfTerm.Uri("${NAMESPACE}${hash}")
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
```

### Konvertierung zu/von StoredQuad (Cassandra)

```kotlin
package com.graphmesh.rdf

import com.graphmesh.storage.cassandra.StoredQuad
import com.graphmesh.storage.cassandra.ObjectType

/**
 * Konvertierung zwischen dem RDF-Modell und dem Cassandra-Speichermodell.
 */
object QuadConverter {

    fun toStoredQuad(quad: Quad): StoredQuad = StoredQuad(
        subject = serializeTerm(quad.subject),
        predicate = quad.predicate.value,
        objectValue = serializeTerm(quad.objectTerm),
        dataset = quad.graph,
        objectType = when (quad.objectTerm) {
            is RdfTerm.Uri -> ObjectType.URI
            is RdfTerm.Literal -> ObjectType.LITERAL
            is RdfTerm.BlankNode -> ObjectType.URI  // Blank Nodes als URI behandelt
            is RdfTerm.QuotedTriple -> ObjectType.QUOTED_TRIPLE
        },
        datatype = (quad.objectTerm as? RdfTerm.Literal)?.datatype ?: "",
        language = (quad.objectTerm as? RdfTerm.Literal)?.language ?: ""
    )

    private fun serializeTerm(term: RdfTerm): String = when (term) {
        is RdfTerm.Uri -> term.value
        is RdfTerm.Literal -> term.value
        is RdfTerm.BlankNode -> "_:${term.id}"
        is RdfTerm.QuotedTriple ->
            "<<${serializeTerm(term.triple.subject)}|${term.triple.predicate.value}|${serializeTerm(term.triple.objectTerm)}>>"
    }
}
```

## Betroffene Dateien

### Backend

| Datei                                                        | Aenderung                                                             |
|--------------------------------------------------------------|-----------------------------------------------------------------------|
| `rdf/src/main/kotlin/com/graphmesh/rdf/RdfTerm.kt`           | NEU - Sealed Class Hierarchie (Uri, Literal, BlankNode, QuotedTriple) |
| `rdf/src/main/kotlin/com/graphmesh/rdf/Triple.kt`            | NEU - Triple-Datenklasse                                              |
| `rdf/src/main/kotlin/com/graphmesh/rdf/Quad.kt`              | NEU - Quad-Datenklasse                                                |
| `rdf/src/main/kotlin/com/graphmesh/rdf/NamedGraph.kt`        | NEU - Named-Graph-Konstanten                                          |
| `rdf/src/main/kotlin/com/graphmesh/rdf/XsdTypes.kt`          | NEU - XSD-Datentyp-Konstanten                                         |
| `rdf/src/main/kotlin/com/graphmesh/rdf/RdfTypes.kt`          | NEU - RDF-Typ-Konstanten                                              |
| `rdf/src/main/kotlin/com/graphmesh/rdf/Namespace.kt`         | NEU - Namespace-Datenklasse                                           |
| `rdf/src/main/kotlin/com/graphmesh/rdf/NamespaceRegistry.kt` | NEU - Prefix-Verwaltung                                               |
| `rdf/src/main/kotlin/com/graphmesh/rdf/EntityIdGenerator.kt` | NEU - Deterministische ID-Generierung                                 |
| `rdf/src/main/kotlin/com/graphmesh/rdf/QuadConverter.kt`     | NEU - Konvertierung zu Cassandra StoredQuad                           |
| `rdf/build.gradle.kts`                                       | NEU - Gradle-Modul                                                    |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                            | Aenderung                                             |
|------------------------------------------------------------------|-------------------------------------------------------|
| `rdf/src/test/kotlin/com/graphmesh/rdf/RdfTermTest.kt`           | NEU - RdfTerm-Tests (alle 4 Varianten)                |
| `rdf/src/test/kotlin/com/graphmesh/rdf/TripleTest.kt`            | NEU - Triple-Serialisierungstests                     |
| `rdf/src/test/kotlin/com/graphmesh/rdf/QuadTest.kt`              | NEU - Quad-Tests inkl. Named Graphs                   |
| `rdf/src/test/kotlin/com/graphmesh/rdf/NamespaceRegistryTest.kt` | NEU - Expand/Compact-Tests                            |
| `rdf/src/test/kotlin/com/graphmesh/rdf/EntityIdGeneratorTest.kt` | NEU - Deterministische ID-Tests                       |
| `rdf/src/test/kotlin/com/graphmesh/rdf/QuadConverterTest.kt`     | NEU - Konvertierungstests                             |
| `rdf/src/test/kotlin/com/graphmesh/rdf/LiteralValidationTest.kt` | NEU - Literal-Validierung (Datentyp/Sprachtag-Regeln) |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                                            |
|-------------------|-------------|------------------------------------------------------------------|
| Spring Boot (JVM) | Ja          | Reines Kotlin-Datenmodell ohne Framework-Abhaengigkeiten         |
| KMP Library       | Nein        | Abhaengigkeit zu java.security.MessageDigest (EntityIdGenerator) |
| Ktor/Wasm         | Nein        | JVM-spezifische Crypto-APIs                                      |

## Akzeptanzkriterien

- [ ] `RdfTerm` Sealed Class deckt alle vier Varianten ab: Uri, Literal, BlankNode, QuotedTriple
- [ ] `Literal` erzwingt die RDF-Regel: entweder Datentyp ODER Sprach-Tag (Validierung im Konstruktor)
- [ ] `Quad` unterstuetzt alle drei Named Graphs: DEFAULT, SOURCE, RETRIEVAL
- [ ] `QuotedTriple` ermoeglicht verschachtelte Aussagen (Reification / RDF-Star)
- [ ] `toNTriples()` und `toNQuads()` erzeugen korrekte RDF-Serialisierung
- [ ] `NamespaceRegistry` expandiert und komprimiert URIs korrekt (z.B. `rdfs:label` <-> voller URI)
- [ ] Standard-Prefixes (rdf, rdfs, xsd, owl, gm, gms) sind vorregistriert
- [ ] `EntityIdGenerator` erzeugt deterministische IDs: gleicher Input -> gleiche URI
- [ ] `EntityIdGenerator` normalisiert Input (Lowercase, Trim, Whitespace-Collapse)
- [ ] `QuadConverter` konvertiert korrekt zwischen RDF-Modell und Cassandra `StoredQuad`
- [ ] Quoted Triples werden korrekt serialisiert/deserialisiert fuer Cassandra-Speicherung
- [ ] Bestehende Funktionalitaet bleibt unberuehrt
