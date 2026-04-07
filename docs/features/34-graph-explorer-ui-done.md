# Feature 34: Graph Explorer UI — Done

## Zusammenfassung

Interaktive Force-Directed-Visualisierung des Knowledge-Graphs in Next.js, gestützt
auf drei neue / erweiterte GraphQL-Endpunkte am Spring-Boot-Backend:

- `triples(..., limit: Int = 500)` — Cassandra-Quad-Query mit neuem Limit-Parameter (Hard-Cap 5000)
- `entitySearch(collectionId, prefix, limit): [String!]!` — case-insensitive Substring-Suche über Subject-URIs
- `graphMetadata(collectionId): GraphMetadata` — distinkte Datasets, Prädikate, Entitätstypen (rdf:type-Objekte)

Frontend liefert eine neue Route `/graph` mit:
- Force-Directed-Canvas (`react-force-graph-2d` per `next/dynamic({ ssr: false })`)
- Klickbare Knoten mit Detail-Panel (alle Triples)
- Subgraph-Expansion (Rechtsklick / „Nachbarn laden") ohne Duplikate
- Filter (Dataset / Prädikat / Entitätstyp) über `graphMetadata`
- Entity-Suche mit Autocomplete über `entitySearch` (Trigger ab 3 Zeichen)
- Layout-Slider (Charge, Link-Distance) und „Ansicht zentrieren"
- `?entity=<uri>`-Query-Param für vorausgewählten Knoten + Zoom

## Akzeptanzkriterien (Spec) — alle erfüllt

- [x] Backend: `triples` akzeptiert `limit` (default 500, hard cap 5000)
- [x] Backend: `entitySearch` liefert distinkte Subject-URIs per Substring
- [x] Backend: `graphMetadata` liefert distinkte Datasets/Prädikate/Entitätstypen
- [x] Backend: Storage- und API-Tests grün (`QuadStoreLimitTest` 5/5, `GraphControllerTest` 4/4)
- [x] Frontend: Route `/graph` rendert Force-Directed-Visualisierung
- [x] Frontend: Knoten typbasiert eingefärbt
- [x] Frontend: Kanten zeigen Prädikat-Label
- [x] Frontend: Klick auf Knoten öffnet Detail-Panel
- [x] Frontend: Subgraph-Expansion ohne Duplikate (Test in `useGraphData.test.tsx`)
- [x] Frontend: Filter schränken Anzeige ein
- [x] Frontend: Entity-Suche mit Autocomplete
- [x] Frontend: Layout-Slider funktionieren
- [x] Frontend: `/graph?entity=<uri>` lädt + zentriert die Entität
- [x] Frontend-Build & Test grün (`pnpm build`, 55/55 Vitest)

## Abweichungen vom Plan

1. **Quad-Schema bleibt flach.** Das ursprüngliche Feature-Dokument
   `docs/features/34-graph-explorer-ui.md` skizzierte verschachtelte `RdfTerm`-Objekte
   (`subject { type, value }`, `object { type, value, datatype, language }`) — der
   Spec hat das auf das tatsächliche, flache `Quad`-Schema (`subject/predicate/object: String`,
   `dataset`, `objectType`, `datatype`, `language`) korrigiert. Implementierung folgt dem Spec.

2. **Feldname `dataset` statt `graph`.** Wie im Spec dokumentiert.

3. **Entity-Suche & Filter über neue Backend-Queries** statt clientseitige Aggregation
   oder `vectorSearch` (das nur Chunks zurückgibt). Beide Backend-Queries verwenden im
   MVP einen Full-Scan über die Collection (mit TODO-Marker für spätere CQL-Indexierung).

4. **`?entity=<uri>` als Query-Param**, nicht als zweite Route `/graph/[entityId]`.
   Spec-Entscheidung zugunsten Einfachheit.

5. **EntitySearch-Trigger ab 3 Zeichen** (statt 2). Vermeidet Mehrfach-Queries beim
   Tippen und vereinfacht Test-Mocks.

6. **GraphCanvas-Test verwendet Mock-Fallback** statt echtem `canvas`-Polyfill-Rendering.
   Das `canvas`-npm-Paket ließ sich zwar installieren (v3.2.3), aber `react-force-graph`
   ruft beim Init `getContext("2d").scale(...)` auf, was unter jsdom `null` liefert.
   Fallback war im Plan dokumentiert; aktiver Test prüft, dass `graphData` korrekt an
   den dynamisch geladenen `ForceGraph2D` weitergereicht wird.

7. **Pre-existing TS-/Lint-Fixes in Task 15.** Beim `pnpm build`-Run wurden zwei
   TypeScript-Probleme aufgedeckt (lose Node-Callback-Typen in `GraphCanvas`,
   `any[]`-Mocks-Param in `useGraphData.test.tsx`); beide minimal gefixt.

8. **`RDF_TYPE_URI`-Konstante extrahiert** als Top-Level-Konstante in
   `com.agentwork.graphmesh.storage.QuadStore.kt` (Code-Review-Feedback aus Task 3).

## Tech Debt / Offene Punkte

- **Full-Scan-MVP für `findSubjects` und `aggregateMetadata`** (Cassandra). Bei großen
  Collections (>100k Quads) durch CQL-Materialized-View oder Cassandra-Aggregation
  ersetzen. TODO-Kommentar steht im Code (`QuadStoreService.kt`).
- **Backend-`triples` filtert nur ein einzelnes Dataset/Prädikat.** Multi-Select im
  Frontend-Filter fällt dann auf Client-Side-Filterung über die zurückgegebenen Quads
  zurück. Spätere Backend-Erweiterung auf List-Filter wäre schön.
- **GraphCanvas-Test ist ein Mock-Test**, nicht ein echtes Canvas-Render. Sobald die
  jsdom/canvas-Integration besser greift, könnte er auf echtes Rendering umgestellt
  werden.
- **EntitySearch hat noch kein Debouncing.** Bei schnellem Tippen feuert sie pro
  Keystroke ab Länge 3. Für Production sinnvoll: 200ms Debounce.

## Geänderte Dateien

### Backend (Spring Boot / Kotlin)
- `src/main/resources/graphql/schema.graphqls`
- `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt`
- `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt`
- `src/main/kotlin/com/agentwork/graphmesh/storage/GraphMetadataView.kt` *(neu)*
- `src/main/kotlin/com/agentwork/graphmesh/api/GraphController.kt`
- `src/test/kotlin/com/agentwork/graphmesh/storage/InMemoryQuadStore.kt`
- `src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreLimitTest.kt` *(neu)*
- `src/test/kotlin/com/agentwork/graphmesh/api/GraphControllerTest.kt` *(neu)*
- `src/test/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventConsumerTest.kt` *(local QuadStore stub aktualisiert)*
- `src/test/kotlin/com/agentwork/graphmesh/provenance/query/ExplanationChainLoaderTest.kt` *(local QuadStore stub aktualisiert)*

### Frontend (Next.js / React / Apollo v4)
- `frontend/package.json` — `react-force-graph-2d`, `d3-force`, `-D canvas`
- `frontend/src/types/graph.ts` *(neu)*
- `frontend/src/lib/graph/transforms.ts` *(neu)*
- `frontend/src/graphql/graph.ts` *(neu)*
- `frontend/src/hooks/useGraphData.ts` *(neu)*
- `frontend/src/components/graph/GraphCanvas.tsx` *(neu)*
- `frontend/src/components/graph/GraphControls.tsx` *(neu)*
- `frontend/src/components/graph/GraphFilter.tsx` *(neu)*
- `frontend/src/components/graph/NodeDetail.tsx` *(neu)*
- `frontend/src/components/graph/EntitySearch.tsx` *(neu)*
- `frontend/src/app/graph/page.tsx` *(neu)*

### Tests
- `frontend/src/__tests__/lib/graph/transforms.test.ts` *(neu, 12 Tests)*
- `frontend/src/__tests__/hooks/useGraphData.test.tsx` *(neu, 2 Tests)*
- `frontend/src/__tests__/components/graph/NodeDetail.test.tsx` *(neu, 2 Tests)*
- `frontend/src/__tests__/components/graph/GraphFilter.test.tsx` *(neu, 2 Tests)*
- `frontend/src/__tests__/components/graph/EntitySearch.test.tsx` *(neu, 1 Test)*
- `frontend/src/__tests__/components/graph/GraphCanvas.test.tsx` *(neu, 1 Test, Mock-Variante)*

## Test-Ergebnisse

- **Backend:** `QuadStoreLimitTest` 5/5 ✅, `GraphControllerTest` 4/4 ✅,
  `QuadStoreDefaultMethodsTest` (Regression) ✅
- **Frontend:** `pnpm test` → 26 Files / 55 Tests ✅
- **Frontend-Build:** `pnpm build` → success, `/graph` als statische Route
  (8.19 kB / 173 kB First-Load JS)

Pre-existing Backend-Integrationstests (`QdrantVectorStoreIntegrationTest` etc.) schlagen
weiterhin fehl, da sie Docker brauchen — siehe `project_known_build_issues` in der Memory.
Alle neuen Tests sind grün.
