# Feature 34: Graph Explorer UI — Design

## Ziel

Interaktive Force-Directed-Visualisierung des Knowledge-Graphs in Next.js mit Knoten-Detailansicht, dynamischer Subgraph-Expansion, Filter, Entity-Suche und Layout-Slidern. Verwendet die bestehende GraphQL-API und erweitert sie um drei neue Queries.

## Abweichungen vom Feature-Dokument

`docs/features/34-graph-explorer-ui.md` ist normativ in **Intent**, aber an mehreren Stellen falsch in den **technischen Details**. Quelle der Wahrheit ist die Codebasis:

| Feature-Doc behauptet | Realität in der Codebasis |
|---|---|
| `triples`-Query liefert verschachtelte `RdfTerm`-Objekte | `Quad` ist flach: `subject/predicate/object: String`, plus `dataset`, `objectType`, `datatype`, `language` |
| Feldname `graph` | Feldname `dataset` |
| `triples` hat `limit`-Parameter | Hat keinen — wird durch dieses Spec hinzugefügt |
| `vectorSearch` liefert Entity-URIs für Autocomplete | Liefert `SearchResult { id, score, payload, text }` (Chunks) |
| `CollectionSelector(selectedId, onSelect)` | Existierender Component nutzt `useActiveCollection()`-Hook ohne Props |

## Architektur

### Backend-Erweiterungen

Drei Änderungen im GraphQL-Schema und entsprechende Resolver/Storage-Methoden:

**1. `triples`-Query bekommt `limit`-Parameter**

```graphql
triples(
    collectionId: ID!
    subject: String
    predicate: String
    object: String
    dataset: String
    limit: Int = 500
): [Quad!]!
```

`GraphController.triples` reicht `limit` an `QuadStore.query` weiter. Der `QuadStore.query`-Vertrag bekommt einen optionalen `limit: Int? = null`-Parameter; `QuadStoreService` slict das Ergebnis nach dem In-Memory-Filter (`if (limit != null) result.take(limit) else result`). Default = 500, max-Cap = 5000 (im Resolver erzwingen).

**2. Neue Query `entitySearch` — Subject-URI-Präfix-Suche**

```graphql
entitySearch(
    collectionId: ID!
    prefix: String!
    limit: Int = 20
): [String!]!
```

Liefert distinkte Subject-URIs einer Collection, deren URI den `prefix` (case-insensitive Substring) enthält. MVP-Implementierung: `QuadStore` bekommt eine neue Methode `findSubjects(collection: String, substringMatch: String, limit: Int): List<String>`, die intern alle Quads der Collection lädt (`query` mit allen Filtern null), distinkte Subjects sammelt und Substring-filtert. Vermerk im Code: später durch CQL-Index oder Materialized View ersetzbar.

**3. Neue Query `graphMetadata` — Aggregations-Übersicht**

```graphql
graphMetadata(collectionId: ID!): GraphMetadata!

type GraphMetadata {
    datasets: [String!]!
    predicates: [String!]!
    entityTypes: [String!]!
}
```

`entityTypes` = distinkte `objectValue`-Werte aller Quads, deren `predicate == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"`. `QuadStore` bekommt `aggregateMetadata(collection: String): GraphMetadata`-Methode mit Full-Scan + In-Memory-Aggregation. Limit der zurückgegebenen Listen pro Feld = 200 (alphabetisch sortiert, abgeschnitten).

**Neue Backend-Dateien / Änderungen:**

| Datei | Änderung |
|---|---|
| `src/main/resources/graphql/schema.graphqls` | `limit` auf `triples` ergänzen, `entitySearch`, `graphMetadata`, `GraphMetadata` ergänzen |
| `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt` | `query` bekommt `limit: Int?`-Parameter; neue Methoden `findSubjects` und `aggregateMetadata` |
| `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt` | Implementierung der drei neuen / geänderten Methoden |
| `src/test/kotlin/com/agentwork/graphmesh/storage/InMemoryQuadStore.kt` (falls existiert) | Gleiche neue Methoden im Fake |
| `src/main/kotlin/com/agentwork/graphmesh/api/GraphController.kt` | `limit`-Argument auf `triples`, neue Resolver `entitySearch` und `graphMetadata` |
| `src/test/kotlin/com/agentwork/graphmesh/api/GraphControllerTest.kt` | Resolver-Tests für die drei Endpunkte |
| `src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreServiceTest.kt` | Storage-Tests für `findSubjects`, `aggregateMetadata`, `query` mit `limit` |

### Frontend-Komponenten

**Routing:** Eine Route `/graph` mit optionalem `?entity=<uri>`-Suchparameter. Beim Mount: wenn `entity` gesetzt → `expandNode(entity)` + `centerOnNode(entity)`.

**Komponenten-Tree (Top-Down):**

```
app/graph/page.tsx                       # Container, Layout
├── header
│   ├── CollectionSelector               # bestehend, useActiveCollection()
│   └── EntitySearch                     # NEU - Apollo-Lazy-Query gegen entitySearch
├── GraphFilter                          # NEU - liest Optionen aus graphMetadata
├── GraphControls                        # NEU - Layout-Slider
└── flex-row
    ├── GraphCanvas                      # NEU - dynamic import, ssr:false
    └── NodeDetail (wenn selectedNode)   # NEU - lädt triples(subject=node.id)
```

**`GraphCanvas`** lädt `react-force-graph-2d` per `next/dynamic({ ssr: false })`. Akzeptiert `data: GraphData`, `layoutConfig`, `selectedNodeId`, `onNodeClick`, `onNodeRightClick`, `forwardedRef` (für `zoomToFit`/`centerAt` von außen). Custom `nodeCanvasObject` zeichnet Kreis + Label, `nodePointerAreaPaint` für Hit-Testing. `useEffect` synchronisiert d3-Force-Parameter über `fg.d3Force('charge')?.strength(...)`.

**`useGraphData(collectionId)`** Hook: hält `graphData`-State, exposes `loadInitial(filter)`, `expandNode(uri)`, `clear()`. Verwendet `useLazyQuery` von `@apollo/client/react`. Pure-Helper-Funktionen sind aus dem Hook **ausgegliedert** in `lib/graph/transforms.ts` für isolierte Unit-Tests:
- `quadsToGraphData(quads): GraphData`
- `mergeGraphData(existing, incoming, expandedNodeId?): GraphData`
- `extractLabel(uri): string`
- `inferSubjectType(uri): RdfTermType`
- `quadToEdgeId(quad): string`

### Datenfluss

```
User selects Collection (useActiveCollection)
    ↓
Mount /graph → useGraphData(collectionId)
    ↓
loadInitial(filter)  ──Apollo──▶  triples(collectionId, dataset?, predicate?, limit=500)
    ↓
quadsToGraphData(quads)  ──▶  setGraphData
    ↓
GraphCanvas rendert ForceGraph2D
    ↓
User klickt Knoten → setSelectedNode(node)
    ↓
NodeDetail mountet, ruft triples(subject=node.id, limit=50)
    ↓
User klickt „Nachbarn laden" / Rechtsklick
    ↓
expandNode(uri) feuert PARALLEL: triples(subject=uri) + triples(object=uri)
    ↓
mergeGraphData(prev, neu, uri)  → setGraphData (Knoten mit expanded=true markieren)
```

### TypeScript-Typen

```typescript
// frontend/src/types/graph.ts

export type RdfTermType = "URI" | "LITERAL" | "BLANK_NODE" | "QUOTED_TRIPLE";

export interface GraphNode {
    id: string;            // URI oder Literal-Wert
    label: string;         // extrahiertes Suffix
    type: RdfTermType;
    isSubject: boolean;    // wurde mind. einmal als Subject gesehen
    expanded: boolean;
    size: number;
}

export interface GraphEdge {
    id: string;            // `${source}|${predicate}|${target}|${dataset}`
    source: string;
    target: string;
    predicate: string;
    dataset: string;
    label: string;         // extractLabel(predicate)
}

export interface GraphData {
    nodes: GraphNode[];
    links: GraphEdge[];
}

export interface GraphFilter {
    datasets: string[];
    predicates: string[];
    entityTypes: string[];
}

export interface LayoutConfig {
    chargeStrength: number;    // default -150
    linkDistance: number;      // default 80
    centerStrength: number;    // default 0.05
    collisionRadius: number;   // default 20
}
```

### GraphQL-Queries (Frontend)

Neue Datei `frontend/src/graphql/graph.ts`:

```typescript
import { gql } from "@apollo/client";

export const GRAPH_TRIPLES_QUERY = gql`
  query GraphTriples(
    $collectionId: ID!
    $subject: String
    $predicate: String
    $object: String
    $dataset: String
    $limit: Int
  ) {
    triples(
      collectionId: $collectionId
      subject: $subject
      predicate: $predicate
      object: $object
      dataset: $dataset
      limit: $limit
    ) {
      subject
      predicate
      object
      dataset
      objectType
      datatype
      language
    }
  }
`;

export const NODE_NEIGHBORS_QUERY = gql`
  query NodeNeighbors($collectionId: ID!, $entityUri: String!, $limit: Int) {
    asSubject: triples(collectionId: $collectionId, subject: $entityUri, limit: $limit) {
      subject predicate object dataset objectType datatype language
    }
    asObject: triples(collectionId: $collectionId, object: $entityUri, limit: $limit) {
      subject predicate object dataset objectType datatype language
    }
  }
`;

export const ENTITY_SEARCH_QUERY = gql`
  query EntitySearch($collectionId: ID!, $prefix: String!, $limit: Int) {
    entitySearch(collectionId: $collectionId, prefix: $prefix, limit: $limit)
  }
`;

export const GRAPH_METADATA_QUERY = gql`
  query GraphMetadata($collectionId: ID!) {
    graphMetadata(collectionId: $collectionId) {
      datasets
      predicates
      entityTypes
    }
  }
`;
```

### Type-Inferenz für Subject-Knoten

Da das `Quad`-Schema kein explizites Subject-Typ-Feld hat, leiten wir den Typ aus dem URI-String ab:

```typescript
function inferSubjectType(uri: string): RdfTermType {
    if (uri.startsWith("_:")) return "BLANK_NODE";
    if (uri.startsWith("<<") && uri.endsWith(">>")) return "QUOTED_TRIPLE";
    return "URI";  // alles andere
}
```

Object-Knoten verwenden direkt das `objectType`-String-Feld aus dem Quad (`"URI"` / `"LITERAL"` / `"QUOTED_TRIPLE"`).

### Filter-Anwendung

`GraphFilter` schickt geänderte `GraphFilter`-Werte an `useGraphData.loadInitial(filter)`. Da Backend-`triples` nur **eine** `dataset` und **ein** `predicate` als Argument akzeptiert (keine Listen), gilt für MVP:
- Wenn genau **ein** Wert ausgewählt → an Query weiterreichen
- Wenn mehrere → kein Backend-Filter, stattdessen clientseitige Nachfilterung der zurückgegebenen Quads
- Wenn keiner → Backend-Filter null

`entityTypes` ist immer clientseitig (Backend kennt das Konzept nicht): nach Load die Quads filtern auf Subjects, deren `rdf:type`-Quad in den ausgewählten Typen liegt.

## Testing

**Backend (Kotlin):**
- `QuadStoreServiceTest`: `query` mit `limit`, `findSubjects` mit Substring-Match, `aggregateMetadata` mit gemischten rdf:type-Quads — gegen `EmbeddedCassandraExtension` oder `InMemoryQuadStore`-Fake (folge bestehenden Patterns)
- `GraphControllerTest`: `triples` mit `limit`, `entitySearch`, `graphMetadata` — Spring GraphQL `WebGraphQlTester` (existierendes Pattern)

**Frontend (Vitest + jsdom + `canvas`-Polyfill):**

Setup: `pnpm add -D canvas` im `frontend/`. Falls `canvas`-Installation fehlschlägt (Cairo/Pango fehlen), Plan-Schritt fällt zurück auf `vi.mock('react-force-graph-2d')` und entfernt die rendernden Assertions aus `GraphCanvas.test.tsx` zugunsten von Prop-Forwarding-Tests.

| Datei | Inhalt |
|---|---|
| `__tests__/lib/graph/transforms.test.ts` | Pure-Function-Unit-Tests: `quadsToGraphData`, `mergeGraphData` (keine Duplikate, expanded-Flag), `extractLabel`, `inferSubjectType` |
| `__tests__/hooks/useGraphData.test.tsx` | Apollo-MockedProvider, prüft `loadInitial` setzt State, `expandNode` mergt asSubject+asObject, doppeltes Expand verändert State nicht |
| `__tests__/components/graph/GraphCanvas.test.tsx` | Mit `canvas`-Polyfill: `render(<GraphCanvas data={fixture} />)` — prüft, dass das Canvas-Element existiert; ein Klick-Event auf den Container ruft Callback auf (vereinfacht — koordinatenbasierte Hit-Tests sind im Test fragil) |
| `__tests__/components/graph/NodeDetail.test.tsx` | Apollo-MockedProvider, prüft Triples-Liste nach Load, „Nachbarn laden"-Button-Klick |
| `__tests__/components/graph/GraphFilter.test.tsx` | Renderprüfung der MultiSelects, `onChange`-Callback bei Auswahl |
| `__tests__/components/graph/EntitySearch.test.tsx` | Apollo-MockedProvider mit Lazy-Query, Eingabe → Vorschläge, Klick auf Vorschlag triggert `onSelect`-Callback |

## Betroffene Dateien

### Backend
- `src/main/resources/graphql/schema.graphqls` *(geändert)*
- `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt` *(geändert)*
- `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt` *(geändert)*
- `src/main/kotlin/com/agentwork/graphmesh/api/GraphController.kt` *(geändert)*
- `src/test/kotlin/com/agentwork/graphmesh/api/GraphControllerTest.kt` *(neu oder geändert)*
- `src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreServiceTest.kt` *(geändert)*
- ggf. `src/test/kotlin/com/agentwork/graphmesh/storage/InMemoryQuadStore.kt` *(geändert)* — wenn vorhanden

### Frontend
- `frontend/package.json` — neue Deps `react-force-graph-2d`, `d3-force`, `-D canvas`
- `frontend/src/app/graph/page.tsx` *(neu)*
- `frontend/src/app/graph/layout.tsx` *(neu, optional)*
- `frontend/src/components/graph/GraphCanvas.tsx` *(neu)*
- `frontend/src/components/graph/GraphControls.tsx` *(neu)*
- `frontend/src/components/graph/GraphFilter.tsx` *(neu)*
- `frontend/src/components/graph/NodeDetail.tsx` *(neu)*
- `frontend/src/components/graph/EntitySearch.tsx` *(neu)*
- `frontend/src/hooks/useGraphData.ts` *(neu)*
- `frontend/src/lib/graph/transforms.ts` *(neu)*
- `frontend/src/graphql/graph.ts` *(neu)*
- `frontend/src/types/graph.ts` *(neu)*
- 6 Test-Dateien in `frontend/src/__tests__/...` *(neu)*

## Akzeptanzkriterien

- [ ] Backend: `triples` akzeptiert `limit`-Parameter (default 500, hard cap 5000)
- [ ] Backend: `entitySearch(collectionId, prefix, limit)` liefert distinkte Subject-URIs mit Substring-Match
- [ ] Backend: `graphMetadata(collectionId)` liefert distinkte `datasets`, `predicates`, `entityTypes`
- [ ] Backend: Resolver- und Storage-Tests grün (`./gradlew test`)
- [ ] Frontend: Route `/graph` rendert eine interaktive Force-Directed-Visualisierung
- [ ] Frontend: Knoten typbasiert eingefärbt (URI / LITERAL / BLANK_NODE / QUOTED_TRIPLE)
- [ ] Frontend: Kanten zeigen Prädikat-Label
- [ ] Frontend: Klick auf Knoten öffnet Detail-Panel mit allen Triples
- [ ] Frontend: Rechtsklick oder „Nachbarn laden" mergt neue Knoten/Kanten ohne Duplikate
- [ ] Frontend: Filter (Dataset, Prädikat, Typ) schränken Anzeige ein
- [ ] Frontend: Entity-Suche zeigt Vorschläge aus `entitySearch` und zentriert beim Klick auf den Treffer
- [ ] Frontend: Layout-Slider (Charge, Distance) reagieren live
- [ ] Frontend: `/graph?entity=<uri>` lädt initial diesen Knoten + Nachbarn und zentriert die Ansicht
- [ ] Frontend: `pnpm test` grün, `pnpm build` grün
- [ ] Performance: 500 Knoten / 1000 Kanten flüssig (manuelle Sichtprüfung)

## Offene Punkte / Tech-Debt für später

- `findSubjects` und `aggregateMetadata` machen aktuell Full-Scans über alle Quads einer Collection. Für große Collections später durch CQL-Materialized-Views oder Cassandra-Aggregationen ersetzen.
- Backend-`triples` unterstützt nur **ein** Dataset/Prädikat als Filter. Multi-Select im Frontend fällt teilweise auf Client-Side-Filterung zurück. Spätere Backend-Erweiterung auf Listen-Filter.
- `canvas`-Polyfill setzt System-Bibliotheken voraus. Falls CI-Setup das nicht hergibt, dokumentierter Fallback auf Mock-Strategie.
