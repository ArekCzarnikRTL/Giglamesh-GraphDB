# Feature 47: Collection-gebundene Ontologie- und Datenverwaltung

## Problem

Ontologien und RDF-Daten werden in GraphMesh aktuell ueber separate, voneinander unabhaengige
Mechanismen verwaltet. Ontologien leben global im `OntologyStore` (Feature 20/44),
RDF-Daten werden per `importRdf`-Mutation in eine Collection importiert (Feature 43).
Es gibt keine formale Zuordnung zwischen einer Ontologie und einer Collection.

In der Praxis fuehrt das zu drei Problemen:

1. **Fehlende Zuordnung** -- Der Nutzer muss manuell wissen, welche Ontologie zu welcher
   Collection gehoert. Beim Context-Core-Export muss der Ontologie-Key aus dem Kopf eingegeben werden.
2. **Kein einheitlicher Ort** -- Ontologie-Upload passiert unter `/admin/ontologies`,
   RDF-Daten-Import nur per API. Es gibt keine Seite, auf der man fuer eine Collection
   beides an einem Ort erledigen kann.
3. **Kein Daten-Management** -- Importierte Tripel koennen nicht eingesehen, nach Dataset
   gefiltert oder geloescht werden. Es gibt keine Statistiken ueber den Datenbestand einer Collection.

## Ziel

Eine Collection-Detail-Seite und die zugehoerigen Backend-Services, die das vollstaendige
Management von Ontologien und RDF-Daten pro Collection ermoeglichen.

1. **Ontologie-Zuordnung** -- Mehrere Ontologien koennen einer Collection mit einer Rolle
   (z.B. `domain`, `upper`, `skos`) zugeordnet werden. Die Zuordnung ist n:m --
   eine Ontologie kann von mehreren Collections referenziert werden.
2. **Ontologie-Upload** -- Neue Ontologien (TTL/RDF-XML) koennen direkt im Collection-Kontext
   hochgeladen und zugeordnet werden. Bestehende globale Ontologien koennen zugeordnet werden.
3. **Ontologie-Modifikation** -- Eine zugeordnete Ontologie kann durch erneuten Upload
   einer TTL-Datei komplett ersetzt werden (Replace-Strategie).
4. **Ontologie-Entfernung** -- Die Zuordnung einer Ontologie zu einer Collection kann
   entfernt werden, ohne die Ontologie selbst zu loeschen.
5. **RDF-Daten-Upload** -- TTL/RDF-XML/N-Triples-Dateien koennen auf der Collection-Seite
   hochgeladen werden, mit optionalem Dataset-Namen und Embedding-Erzeugung.
6. **Daten-Uebersicht** -- Statistiken (Tripel-Anzahl, Entitaeten, Praedikate, Datasets)
   und Dataset-Liste mit Loeschmoeglichkeit.
7. **Daten-Loeschung** -- Alle Tripel einer Collection oder nur eines bestimmten Datasets
   koennen geloescht werden.

## Voraussetzungen

| Abhaengigkeit                                          | Status      | Blocker? |
|--------------------------------------------------------|-------------|----------|
| Feature 02: Cassandra Storage Layer (`QuadStore`)      | Implementiert | Ja     |
| Feature 07: RDF Graph Model                            | Implementiert | Ja     |
| Feature 08: Collection Management                      | Implementiert | Ja     |
| Feature 14: GraphQL API                                | Implementiert | Ja     |
| Feature 20: Ontology System (`OntologyService`)        | Implementiert | Ja     |
| Feature 43: RDF Data Import (`RdfImportService`)       | Implementiert | Ja     |
| Feature 44: Ontology Import API (`OntologyController`) | Implementiert | Ja     |

## Architektur

### Cassandra-Schema

Neue Zuordnungstabelle fuer die n:m-Beziehung zwischen Collections und Ontologien:

```sql
CREATE TABLE IF NOT EXISTS graphmesh.collection_ontologies (
    collection_id text,
    ontology_key  text,
    role          text,
    assigned_at   timestamp,
    assigned_by   text,
    PRIMARY KEY (collection_id, ontology_key)
);
```

Die Partition-Key ist `collection_id`, sodass alle Ontologien einer Collection effizient
abgefragt werden koennen. `role` ist ein freier String (z.B. `domain`, `upper`, `skos`, `custom`).

### CollectionOntologyService

```kotlin
@Service
class CollectionOntologyService(
    private val session: CqlSession,
    private val ontologyService: OntologyService
) {
    fun assign(collectionId: String, ontologyKey: String, role: String, assignedBy: String): CollectionOntologyRecord
    fun unassign(collectionId: String, ontologyKey: String)
    fun listForCollection(collectionId: String): List<CollectionOntologyRecord>
    fun listCollections(ontologyKey: String): List<String>
}

data class CollectionOntologyRecord(
    val collectionId: String,
    val ontologyKey: String,
    val role: String,
    val assignedAt: Instant,
    val assignedBy: String
)
```

### QuadStore-Erweiterungen

Neue Methoden auf dem bestehenden `QuadStore`-Interface:

```kotlin
fun deleteByDataset(collectionId: String, dataset: String): Long
fun countByDataset(collectionId: String): Map<String, Long>
fun stats(collectionId: String): CollectionDataStats
```

```kotlin
data class CollectionDataStats(
    val tripleCount: Long,
    val entityCount: Long,
    val predicateCount: Long,
    val datasets: List<String>
)
```

### GraphQL-Schema

```graphql
type CollectionOntology {
    ontologyKey: String!
    role: String!
    assignedAt: String!
    assignedBy: String!
    ontology: OntologyInfo
}

type CollectionDataStats {
    tripleCount: Int!
    entityCount: Int!
    predicateCount: Int!
    datasets: [String!]!
}

extend type Query {
    collectionOntologies(collectionId: ID!): [CollectionOntology!]!
    collectionDataStats(collectionId: ID!): CollectionDataStats!
}

extend type Mutation {
    assignOntology(collectionId: ID!, ontologyKey: String!, role: String!): CollectionOntology!
    unassignOntology(collectionId: ID!, ontologyKey: String!): Boolean!
    deleteTriples(collectionId: ID!, dataset: String): Int!
}
```

Bestehende Mutations (`importOntology`, `importRdf`) bleiben unveraendert und werden
im Frontend-Workflow kombiniert.

### GraphQL-Controller

```kotlin
@Controller
class CollectionDataController(
    private val collectionOntologyService: CollectionOntologyService,
    private val quadStore: QuadStore,
    private val ontologyService: OntologyService
) {
    @QueryMapping
    fun collectionOntologies(collectionId: String): List<CollectionOntologyPayload>

    @QueryMapping
    fun collectionDataStats(collectionId: String): CollectionDataStats

    @MutationMapping
    fun assignOntology(collectionId: String, ontologyKey: String, role: String): CollectionOntologyPayload

    @MutationMapping
    fun unassignOntology(collectionId: String, ontologyKey: String): Boolean

    @MutationMapping
    fun deleteTriples(collectionId: String, dataset: String?): Int
}
```

### Frontend

Neue Seite `/collections/[id]` mit drei Bereichen:

**Header:**
- Collection-Name, Beschreibung, Tags, Erstelldatum

**Bereich "Ontologien":**
- Tabelle: Ontologie-Key, Name, Rolle, Klassen-/Property-Anzahl, Zuordnungsdatum
- "Ontologie hochladen" — Dialog: TTL/RDF-Datei, Key, Name, Rolle. Ruft `importOntology` + `assignOntology` auf.
- "Bestehende zuordnen" — Dropdown globaler Ontologien, Rolle waehlen. Ruft `assignOntology` auf.
- Pro Zeile: "Zuordnung entfernen" → `unassignOntology`

**Bereich "RDF-Daten":**
- Statistik-Karten: Tripel, Entitaeten, Praedikate, Datasets
- "Daten hochladen" — Dialog: TTL/RDF/NT-Datei, Dataset-Name (optional), generateEmbeddings-Toggle. Ruft `importRdf` auf.
- Dataset-Liste: Name, Tripel-Anzahl, "Loeschen" → `deleteTriples(dataset)`
- "Alle Tripel loeschen" mit Bestaetigung → `deleteTriples` ohne Dataset

**Navigation:**
- Neuer Link "Collections" im Hauptmenue → `/collections` (nutzt bestehende Collection-Liste)
- Klick auf eine Collection → `/collections/[id]`

### Zusammenspiel mit bestehenden Features

- **Context Core Build (Feature 37):** Der Build-Dialog kann die zugeordneten Ontologien
  der Quell-Collection abfragen und vorselektieren, statt nur ein globales Dropdown zu zeigen.
- **Ontologie-gesteuertes Extrahieren (Feature 21):** Kann die zugeordneten Ontologien
  einer Collection nutzen statt einen manuellen Key zu erfordern.
- **Admin-Seite `/admin/ontologies` (Feature 44):** Bleibt fuer globale Verwaltung bestehen.

## Betroffene Dateien

### Backend

| Datei                                                                                           | Aenderung                                                |
|-------------------------------------------------------------------------------------------------|----------------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/collection/CollectionOntologyService.kt`              | NEU — Assign/Unassign/List fuer Ontologie-Zuordnungen    |
| `src/main/kotlin/com/agentwork/graphmesh/api/CollectionDataController.kt`                      | NEU — GraphQL-Controller fuer Queries und Mutations      |
| `src/main/resources/graphql/collection-data.graphqls`                                           | NEU — GraphQL-Schema fuer Zuordnungen und Stats          |
| `src/main/kotlin/com/agentwork/graphmesh/collection/CollectionSchemaInitializer.kt`             | UPDATE — `collection_ontologies`-Tabelle erzeugen        |
| `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt`                                 | UPDATE — `deleteByDataset`, `countByDataset`, `stats`    |

### Frontend

| Datei                                                                       | Aenderung                                                  |
|-----------------------------------------------------------------------------|------------------------------------------------------------|
| `frontend/src/app/collections/page.tsx`                                     | NEU — Collection-Liste (nutzt bestehende Query)            |
| `frontend/src/app/collections/[id]/page.tsx`                                | NEU — Collection-Detail mit Ontologien + Daten-Bereichen   |
| `frontend/src/components/collections/OntologySection.tsx`                   | NEU — Ontologie-Tabelle, Upload- und Zuordnungs-Dialoge   |
| `frontend/src/components/collections/DataSection.tsx`                       | NEU — Daten-Statistiken, Upload, Dataset-Liste             |
| `frontend/src/components/collections/UploadOntologyDialog.tsx`              | NEU — Dialog: TTL hochladen + Rolle waehlen                |
| `frontend/src/components/collections/AssignOntologyDialog.tsx`              | NEU — Dialog: Bestehende Ontologie zuordnen                |
| `frontend/src/components/collections/UploadDataDialog.tsx`                  | NEU — Dialog: RDF-Daten hochladen                          |
| `frontend/src/graphql/collection-data.ts`                                   | NEU — Apollo Queries und Mutations                         |

### Tests

| Datei                                                                                           | Aenderung                                              |
|-------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/collection/CollectionOntologyServiceTest.kt`           | NEU — Assign/Unassign/List-Tests                       |
| `src/test/kotlin/com/agentwork/graphmesh/api/CollectionDataControllerTest.kt`                   | NEU — GraphQL-Endpoint-Tests                           |

## Akzeptanzkriterien

- [ ] `assignOntology`-Mutation ordnet eine Ontologie mit Rolle einer Collection zu.
- [ ] `unassignOntology`-Mutation entfernt die Zuordnung, ohne die Ontologie zu loeschen.
- [ ] `collectionOntologies`-Query listet alle zugeordneten Ontologien mit Rolle und Metadaten.
- [ ] Ontologie-Upload auf der Collection-Seite ruft `importOntology` + `assignOntology` auf.
- [ ] Bestehende Ontologien koennen per Dropdown zugeordnet werden (ohne erneuten Upload).
- [ ] Ontologie ersetzen: erneuter Upload mit gleichem Key ueberschreibt die Ontologie (Replace).
- [ ] `collectionDataStats`-Query liefert Tripel-Anzahl, Entitaeten, Praedikate und Dataset-Liste.
- [ ] RDF-Daten-Upload auf der Collection-Seite ruft `importRdf` mit der Collection-ID auf.
- [ ] `deleteTriples`-Mutation loescht alle Tripel oder nur ein bestimmtes Dataset.
- [ ] Collection-Detail-Seite zeigt Ontologien und Daten mit allen CRUD-Aktionen.
- [ ] Mehrere Ontologien pro Collection mit unterschiedlichen Rollen moeglich.
- [ ] Eine Ontologie kann von mehreren Collections referenziert werden.
- [ ] Loeschen einer Collection-Zuordnung beeinflusst andere Collections nicht.
