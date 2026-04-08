# Feature 42: Graph Explorer auf Sigma.js umstellen — Done

## Zusammenfassung

Der Graph Explorer (Feature 34) wurde vom Canvas-basierten `react-force-graph-2d`
auf **Sigma.js v3** via `@react-sigma/core` + `graphology` migriert. Rendering
läuft jetzt über WebGL, das ForceAtlas2-Layout in einem dedizierten WebWorker,
Hover-Highlighting über `nodeReducer`/`edgeReducer` statt Custom-Canvas-Draw.
Die bestehenden Backend-Queries (`triples`, `entitySearch`, `graphMetadata`)
bleiben unverändert — reines Frontend-Refactoring.

- **Rendering:** `SigmaContainer` + `MultiDirectedGraph` (graphology) ersetzen
  `<ForceGraph2D />`. Bleibt auch bei >2000 Knoten flüssig.
- **Layout:** `useWorkerLayoutForceAtlas2` aus `@react-sigma/layout-forceatlas2`
  schiebt die Force-Simulation in einen WebWorker.
- **Hover-Highlight:** `buildNodeReducer`/`buildEdgeReducer`
  (`src/lib/graph/highlight.ts`) reduzieren beim Hover/Select die Darstellung
  auf die 1-Hop-Nachbarschaft.
- **Controls:** `ZoomControl`, `FullScreenControl`,
  `LayoutForceAtlas2Control`, `MiniMap` aus den offiziellen react-sigma-Paketen
  ersetzen die alten `GraphControls`.
- **Fokus-Navigation:** `useCamera().gotoNode` animiert beim Öffnen von
  `?entity=<uri>` bzw. aus der Entity-Suche auf den Knoten.
- **Dark Theme:** Sigma-Hover-Box + Controls + MiniMap wurden an das
  OKLCh-Dark-Theme angepasst (eigener `defaultDrawNodeHover`, CSS-Variablen
  `--sigma-*` in `globals.css`).

## Akzeptanzkriterien (Spec) — Status

- [x] `/graph` rendert den Knowledge Graph via Sigma.js/WebGL, kein
      `react-force-graph-2d` im Bundle (Dependency entfernt)
- [x] Hover auf einen Knoten faded alle nicht-benachbarten Knoten und blendet
      deren Labels aus (`buildNodeReducer` / `buildEdgeReducer`)
- [x] ForceAtlas2-Layout läuft in einem WebWorker
      (`useWorkerLayoutForceAtlas2`) und blockiert den Main-Thread nicht
- [x] Zoom, Fullscreen und Layout-Toggle sind über die react-sigma Controls
      bedienbar
- [x] MiniMap zeigt den aktuellen Viewport-Ausschnitt
- [x] Rechtsklick auf einen Knoten triggert `expandNode` und fügt neue
      Knoten/Kanten hinzu, ohne bestehende zu duplizieren
      (`useGraphData.test.tsx`)
- [x] `useGraphData` gibt eine `graphology.Graph`-Instanz (plus `version`-
      Counter) zurück, nicht mehr `{ nodes, links }`
- [x] Sigma wird ausschließlich client-seitig geladen (`GraphCanvas` ist reiner
      `next/dynamic`-Wrapper mit `ssr: false`)
- [x] Bestehende Funktionalität (Filter, NodeDetail, Collection-Auswahl,
      `?entity=<uri>`-Deep-Link) bleibt unverändert
- [ ] **Entity-Suche über `@react-sigma/graph-search`** — **nicht umgesetzt**,
      siehe Abweichung 1
- [ ] **Benchmark „2000 Knoten / 4000 Kanten >30 FPS"** wurde nicht formell
      gemessen; subjektiv stabil, Messwerkzeug fehlt (Tech Debt)

## Abweichungen vom Plan

1. **`@react-sigma/graph-search` wurde verworfen — die bestehende
   `EntitySearch`-Komponente bleibt bestehen.**
   Das Paket setzt auf ein eigenes Label-Autocomplete auf dem geladenen
   `graphology`-Graph — das funktioniert nur für bereits im Client geladene
   Knoten, nicht für die Backend-Suche über alle Subject-URIs einer Collection.
   Die vorhandene `EntitySearch` ruft `entitySearch` (GraphQL) auf und kann
   Collections mit >500 Entitäten bedienen, die nie vollständig im Client
   liegen. Stattdessen: `onSelect` → `expandNode(uri)` →
   `useCamera().gotoNode(uri)` für den gleichen UX-Effekt.
   Konsequenz: `EntitySearch.tsx` + `EntitySearch.test.tsx` bleiben,
   `@react-sigma/graph-search` landet nicht in `package.json`.

2. **`LoadGraphFromStore` lädt die Live-Instanz, nicht `graph.copy()`.**
   Der Spec schlug `loadGraph(graph.copy())` vor. In graphology 0.26 verliert
   `Graph.copy()` die `multi: true`-Option, sobald parallele Kanten neu
   hinzugefügt werden (und RDF-Graphen haben ständig mehrere Prädikate zwischen
   denselben Entitäten → Crash). Sigma beobachtet die Live-Instanz über
   graphology-Events, der `version`-Counter im Dep-Array triggert den
   erneuten `loadGraph` bei jeder Mutationsrunde. Kommentar im Code
   (`GraphCanvasInner.tsx:67`).

3. **Custom `defaultDrawNodeHover` (Dark-Theme).**
   Sigmas Default-Hover-Renderer hardcoded `context.fillStyle = "#FFF"` — auf
   dem dunklen Canvas mit hellem `labelColor` (`#E5E7EB`) entstand eine
   weiße Box mit grauer, unlesbarer Schrift. Ersetzt durch `drawDarkNodeHover`
   (abgerundete Box `#1f2638` + dezenter Rand + Schatten).

4. **`@react-sigma/*`-CSS-Variablen in `globals.css` überschrieben.**
   `@react-sigma/core/lib/style.css` definiert `--sigma-background-color` und
   `--sigma-controls-*` auf `:root` (weiß/schwarz). Da diese Datei über
   `GraphCanvasInner` (Client-Component) **nach** `globals.css` geladen wird,
   reichte ein `:root`-Override nicht aus. Fix: Override unter `html:root`
   (Spezifität 0,1,1 statt 0,1,0), Werte an OKLCh-Theme angepasst. Controls-
   SVG-Icons nutzen `fill="currentColor"` und erben die Farbe automatisch.

5. **Layout-Slider (Charge / Link-Distance) entfallen komplett.**
   Die alte `GraphControls`-Komponente wurde gelöscht — ForceAtlas2 hat andere
   Parameter (`slowDown`, `gravity`, `scalingRatio`) und der
   `LayoutForceAtlas2Control` ist ein reiner Start/Stop-Toggle. Feste
   Einstellungen in `FA2_SETTINGS` (`GraphCanvasInner.tsx:57`).

6. **Keine eigene `app/graph/layout.tsx`.** Sigma-Stylesheet wird direkt in
   `GraphCanvasInner.tsx` importiert — greift trotzdem nur auf der Client-Seite,
   weil die Datei selbst via `next/dynamic({ ssr: false })` geladen wird.
   `@react-sigma/graph-search/lib/style.css` entfällt zusammen mit dem Paket.

7. **`EntitySearch` wurde dark-theme-fähig gemacht** (Folgeaufgabe nach
   Migration). Das ursprüngliche `bg-input/30` brach in Tailwind v3, weil
   `--input` bereits einen Alpha-Kanal hat und der `/30`-Modifier dann
   fehlschlägt → Browser-Default (weiß). Fix: `bg-secondary` + `border-border`.

## Tech Debt / Offene Punkte

- **Performance-Benchmark fehlt.** Das Akzeptanzkriterium „2000/4000 >30 FPS"
  wurde nicht gemessen. Für später: `stats.js` oder `chrome://tracing` am
  realen Dataset.
- **Kein Label-Debounce / Level-of-Detail.** Bei vielen Knoten werden Labels
  via `labelRenderedSizeThreshold: 6` und `labelDensity: 0.4` ausgedünnt, aber
  kein manuelles LOD implementiert.
- **ForceAtlas2-Settings sind hardcoded.** Keine UI zum Nachjustieren; bei
  bestimmten Graph-Topologien (stark clustered) könnten andere Gravity/Scaling-
  Werte besser sein.
- **MiniMap-Viewport-Highlight dezent.** Nutzt die Sigma-Defaults — bei großen
  Zoom-Leveln ist das Viewport-Rechteck schwer zu erkennen.
- **`EntitySearch` hat weiterhin kein Debouncing** (schon in Feature 34 als
  Tech Debt vermerkt, unverändert).

## Geänderte Dateien

### Frontend (Sigma.js Migration)

- `frontend/package.json` — `react-force-graph-2d` + `d3-force` raus,
  `@react-sigma/core`, `@react-sigma/layout-core`,
  `@react-sigma/layout-forceatlas2`, `@react-sigma/minimap`, `sigma`,
  `graphology`, `graphology-types`, `graphology-layout-forceatlas2` rein
- `frontend/src/types/graph.ts` — `GraphData`-Alias entfernt,
  `NodeAttributes`/`EdgeAttributes` für graphology ergänzt
- `frontend/src/lib/graph/transforms.ts` — Rewrite rund um graphology
- `frontend/src/lib/graph/highlight.ts` *(neu)* — `buildNodeReducer` /
  `buildEdgeReducer` für Hover-/Select-Highlighting
- `frontend/src/hooks/useGraphData.ts` — Rewrite, liefert jetzt
  `{ graph, version, loadInitial, expandNode }` statt `{ nodes, links }`
- `frontend/src/components/graph/GraphCanvas.tsx` — auf reinen
  `next/dynamic`-SSR-Guard-Wrapper kollabiert
- `frontend/src/components/graph/GraphCanvasInner.tsx` *(neu)* — Sigma-Rendering,
  Worker-Layout, Events, Custom-Hover-Renderer, Fokus-Animation
- `frontend/src/components/graph/GraphControls.tsx` *(gelöscht)*
- `frontend/src/app/graph/page.tsx` — verwendet neuen Canvas + Hook,
  Layout-Slider entfernt
- `frontend/src/components/graph/EntitySearch.tsx` — bleibt erhalten, UI für
  Dark-Theme angepasst
- `frontend/src/app/globals.css` — `html:root`-Override der
  `--sigma-*`-CSS-Variablen (Controls + MiniMap im Dark-Theme)

### Tests

- `frontend/src/__tests__/lib/graph/transforms.test.ts` — an graphology-basiertes
  Transform angepasst
- `frontend/src/__tests__/lib/graph/highlight.test.ts` *(neu)* — testet die
  Reducer-Factories (Hover-Fokus, Nachbar-Highlighting, Edge-Hiding)
- `frontend/src/__tests__/hooks/useGraphData.test.tsx` — prüft graphology-
  basiertes Merging ohne Knoten/Kanten-Duplikate
- `frontend/src/__tests__/components/graph/GraphCanvas.test.tsx` *(gelöscht)* —
  obsolet, weil `GraphCanvas` jetzt nur noch ein `next/dynamic`-Wrapper ist

### Backend

Nicht betroffen — reines Frontend-Refactoring.

## Test-Ergebnisse

- **Frontend-Unit:** `pnpm test` grün inkl. neuer
  `highlight.test.ts` + aktualisierten `transforms` / `useGraphData`-Tests.
- **TypeScript:** `pnpm tsc --noEmit` liefert nur noch die pre-existing
  Apollo-`MockedProvider`-Fehler in Test-Dateien — keine neuen Fehler durch
  die Migration.
- **Backend:** Nicht berührt.

Pre-existing Backend-Integrationstests (Qdrant/Cassandra via Docker) sowie die
Apollo-v4-`MockedProvider`-Typkonflikte in Test-Dateien bleiben unverändert —
siehe `project_known_build_issues` in der Memory.
