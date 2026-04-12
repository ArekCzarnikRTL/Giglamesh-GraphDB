# Feature 46: SKOS Taxonomy Support — Design Spec

## Ueberblick

Neues Package `com.agentwork.graphmesh.skos` mit semantischer SKOS-Verarbeitung auf dem
bestehenden QuadStore. SKOS-Daten werden weiterhin ueber `importRdf` (Feature 43) importiert.
Der neue `SkosService` interpretiert die rohen Quads und macht sie ueber eine GraphQL-API
navigierbar.

## Entscheidungen

| Frage | Entscheidung | Begruendung |
|-------|-------------|-------------|
| JenaAdapter-Erweiterung | Ja, mit Validierung | SKOS-Extraktion + Konsistenzpruefung beim Import |
| LangLabel in GraphQL | Eigener Type `LangLabel { value, lang }` | Mehrsprachigkeit ist bei SKOS zentral (EuroVoc etc.) |
| Package-Struktur | Eigenes `skos`-Package | Konsistent mit Modulith-Ansatz, SKOS != OWL |
| Label-Suche | In-Memory-Filterung | Reicht fuer realistische Taxonomie-Groessen, spaeter optimierbar |
| Frontend | Nicht in dieser Phase | Backend + GraphQL-API als Kern, Frontend als Follow-up |

## Architektur

### Datenfluss

```
Import: Turtle/RDF-XML → JenaAdapter.extractSkos() → SkosValidator → RdfImportService → QuadStore
Lesen:  GraphQL Query → SkosController → SkosService → QuadStore.query() → In-Memory-Filterung
```

### Komponenten

#### 1. SkosTypes (in RdfTerm.kt)

Konstanten-Objekt analog zu `XsdTypes`/`RdfTypes`:

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

#### 2. Datenklassen (skos/Models.kt)

```kotlin
package com.agentwork.graphmesh.skos

import com.agentwork.graphmesh.ontology.LangLabel

data class SkosConcept(
    val uri: String,
    val prefLabels: List<LangLabel>,
    val altLabels: List<LangLabel> = emptyList(),
    val broader: List<String> = emptyList(),    // URIs
    val narrower: List<String> = emptyList(),   // URIs
    val related: List<String> = emptyList(),    // URIs
    val inScheme: String? = null,
    val scopeNote: String? = null,
    val definition: String? = null
)

data class SkosConceptScheme(
    val uri: String,
    val prefLabels: List<LangLabel>,
    val topConcepts: List<String> = emptyList()  // URIs
)
```

`broader`/`narrower`/`related` und `topConcepts` sind URI-Listen. Die Aufloesung zu
vollen `SkosConcept`-Objekten erfolgt lazy per `@SchemaMapping` im Controller.

#### 3. SkosService

```kotlin
@Service
class SkosService(private val quadStore: QuadStore) {

    fun getConceptSchemes(collectionId: String): List<SkosConceptScheme>
    fun getConcepts(collectionId: String, schemeUri: String): List<SkosConcept>
    fun getTopConcepts(collectionId: String, schemeUri: String): List<SkosConcept>
    fun getNarrower(collectionId: String, conceptUri: String): List<SkosConcept>
    fun getBroader(collectionId: String, conceptUri: String): List<SkosConcept>
    fun getRelated(collectionId: String, conceptUri: String): List<SkosConcept>
    fun findByLabel(collectionId: String, label: String): List<SkosConcept>
    fun getConcept(collectionId: String, conceptUri: String): SkosConcept?
    fun countConcepts(collectionId: String, schemeUri: String): Int
}
```

Implementierungsstrategie pro Methode:

- `getConceptSchemes`: `QuadStore.query(collection, QuadQuery(predicate=RDF_TYPE, objectValue=CONCEPT_SCHEME))`
  → Fuer jedes Subject die Labels und topConcepts nachladen.
- `getConcepts`: `QuadStore.query(collection, QuadQuery(predicate=IN_SCHEME, objectValue=schemeUri))`
  → Fuer jedes Subject `buildConcept()` aufrufen.
- `getTopConcepts`: `QuadStore.query(collection, QuadQuery(subject=schemeUri, predicate=HAS_TOP_CONCEPT))`
  + `QuadStore.query(collection, QuadQuery(predicate=TOP_CONCEPT_OF, objectValue=schemeUri))`
  → Union bilden, Concepts laden.
- `getNarrower`/`getBroader`/`getRelated`: `QuadStore.query(collection, QuadQuery(subject=conceptUri, predicate=NARROWER|BROADER|RELATED))`
  → Object-URIs zu Concepts aufloesen.
- `findByLabel`: Alle Quads mit `predicate=PREF_LABEL` + `predicate=ALT_LABEL` laden,
  case-insensitive `contains`-Filter auf `objectValue`, dann Concepts laden.
- `getConcept`: Alle Quads mit `subject=conceptUri` laden, zu `SkosConcept` zusammenbauen.
- `countConcepts`: `QuadStore.query(collection, QuadQuery(predicate=IN_SCHEME, objectValue=schemeUri)).size`

Hilfsmethode `buildConcept(collectionId, conceptUri)` laedt alle Quads fuer ein Subject
und mappt die Praedikate auf die `SkosConcept`-Felder.

#### 4. SkosController

```kotlin
@Controller
class SkosController(private val skosService: SkosService) {

    @QueryMapping
    fun skosConceptSchemes(@Argument collectionId: String): List<SkosConceptScheme>

    @QueryMapping
    fun skosConcepts(@Argument collectionId: String, @Argument schemeUri: String): List<SkosConcept>

    @QueryMapping
    fun skosConcept(@Argument collectionId: String, @Argument conceptUri: String): SkosConcept?

    @QueryMapping
    fun skosSearch(@Argument collectionId: String, @Argument label: String): List<SkosConcept>

    // Lazy nested resolution
    @SchemaMapping(typeName = "SkosConcept", field = "broader")
    fun broader(concept: SkosConcept, @Argument collectionId: String): List<SkosConcept>

    @SchemaMapping(typeName = "SkosConcept", field = "narrower")
    fun narrower(concept: SkosConcept, @Argument collectionId: String): List<SkosConcept>

    @SchemaMapping(typeName = "SkosConcept", field = "related")
    fun related(concept: SkosConcept, @Argument collectionId: String): List<SkosConcept>

    @SchemaMapping(typeName = "SkosConceptScheme", field = "topConcepts")
    fun topConcepts(scheme: SkosConceptScheme, @Argument collectionId: String): List<SkosConcept>

    @SchemaMapping(typeName = "SkosConceptScheme", field = "conceptCount")
    fun conceptCount(scheme: SkosConceptScheme, @Argument collectionId: String): Int
}
```

Hinweis zur `collectionId` in `@SchemaMapping`: Die collectionId wird als GraphQL-Variable
vom Client durchgereicht. Falls das nicht moeglich ist (Spring GraphQL propagiert keine
Query-Arguments an nested SchemaMapping), wird ein `DataFetchingEnvironment`-Parameter
genutzt, um die collectionId aus dem Root-Query-Kontext zu extrahieren.

#### 5. JenaAdapter-Erweiterung

Neue Methoden in `JenaAdapter`:

```kotlin
fun extractSkosSchemes(model: Model): List<SkosConceptScheme>
fun extractSkosConcepts(model: Model): List<SkosConcept>
```

Nutzt `org.apache.jena.vocabulary.SKOS` fuer typsicheren Property-Zugriff.
Extrahiert alle Scheme- und Concept-Resourcen mit Labels, Hierarchie-Relationen etc.

#### 6. SkosValidator

```kotlin
@Component
class SkosValidator {
    fun validate(schemes: List<SkosConceptScheme>, concepts: List<SkosConcept>): List<SkosValidationError>
}

data class SkosValidationError(val uri: String, val message: String)
```

Validierungsregeln:
- Jedes `skos:Concept` muss mindestens ein `skos:prefLabel` haben
- Maximal ein `prefLabel` pro Sprache pro Concept
- Keine zirkulaeren `broader`-Ketten (DFS-basierte Zykluserkennung)
- Jedes Concept mit `skos:inScheme` muss auf ein existierendes `skos:ConceptScheme` zeigen
- `broader`/`narrower`-Symmetrie: Wenn A broader B, sollte B narrower A haben (Warnung, kein Error)

### GraphQL-Schema (skos.graphqls)

```graphql
type LangLabel {
    value: String!
    lang: String!
}

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
    conceptCount: Int!
}

extend type Query {
    skosConceptSchemes(collectionId: ID!): [SkosConceptScheme!]!
    skosConcepts(collectionId: ID!, schemeUri: String!): [SkosConcept!]!
    skosConcept(collectionId: ID!, conceptUri: String!): SkosConcept
    skosSearch(collectionId: ID!, label: String!): [SkosConcept!]!
}
```

## Betroffene Dateien

### Neu

| Datei | Inhalt |
|-------|--------|
| `src/main/kotlin/.../skos/Models.kt` | `SkosConcept`, `SkosConceptScheme` |
| `src/main/kotlin/.../skos/SkosService.kt` | QuadStore-basierte SKOS-Navigation |
| `src/main/kotlin/.../skos/SkosController.kt` | GraphQL-Controller + Payload-Typen |
| `src/main/kotlin/.../skos/SkosValidator.kt` | SKOS-Konsistenzpruefung |
| `src/main/resources/graphql/skos.graphqls` | GraphQL-Schema |
| `src/test/kotlin/.../skos/SkosServiceTest.kt` | Unit-Tests SkosService |
| `src/test/kotlin/.../skos/SkosControllerTest.kt` | Controller-Tests |
| `src/test/kotlin/.../ontology/JenaAdapterSkosTest.kt` | SKOS-Extraktion Tests |
| `src/test/kotlin/.../skos/SkosValidatorTest.kt` | Validierungs-Tests |

### Geaendert

| Datei | Aenderung |
|-------|-----------|
| `src/main/kotlin/.../rdf/RdfTerm.kt` | `SkosTypes`-Objekt hinzufuegen |
| `src/main/kotlin/.../ontology/JenaAdapter.kt` | `extractSkosSchemes()`, `extractSkosConcepts()` |

## Tests

- **SkosServiceTest**: InMemoryQuadStore, Schemes/Concepts laden, Hierarchie-Navigation (broader/narrower), Label-Suche (case-insensitive, substring), leere Collection, unbekannte URIs
- **SkosControllerTest**: MockK fuer SkosService, alle @QueryMapping-Methoden, SchemaMapping-Aufloesungen
- **JenaAdapterSkosTest**: Turtle mit ConceptScheme + Concepts parsen, Labels mit Sprachen, broader/narrower-Relationen, fehlende Properties
- **SkosValidatorTest**: Fehlendes prefLabel, doppelte prefLabel pro Sprache, zirkulaere broader-Ketten, unbekanntes Scheme, broader/narrower-Asymmetrie-Warnung
