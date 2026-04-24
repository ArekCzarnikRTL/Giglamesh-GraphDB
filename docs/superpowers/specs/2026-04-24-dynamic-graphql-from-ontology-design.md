# Feature 61: Dynamic GraphQL from Ontology

## Zusammenfassung

Wenn ein RDF/TTL-File in eine Collection importiert wird und dieser Collection eine Ontologie zugewiesen ist, wird automatisch ein GraphQL-Schema generiert. Dieses Schema ist unter `/graphql/{collectionName}` als eigenstaendiger Endpoint erreichbar und ermoeglicht es, die gespeicherten Triples typisiert und mit Graph-Traversal abzufragen.

## Entscheidungen

| Frage | Entscheidung |
|---|---|
| Endpoint-Modell | Separater Endpoint pro Collection (`/graphql/{collectionName}`) |
| Trigger | Automatisch bei jedem `importRdf()`-Aufruf |
| Schema-Quelle | Zugewiesene Ontologie (`CollectionOntologyService`) |
| Query-Semantik | Graph-Traversal mit Filterung und Paginierung auf jedem Level |
| Schema-Update | Automatisch bei jedem Import (Schema wird neu generiert) |
| XSD-Mapping | Umfangreich mit Custom Scalars (Date, DateTime, Long) |
| Technologie | Pure graphql-java (programmatische API), kein DGS, kein Eingriff in Spring GraphQL |

## Architektur

```
TTL-Upload → RdfImportService → QuadStore
                ↓
         CollectionOntologyService (Ontologie zugewiesen?)
                ↓ ja
         DynamicGraphQlSchemaBuilder
           - liest Ontology (Classes, ObjectProperties, DatatypeProperties)
           - baut GraphQLSchema programmatisch (graphql-java)
           - registriert DataFetcher die per QuadStore.query() aufloesen
                ↓
         DynamicGraphQlRegistry
           - ConcurrentHashMap<collectionId, GraphQL>
           - Schema-Swap bei Re-Import
                ↓
         DynamicGraphQlController
           - @PostMapping("/graphql/{collectionName}")
           - Lookup Collection by Name → collectionId → GraphQL-Instanz
           - ExecutionResult → JSON Response
```

## Package

`com.agentwork.graphmesh.dynamicgraphql`

## Komponenten

| Klasse | Verantwortung |
|---|---|
| `DynamicGraphQlController` | REST-Endpoint (`/graphql/{collectionName}`), Request-Dispatch, Introspection |
| `DynamicGraphQlRegistry` | Schema-Cache (`ConcurrentHashMap<collectionId, GraphQL>`), register/get/remove |
| `DynamicGraphQlSchemaBuilder` | Ontology → `GraphQLSchema` Transformation, Schema-Lifecycle |
| `XsdScalarMapping` | XSD-URI → GraphQL Scalar Type Mapping inkl. Custom Scalars |
| `QuadDataFetcher` | Generischer DataFetcher der per `QuadStore.query()` Triples liest |
| `QuadDataLoaderFactory` | `DataLoaderRegistry`-Factory fuer Batch-Loading (N+1 Vermeidung) |

## Schema-Generierung: Mapping-Regeln

### Ontology → GraphQL Typen

| Ontology-Konzept | GraphQL-Ergebnis |
|---|---|
| `OntologyClass` | `GraphQLObjectType` (Name = localName der URI) |
| `DatatypeProperty` (domain=Class) | Feld auf dem ObjectType, Skalar-Typ aus `range` |
| `ObjectProperty` (domain=Class, range=Class) | Feld auf dem ObjectType, Typ = referenzierter ObjectType |
| `ObjectProperty.functional=true` | Einzelwert-Feld |
| `ObjectProperty.functional=false` | Listenfeld mit Paginierung |
| `subClassOf` | GraphQL Interface (Superklasse wird Interface, Subklassen implementieren es) |

### XSD → GraphQL Scalar Mapping

| XSD-Typ | GraphQL-Scalar |
|---|---|
| `xsd:string`, `rdfs:Literal` | `String` |
| `xsd:integer`, `xsd:int`, `xsd:short`, `xsd:byte` | `Int` |
| `xsd:long` | Custom `Long` Scalar |
| `xsd:float`, `xsd:double` | `Float` |
| `xsd:boolean` | `Boolean` |
| `xsd:anyURI` | `ID` |
| `xsd:date` | Custom `Date` Scalar (ISO 8601) |
| `xsd:dateTime` | Custom `DateTime` Scalar (ISO 8601) |
| Unbekannt | Fallback `String` |

### Generiertes Query-Schema (Beispiel)

Gegeben eine Ontologie mit `Person`-Klasse und Properties `name` (xsd:string), `age` (xsd:integer), `gender` (xsd:boolean), `worksAt` (ObjectProperty → Company):

```graphql
type Query {
  Person(filter: PersonFilter, limit: Int = 20, offset: Int = 0): [Person!]!
  PersonById(id: ID!): Person
}

type Person {
  id: ID!            # subject URI
  name: String
  age: Int
  gender: Boolean
  worksAt(filter: CompanyFilter, limit: Int = 10, offset: Int = 0): [Company!]!
}

input PersonFilter {
  name: String       # exakter Match auf Literal-Wert
  age: Int
  gender: Boolean
}

type Company {
  id: ID!
  name: String
  location: String
}

input CompanyFilter {
  name: String
  location: String
}
```

Jede `OntologyClass` bekommt:
- Eine Top-Level-Query als Liste mit Filter/Limit/Offset
- Eine Top-Level-Query `{ClassName}ById(id: ID!)` fuer Einzelzugriff
- Filter-Inputs aus allen `DatatypeProperty`-Feldern

`ObjectProperty`-Felder bekommen eigene `limit`/`offset`/`filter`-Argumente fuer verschachteltes Paging.

## Datenaufloesung (Resolver-Logik)

### Top-Level Query (z.B. `Person(filter, limit, offset)`)

1. `QuadStore.query(collectionId, QuadQuery(predicate=RDF_TYPE, objectValue=classUri))` liefert alle Subject-URIs vom Typ `Person`
2. Filter anwenden: fuer jedes Filter-Feld eine weitere `QuadQuery(subject=uri, predicate=propertyUri, objectValue=filterWert)`
3. Paginierung: `limit`/`offset` auf die gefilterte Subject-Liste
4. Rueckgabe: Liste von `Map<String, Any>` mit `id=subjectUri`

### Feld-Resolver (DatatypeProperty, z.B. `Person.name`)

1. Parent liefert `id` (= subject URI)
2. `QuadStore.query(collectionId, QuadQuery(subject=id, predicate=propertyUri))` liefert Literal-Wert(e)
3. Bei `functional=true`: erster Wert oder null
4. Bei `functional=false` / Liste: alle Werte
5. Typ-Konvertierung: `StoredQuad.objectValue` anhand `datatype` in den passenden Kotlin-Typ (String, Int, Boolean, Float etc.)

### ObjectProperty-Resolver (z.B. `Person.worksAt`)

1. `QuadStore.query(collectionId, QuadQuery(subject=parentId, predicate=propertyUri))` liefert Object-URIs
2. Verschachtelte `limit`/`offset`/`filter`-Args auf die URI-Liste anwenden
3. Rueckgabe: `Map<String, Any>` mit `id=objectUri` — Kind-Felder loesen sich rekursiv auf

### Batch-Loading (N+1 Vermeidung)

- `QuadDataLoaderFactory` erstellt eine `DataLoaderRegistry` pro Request
- Beim Top-Level-Query werden alle Quads fuer die Subject-URIs auf einmal geladen
- graphql-java `DataLoader`-Mechanismus batcht Feld-Aufloesungen

## Endpoint-Routing

### Controller

```kotlin
@RestController
class DynamicGraphQlController(
    private val registry: DynamicGraphQlRegistry,
    private val collectionService: CollectionService
) {
    @PostMapping("/graphql/{collectionName}")
    fun execute(
        @PathVariable collectionName: String,
        @RequestBody body: Map<String, Any>
    ): ResponseEntity<Map<String, Any>>
}
```

- Lookup: `collectionName` → `CollectionService` → `collectionId` → `DynamicGraphQlRegistry.get(collectionId)`
- Wenn kein Schema: HTTP 404 mit `{"error": "No GraphQL schema for collection '...'"}`
- `body` enthaelt Standard-GraphQL-Request (`query`, `variables`, `operationName`)
- Introspection-Queries funktionieren automatisch (graphql-java nativ)

### Registry

```kotlin
@Component
class DynamicGraphQlRegistry {
    private val schemas = ConcurrentHashMap<String, GraphQL>()
    
    fun register(collectionId: String, schema: GraphQLSchema)
    fun get(collectionId: String): GraphQL?
    fun remove(collectionId: String)
    fun has(collectionId: String): Boolean
}
```

## Schema-Lifecycle

| Event | Aktion |
|---|---|
| `importRdf()` erfolgreich + Ontologie zugewiesen | Schema (neu) generieren → `registry.register()` |
| `importRdf()` erfolgreich, keine Ontologie | Kein Schema, kein Endpoint |
| Collection geloescht | `registry.remove(collectionId)` |

Integration in `RdfImportService`: ein Aufruf am Ende von `importRdf()`:
```kotlin
dynamicGraphQlSchemaBuilder.rebuildIfOntologyAssigned(collectionId)
```

## Aenderungen an bestehenden Dateien

| Datei | Aenderung |
|---|---|
| `RdfImportService.kt` | +1 Injection (`DynamicGraphQlSchemaBuilder`), +1 Aufruf am Ende von `importRdf()` |
| `CorsConfig.kt` | `/graphql/**` statt `/graphql` (deckt statisch + dynamisch ab) |
| `build.gradle.kts` | +`com.graphql-java:graphql-java-extended-scalars` Dependency |

## Neue Dependency

```kotlin
// build.gradle.kts
implementation("com.graphql-java:graphql-java-extended-scalars:22.0")
```

Stellt `GraphQLDate`, `GraphQLDateTime`, `GraphQLLong` und weitere Custom Scalars bereit.

## Akzeptanzkriterien

1. Nach `importRdf()` mit zugewiesener Ontologie ist `/graphql/{collectionName}` erreichbar
2. Introspection-Query liefert das generierte Schema mit allen Ontologie-Klassen als Typen
3. Top-Level-Query pro Klasse liefert Entitaeten aus dem QuadStore
4. DatatypeProperty-Felder werden korrekt als typisierte Skalare aufgeloest
5. ObjectProperty-Felder traversieren den Graphen und loesen referenzierte Entitaeten auf
6. Filterung auf DatatypeProperty-Feldern funktioniert (exakter Match)
7. Paginierung (limit/offset) funktioniert auf Top-Level und verschachtelten Feldern
8. Schema-Update: erneuter `importRdf()` aktualisiert das Schema
9. Collection-Loesung entfernt das Schema aus der Registry
10. Bestehende `/graphql`-Endpoints bleiben vollstaendig unberuehrt
