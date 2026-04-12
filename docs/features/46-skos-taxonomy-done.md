# Feature 46: SKOS Taxonomy Support — Done

## Implementierung

### Backend

- **`src/main/kotlin/.../rdf/RdfTerm.kt`** — `SkosTypes`-Objekt mit 14 SKOS Core URIs (Concept, ConceptScheme, broader, narrower, prefLabel, altLabel, etc.)
- **`src/main/kotlin/.../skos/Models.kt`** — `SkosConcept` und `SkosConceptScheme` Datenklassen, importieren `LangLabel` aus dem ontology-Package
- **`src/main/kotlin/.../skos/SkosService.kt`** — QuadStore-basierte SKOS-Navigation: `getConceptSchemes`, `getConcepts`, `getTopConcepts`, `getNarrower`, `getBroader`, `getRelated`, `findByLabel` (case-insensitive, in-memory), `getConcept`, `countConcepts`
- **`src/main/kotlin/.../skos/SkosValidator.kt`** — 5 Validierungsregeln: `MISSING_PREF_LABEL`, `DUPLICATE_PREF_LABEL_PER_LANG`, `CIRCULAR_BROADER`, `UNKNOWN_SCHEME`, `BROADER_NARROWER_ASYMMETRY`
- **`src/main/kotlin/.../skos/SkosController.kt`** — Spring GraphQL Controller mit 4 `@QueryMapping` und 5 `@SchemaMapping` fuer nested field resolution
- **`src/main/kotlin/.../ontology/JenaAdapter.kt`** — `extractSkosSchemes()`, `extractSkosConcepts()`, `extractSkosLabels()` hinzugefuegt
- **`src/main/resources/graphql/skos.graphqls`** — GraphQL-Schema mit `LangLabel`, `SkosConcept`, `SkosConceptScheme` Typen und 4 Queries

### Tests

- **`SkosServiceTest`** — 14 Unit-Tests mit InMemoryQuadStore (alle Methoden, edge cases)
- **`SkosValidatorTest`** — 6 Unit-Tests fuer alle Validierungsregeln
- **`SkosControllerTest`** — 10 Unit-Tests mit MockK fuer alle Query/SchemaMapping-Methoden
- **`JenaAdapterSkosTest`** — 4 Unit-Tests fuer SKOS-Extraktion aus Turtle

## Abweichungen vom Feature-Dokument

- **collectionId in Datenklassen**: `SkosConcept` und `SkosConceptScheme` enthalten ein `collectionId`-Feld (nicht im GraphQL-Schema exponiert), damit `@SchemaMapping`-Methoden die Collection-ID aus dem Parent-Objekt lesen koennen. Spring GraphQL propagiert Query-Arguments nicht an nested Resolver.
- **Kein Frontend**: Das Feature-Dokument listet `TaxonomyBrowser.tsx` — nicht in dieser Phase implementiert. Backend + GraphQL-API als Kern, Frontend als Follow-up.
- **SkosValidator nicht im Import-Flow integriert**: Der Validator existiert als eigenstaendige Komponente, ist aber noch nicht automatisch beim RDF-Import eingebunden. Integration in den Import-Flow kann in einem Follow-up erfolgen.

## Offene Punkte

- `SkosValidator` in den `RdfImportService`-Flow integrieren (automatische Validierung beim Import von SKOS-Daten)
- Frontend TaxonomyBrowser als Follow-up Feature
- `findByLabel` laedt alle Label-Quads in-memory — bei sehr grossen Taxonomien (>10k Concepts) sollte eine QuadStore-seitige Suche evaluiert werden
