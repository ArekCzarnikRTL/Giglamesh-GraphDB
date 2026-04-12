# Feature 46: SKOS Taxonomy Support

## Problem

GraphMesh kann ueber Feature 43 (RDF Import) beliebige RDF-Daten importieren — darunter
auch SKOS-Taxonomien. Diese landen als rohe Quads im `QuadStore`, aber es gibt keine
**semantische Verarbeitung**: kein Parsen von `skos:broader`/`narrower`-Hierarchien,
kein Auflisten von Concept Schemes, keine Suche nach `skos:prefLabel`/`altLabel`.

Gleichzeitig plant Feature 38 (Topic Extractor), Topics als `skos:Concept` zu speichern
und importierte SKOS-Konzepte als kontrolliertes Vokabular zu nutzen. Dafuer fehlt die
Grundlage: ein dedizierter SKOS-Layer, der Taxonomien lesen, navigieren und abfragen kann.

Ohne SKOS-Support sind folgende Use Cases nicht moeglich:

1. **Taxonomie-Navigation** — Hierarchisches Browsen von Konzepten (broader/narrower)
2. **Kontrolliertes Vokabular** — Topic Extractor (Feature 38) kann Topics nicht gegen
   bekannte SKOS-Konzepte abgleichen
3. **Facettierte Suche** — Concept Schemes als Facetten-Quelle fuer die Query-UI
4. **Interoperabilitaet** — Import/Export von Standard-Taxonomien (z.B. EuroVoc, STW,
   Wikidata-Taxonomien) ohne Informationsverlust

## Ziel

Implementierung eines `SkosService`, der SKOS-Taxonomien semantisch versteht und ueber
eine GraphQL-API navigierbar macht.

1. **SKOS-Vokabular-Konstanten** — Zentrale URI-Definitionen fuer das SKOS Core Vocabulary
   als `SkosTypes`-Objekt (analog zu `XsdTypes`/`RdfTypes`).
2. **SKOS-Parser im JenaAdapter** — Erweiterung von `JenaAdapter` um SKOS-spezifische
   Extraktion: `ConceptScheme`, `Concept`, `broader`, `narrower`, `prefLabel`, `altLabel`,
   `inScheme`, `topConceptOf`.
3. **SkosService** — Service zum Laden, Speichern und Navigieren von SKOS-Taxonomien.
   Operiert auf dem `QuadStore` und bietet Methoden wie `getConceptSchemes()`,
   `getConcepts(schemeUri)`, `getBroader(conceptUri)`, `getNarrower(conceptUri)`,
   `findByLabel(label)`.
4. **GraphQL-API** — Queries zum Browsen von Concept Schemes und Konzepten mit
   Hierarchie-Navigation.
5. **Label-Lookup** — Schnelles Finden von Konzepten anhand normalisierter Labels
   (`prefLabel`/`altLabel`), nutzbar fuer Feature 38 (Topic-Matching).

## Voraussetzungen

| Abhaengigkeit                                                         | Status    | Blocker? |
|-----------------------------------------------------------------------|-----------|----------|
| Feature 02: Cassandra Storage Layer (`QuadStore`)                     | Vorhanden | Ja       |
| Feature 07: RDF Graph Model (`Quad`, `RdfTerm`, `NamedGraph`)         | Vorhanden | Ja       |
| Feature 14: GraphQL API                                               | Vorhanden | Ja       |
| Feature 43: RDF Data Import (`RdfImportService`, `JenaAdapter`)       | Vorhanden | Ja       |

## Architektur

### SKOS-Vokabular-Konstanten

Erweiterung in `RdfTerm.kt` (analog zu `XsdTypes`, `RdfTypes`):

```kotlin
object SkosTypes {
    private const val NS = "http://www.w3.org/2004/02/skos/core#"

    const val CONCEPT = "${NS}Concept"
    const val CONCEPT_SCHEME = "${NS}ConceptScheme"
    const val IN_SCHEME = "${NS}inScheme"
    const val HAS_TOP_CONCEPT = "${NS}hasTopConcept"
    const val TOP_CONCEPT_OF = "${NS}topConceptOf"
    const val PREF_LABEL = "${NS}prefLabel"
    const val ALT_LABEL = "${NS}altLabel"
    const val HIDDEN_LABEL = "${NS}hiddenLabel"
    const val BROADER = "${NS}broader"
    const val NARROWER = "${NS}narrower"
    const val RELATED = "${NS}related"
    const val NOTE = "${NS}note"
    const val SCOPE_NOTE = "${NS}scopeNote"
    const val DEFINITION = "${NS}definition"
}
```

### Datenklassen

```kotlin
package com.agentwork.graphmesh.skos

data class SkosConceptScheme(
    val uri: String,
    val prefLabels: List<LangLabel>,
    val topConcepts: List<String>   // URIs der Top-Concepts
)

data class SkosConcept(
    val uri: String,
    val prefLabels: List<LangLabel>,
    val altLabels: List<LangLabel> = emptyList(),
    val broader: List<String> = emptyList(),    // URIs
    val narrower: List<String> = emptyList(),   // URIs
    val related: List<String> = emptyList(),    // URIs
    val inScheme: String? = null,               // URI des ConceptScheme
    val scopeNote: String? = null,
    val definition: String? = null
)
```

### SkosService

```kotlin
package com.agentwork.graphmesh.skos

import com.agentwork.graphmesh.rdf.RdfTerm
import com.agentwork.graphmesh.rdf.SkosTypes
import com.agentwork.graphmesh.storage.QuadStore
import org.springframework.stereotype.Service

@Service
class SkosService(
    private val quadStore: QuadStore
) {

    /** Alle ConceptSchemes einer Collection. */
    fun getConceptSchemes(collectionId: String): List<SkosConceptScheme>

    /** Alle Concepts eines Schemes (via skos:inScheme). */
    fun getConcepts(collectionId: String, schemeUri: String): List<SkosConcept>

    /** Top-Concepts eines Schemes (via skos:hasTopConcept / skos:topConceptOf). */
    fun getTopConcepts(collectionId: String, schemeUri: String): List<SkosConcept>

    /** Direkte Unterbegriffe (skos:narrower). */
    fun getNarrower(collectionId: String, conceptUri: String): List<SkosConcept>

    /** Direkte Oberbegriffe (skos:broader). */
    fun getBroader(collectionId: String, conceptUri: String): List<SkosConcept>

    /** Verwandte Konzepte (skos:related). */
    fun getRelated(collectionId: String, conceptUri: String): List<SkosConcept>

    /** Suche nach Konzept anhand normalisiertem Label (prefLabel + altLabel). */
    fun findByLabel(collectionId: String, label: String): List<SkosConcept>

    /** Einzelnes Konzept laden mit allen Properties. */
    fun getConcept(collectionId: String, conceptUri: String): SkosConcept?
}
```

Der `SkosService` arbeitet direkt auf dem `QuadStore` und filtert nach SKOS-Praedikaten.
Es wird kein separater Store benoetigt — die SKOS-Quads liegen bereits im Cassandra-
basierten `QuadStore` (importiert via Feature 43).

### JenaAdapter-Erweiterung

Neue Methode in `JenaAdapter` zum Extrahieren von SKOS-Strukturen aus einem Jena-Model:

```kotlin
fun extractSkosSchemes(model: Model): List<SkosConceptScheme>
fun extractSkosConcepts(model: Model): List<SkosConcept>
```

Diese Methoden werden beim RDF-Import genutzt, um SKOS-Daten zu validieren, bevor sie
als Quads gespeichert werden.

### GraphQL-Schema

```graphql
type SkosConcept {
    uri: String!
    prefLabels: [LangLabel!]!
    altLabels: [LangLabel!]!
    broader: [SkosConcept!]!
    narrower: [SkosConcept!]!
    related: [SkosConcept!]!
    inScheme: String
    scopeNote: String
    definition: String
}

type SkosConceptScheme {
    uri: String!
    prefLabels: [LangLabel!]!
    topConcepts: [SkosConcept!]!
}

type LangLabel {
    value: String!
    lang: String!
}

extend type Query {
    skosConceptSchemes(collectionId: ID!): [SkosConceptScheme!]!
    skosConcepts(collectionId: ID!, schemeUri: String!): [SkosConcept!]!
    skosConcept(collectionId: ID!, conceptUri: String!): SkosConcept
    skosSearch(collectionId: ID!, label: String!): [SkosConcept!]!
}
```

### SkosController

```kotlin
package com.agentwork.graphmesh.skos

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class SkosController(
    private val skosService: SkosService
) {

    @QueryMapping
    fun skosConceptSchemes(@Argument collectionId: String): List<SkosConceptScheme> =
        skosService.getConceptSchemes(collectionId)

    @QueryMapping
    fun skosConcepts(@Argument collectionId: String, @Argument schemeUri: String): List<SkosConcept> =
        skosService.getConcepts(collectionId, schemeUri)

    @QueryMapping
    fun skosConcept(@Argument collectionId: String, @Argument conceptUri: String): SkosConcept? =
        skosService.getConcept(collectionId, conceptUri)

    @QueryMapping
    fun skosSearch(@Argument collectionId: String, @Argument label: String): List<SkosConcept> =
        skosService.findByLabel(collectionId, label)
}
```

### Konfiguration

Keine zusaetzliche Konfiguration noetig. Der `SkosService` operiert auf den bestehenden
`QuadStore`-Daten. SKOS-Taxonomien werden ueber die existierende `importRdf`-Mutation
(Feature 43) importiert.

## Betroffene Dateien

### Backend

| Datei                                                                             | Aenderung                                           |
|-----------------------------------------------------------------------------------|-----------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/rdf/RdfTerm.kt`                          | UPDATE - `SkosTypes`-Objekt hinzufuegen             |
| `src/main/kotlin/com/agentwork/graphmesh/skos/SkosConcept.kt`                     | NEU - Datenklassen `SkosConcept`, `SkosConceptScheme`|
| `src/main/kotlin/com/agentwork/graphmesh/skos/SkosService.kt`                     | NEU - Taxonomie-Navigation auf QuadStore            |
| `src/main/kotlin/com/agentwork/graphmesh/skos/SkosController.kt`                  | NEU - GraphQL-Controller fuer SKOS-Queries          |
| `src/main/kotlin/com/agentwork/graphmesh/ontology/JenaAdapter.kt`                 | UPDATE - `extractSkosSchemes`, `extractSkosConcepts` |
| `src/main/resources/graphql/skos.graphqls`                                        | NEU - GraphQL-Schema fuer SKOS-Typen und Queries    |

### Frontend

| Datei                                                             | Aenderung                                           |
|-------------------------------------------------------------------|-----------------------------------------------------|
| `frontend/src/components/taxonomy/TaxonomyBrowser.tsx`            | NEU - Hierarchischer Taxonomy-Browser               |
| `frontend/src/app/collections/[id]/taxonomy/page.tsx`             | NEU - Taxonomy-Seite pro Collection                 |

### Tests

| Datei                                                                             | Aenderung                                           |
|-----------------------------------------------------------------------------------|-----------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/skos/SkosServiceTest.kt`                 | NEU - Unit-Tests fuer Hierarchie-Navigation         |
| `src/test/kotlin/com/agentwork/graphmesh/skos/SkosControllerTest.kt`              | NEU - Controller-Tests mit MockK                    |
| `src/test/kotlin/com/agentwork/graphmesh/ontology/JenaAdapterSkosTest.kt`         | NEU - SKOS-Extraktion aus Jena-Model                |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                        |
|-------------------|-------------|----------------------------------------------|
| Spring Boot (JVM) | Ja          | Jena, Cassandra-Clients sind JVM-only        |

## Akzeptanzkriterien

- [ ] `SkosTypes`-Objekt in `RdfTerm.kt` mit allen SKOS Core URIs.
- [ ] `SkosService.getConceptSchemes()` liefert alle `skos:ConceptScheme`-Instanzen einer Collection.
- [ ] `SkosService.getTopConcepts()` liefert Top-Concepts eines Schemes via `skos:hasTopConcept`.
- [ ] `SkosService.getNarrower()` / `getBroader()` navigieren die Hierarchie korrekt.
- [ ] `SkosService.findByLabel()` findet Konzepte anhand normalisiertem `prefLabel` und `altLabel`.
- [ ] `JenaAdapter.extractSkosSchemes()` parst SKOS-Strukturen aus Turtle/RDF-XML korrekt.
- [ ] GraphQL-Queries `skosConceptSchemes`, `skosConcepts`, `skosConcept`, `skosSearch` funktionieren.
- [ ] Import einer SKOS-Taxonomie via `importRdf` (Feature 43) + anschliessende Navigation via `SkosService`.
- [ ] `broader`/`narrower`-Felder in `SkosConcept` werden korrekt als inverse Beziehungen aufgeloest.
- [ ] Frontend-TaxonomyBrowser zeigt Hierarchie als aufklappbaren Baum mit Label und Count.
- [ ] Integrationstest: Import einer Beispiel-SKOS-Taxonomie, Navigation von Root zu Leaf.
