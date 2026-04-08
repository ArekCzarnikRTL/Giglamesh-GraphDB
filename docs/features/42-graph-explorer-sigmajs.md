# Feature 42: Graph Explorer auf Sigma.js umstellen

## Problem

Der Graph Explorer (Feature 34) nutzt aktuell `react-force-graph-2d`, das auf HTML-Canvas aufsetzt und bei groesseren
Subgraphen (>500 Knoten) spuerbar an Leistung verliert. Das Force-Layout laeuft zudem ausschliesslich im Main-Thread,
Label-Rendering wird mit steigender Knotenzahl unruhig und die Interaktion (Hover-Highlight, Neighbor-Fading) ist nur
mit eigenem Canvas-Custom-Rendering moeglich. Fuer die Exploration des GraphMesh-Wissensgraphen, der pro Collection
schnell mehrere tausend Triples umfasst, brauchen wir eine WebGL-basierte Visualisierung mit stabilerem Layout und
produktionsreifen Interaktions-Primitiven.

## Ziel

Migration des bestehenden Graph Explorers von `react-force-graph-2d` auf **Sigma.js v3** via `@react-sigma/core` und
`graphology`. Die bestehenden GraphQL-Queries, Filter, Entity-Suche und Subgraph-Expansion (Feature 34) bleiben
erhalten, nur die Rendering-Schicht und das Layout werden ausgetauscht.

1. **WebGL-Rendering** -- `SigmaContainer` aus `@react-sigma/core` ersetzt `<ForceGraph2D />`; Rendering laeuft ueber
   WebGL und bleibt auch bei >2000 Knoten fluessig.
2. **Graphology-Datenmodell** -- Interne Graphrepraesentation wird auf `graphology.Graph` umgestellt, dadurch entfaellt
   manuelles Deduplizieren von Knoten/Kanten im Merge-Pfad.
3. **ForceAtlas2-Layout** -- `@react-sigma/layout-forceatlas2` ersetzt das bisherige d3-force-Layout und laeuft
   optional in einem WebWorker (`useWorkerLayoutForceAtlas2`).
4. **Hover/Highlight-Reducer** -- Nachbarschafts-Highlighting via `nodeReducer`/`edgeReducer` statt Canvas-Custom-Draw.
5. **Controls & MiniMap** -- `ZoomControl`, `FullScreenControl`, `LayoutForceAtlas2Control` und `MiniMap` aus den
   offiziellen React-Sigma-Paketen ersetzen selbstgebaute Controls.
6. **Integrierte Entity-Suche** -- `@react-sigma/graph-search` ersetzt die eigene `EntitySearch`-Komponente und
   fokussiert den Graphen automatisch auf den gewaehlten Knoten.

## Voraussetzungen

| Abhaengigkeit                      | Status                                       | Blocker? |
|------------------------------------|----------------------------------------------|----------|
| Feature 34: Graph Explorer UI      | Implementiert (mit react-force-graph)        | Ja       |
| Feature 14: GraphQL triples-Query  | Implementiert                                | Ja       |
| `@react-sigma/core` >= 5.x         | Neu hinzuzufuegen                            | Nein     |
| `@react-sigma/layout-forceatlas2`  | Neu hinzuzufuegen                            | Nein     |
| `@react-sigma/graph-search`        | Neu hinzuzufuegen                            | Nein     |
| `@react-sigma/minimap`             | Neu hinzuzufuegen                            | Nein     |
| `sigma` (peer dependency)          | Wird durch `@react-sigma/core` mitgezogen    | Nein     |
| `graphology` + `graphology-types`  | Neu hinzuzufuegen                            | Nein     |
| Next.js 14 App Router (SSR-Guard)  | Vorhanden                                    | Nein     |

## Architektur

### Paketinstallation

```bash
pnpm add @react-sigma/core @react-sigma/layout-forceatlas2 \
         @react-sigma/graph-search @react-sigma/minimap \
         sigma graphology graphology-types
pnpm remove react-force-graph-2d d3-force
```

`@react-sigma/core` bringt das Sigma-Stylesheet mit, das einmalig importiert werden muss:

```typescript
import "@react-sigma/core/lib/style.css";
import "@react-sigma/graph-search/lib/style.css";
```

### SSR-Guard fuer Next.js

Sigma greift beim Import auf `window` zu und darf nicht server-seitig gerendert werden. Der `SigmaContainer` wird
daher via `next/dynamic` mit `{ ssr: false }` eingebunden:

```typescript
// frontend/src/components/graph/GraphCanvas.tsx
"use client";

import dynamic from "next/dynamic";

const GraphCanvasInner = dynamic(() => import("./GraphCanvasInner"), {
    ssr: false,
    loading: () => <div className="h-full w-full animate-pulse bg-muted" />,
});

export { GraphCanvasInner as GraphCanvas };
```

### Datenmodell-Anpassung (graphology)

Die bestehenden Types `GraphNode`/`GraphEdge` aus `types/graph.ts` bleiben als Domain-Typen erhalten, der Hook
`useGraphData` liefert aber zusaetzlich einen `graphology.Graph` statt eines `{ nodes, links }`-Objekts:

```typescript
// frontend/src/hooks/useGraphData.ts
import Graph from "graphology";
import { useCallback, useMemo, useState } from "react";
import { useLazyQuery } from "@apollo/client";
import { GRAPH_TRIPLES_QUERY, NODE_NEIGHBORS_QUERY } from "@/graphql/queries/graph";
import { RdfTermType } from "@/types/graph";

const NODE_COLORS: Record<RdfTermType, string> = {
    URI: "#4F46E5",
    LITERAL: "#059669",
    BLANK_NODE: "#D97706",
    QUOTED_TRIPLE: "#7C3AED",
};

export function useGraphData(collectionId: string) {
    const [graph] = useState(() => new Graph({ multi: true, type: "directed" }));
    const [version, setVersion] = useState(0); // triggert Re-Render bei Mutationen
    const [loadTriples] = useLazyQuery(GRAPH_TRIPLES_QUERY);
    const [loadNeighbors] = useLazyQuery(NODE_NEIGHBORS_QUERY);

    const upsertTriples = useCallback((triples: any[]) => {
        for (const t of triples) {
            const sId = t.subject.value;
            const oId = t.object.value;
            if (!graph.hasNode(sId)) {
                graph.addNode(sId, {
                    label: extractLabel(sId),
                    termType: t.subject.type as RdfTermType,
                    color: NODE_COLORS[t.subject.type as RdfTermType],
                    size: 6,
                    x: Math.random(), // Startposition fuer ForceAtlas2
                    y: Math.random(),
                });
            }
            if (!graph.hasNode(oId)) {
                graph.addNode(oId, {
                    label: extractLabel(oId),
                    termType: t.object.type as RdfTermType,
                    color: NODE_COLORS[t.object.type as RdfTermType],
                    size: t.object.type === "LITERAL" ? 4 : 6,
                    x: Math.random(),
                    y: Math.random(),
                });
            }
            const edgeKey = `${sId}|${t.predicate}|${oId}`;
            if (!graph.hasEdge(edgeKey)) {
                graph.addEdgeWithKey(edgeKey, sId, oId, {
                    label: extractLabel(t.predicate),
                    predicate: t.predicate,
                    graph: t.graph,
                    size: 1.5,
                    type: "arrow",
                });
            }
        }
        setVersion((v) => v + 1);
    }, [graph]);

    const loadInitialGraph = useCallback(async (filter: { predicate?: string; graph?: string }) => {
        graph.clear();
        const { data } = await loadTriples({
            variables: { collectionId, ...filter, limit: 200 },
        });
        if (data?.triples) upsertTriples(data.triples);
    }, [collectionId, graph, loadTriples, upsertTriples]);

    const expandNode = useCallback(async (entityUri: string) => {
        const { data } = await loadNeighbors({
            variables: { collectionId, entityUri, limit: 50 },
        });
        if (data) {
            upsertTriples([...(data.triples ?? []), ...(data.triplesAsObject ?? [])]);
            if (graph.hasNode(entityUri)) {
                graph.setNodeAttribute(entityUri, "expanded", true);
            }
        }
    }, [collectionId, graph, loadNeighbors, upsertTriples]);

    return useMemo(
        () => ({ graph, version, loadInitialGraph, expandNode }),
        [graph, version, loadInitialGraph, expandNode],
    );
}

function extractLabel(uri: string): string {
    const i = Math.max(uri.lastIndexOf("#"), uri.lastIndexOf("/"));
    return i >= 0 ? uri.slice(i + 1) : uri;
}
```

### GraphCanvasInner mit Sigma

```typescript
// frontend/src/components/graph/GraphCanvasInner.tsx
"use client";

import {
    SigmaContainer,
    ControlsContainer,
    ZoomControl,
    FullScreenControl,
    useLoadGraph,
    useRegisterEvents,
    useSetSettings,
    useSigma,
} from "@react-sigma/core";
import { LayoutForceAtlas2Control, useWorkerLayoutForceAtlas2 } from "@react-sigma/layout-forceatlas2";
import { MiniMap } from "@react-sigma/minimap";
import { GraphSearch, GraphSearchOption } from "@react-sigma/graph-search";
import Graph from "graphology";
import { FC, useCallback, useEffect, useState } from "react";

interface Props {
    graph: Graph;
    version: number;
    onNodeClick: (nodeId: string) => void;
    onNodeRightClick: (nodeId: string) => void;
}

const LoadGraphFromStore: FC<{ graph: Graph; version: number }> = ({ graph, version }) => {
    const loadGraph = useLoadGraph();
    useEffect(() => {
        // Sigma arbeitet auf einer Kopie, damit interne Mutationen nichts ueberschreiben
        loadGraph(graph.copy());
    }, [graph, version, loadGraph]);
    return null;
};

const WorkerLayout: FC = () => {
    const { start, kill } = useWorkerLayoutForceAtlas2({
        settings: { slowDown: 10, gravity: 1, scalingRatio: 10 },
    });
    useEffect(() => {
        start();
        return () => kill();
    }, [start, kill]);
    return null;
};

const GraphEvents: FC<Pick<Props, "onNodeClick" | "onNodeRightClick">> = ({ onNodeClick, onNodeRightClick }) => {
    const registerEvents = useRegisterEvents();
    const sigma = useSigma();
    const setSettings = useSetSettings();
    const [hoveredNode, setHoveredNode] = useState<string | null>(null);

    useEffect(() => {
        registerEvents({
            clickNode: (e) => onNodeClick(e.node),
            rightClickNode: (e) => {
                e.preventSigmaDefault();
                onNodeRightClick(e.node);
            },
            enterNode: (e) => setHoveredNode(e.node),
            leaveNode: () => setHoveredNode(null),
        });
    }, [registerEvents, onNodeClick, onNodeRightClick]);

    useEffect(() => {
        setSettings({
            nodeReducer: (node, data) => {
                if (!hoveredNode) return data;
                const g = sigma.getGraph();
                if (node === hoveredNode || g.neighbors(hoveredNode).includes(node)) {
                    return { ...data, highlighted: true };
                }
                return { ...data, color: "#E5E7EB", label: "" };
            },
            edgeReducer: (edge, data) => {
                if (!hoveredNode) return data;
                const g = sigma.getGraph();
                return g.extremities(edge).includes(hoveredNode)
                    ? { ...data, color: "#4F46E5" }
                    : { ...data, hidden: true };
            },
        });
    }, [hoveredNode, sigma, setSettings]);

    return null;
};

const FocusOnSearch: FC = () => {
    const sigma = useSigma();
    const [selected, setSelected] = useState<GraphSearchOption | null>(null);

    const onChange = useCallback((value: GraphSearchOption | null) => {
        setSelected(value);
        if (value?.type === "nodes") {
            sigma.getCamera().animate(sigma.getNodeDisplayData(value.id)!, { duration: 600 });
        }
    }, [sigma]);

    return <GraphSearch type="nodes" value={selected} onChange={onChange} placeholder="Entitaet suchen..." />;
};

const GraphCanvasInner: FC<Props> = ({ graph, version, onNodeClick, onNodeRightClick }) => (
    <SigmaContainer
        style={{ height: "100%", width: "100%" }}
        settings={{
            allowInvalidContainer: true,
            defaultEdgeType: "arrow",
            labelRenderedSizeThreshold: 8,
            labelDensity: 0.2,
            renderEdgeLabels: true,
        }}
    >
        <LoadGraphFromStore graph={graph} version={version} />
        <WorkerLayout />
        <GraphEvents onNodeClick={onNodeClick} onNodeRightClick={onNodeRightClick} />

        <ControlsContainer position="top-left">
            <FocusOnSearch />
        </ControlsContainer>
        <ControlsContainer position="bottom-right">
            <ZoomControl />
            <FullScreenControl />
            <LayoutForceAtlas2Control />
        </ControlsContainer>
        <ControlsContainer position="bottom-left">
            <MiniMap width="140px" height="140px" />
        </ControlsContainer>
    </SigmaContainer>
);

export default GraphCanvasInner;
```

### Seiten-Integration

`frontend/src/app/graph/page.tsx` wird vereinfacht: `GraphControls` (Layout-Slider) und die eigene `EntitySearch`
entfallen, weil Sigma Zoom, Reset, ForceAtlas2-Steuerung und Suche als Komponenten mitbringt. `GraphFilter` und
`NodeDetail` bleiben unveraendert.

```typescript
// frontend/src/app/graph/page.tsx (Ausschnitt)
const { graph, version, loadInitialGraph, expandNode } = useGraphData(collectionId ?? "");

<GraphCanvas
    graph={graph}
    version={version}
    onNodeClick={(id) => setSelectedNodeId(id)}
    onNodeRightClick={(id) => expandNode(id)}
/>
```

### Styling & Theme

Das Sigma-Stylesheet wird einmalig in `app/graph/layout.tsx` importiert, damit die Controls ausserhalb des
dynamischen Imports nicht im SSR-Tree landen. Farben fuer Knoten werden aus dem bestehenden OKLCh-Theme uebernommen,
Kanten nutzen `--muted-foreground`.

## Betroffene Dateien

### Backend

Nicht betroffen -- reines Frontend-Refactoring.

### Frontend

| Datei                                                 | Aenderung                                                  |
|-------------------------------------------------------|------------------------------------------------------------|
| `frontend/package.json`                               | AENDERUNG - `react-force-graph-2d`/`d3-force` raus, Sigma + graphology + react-sigma rein |
| `frontend/src/components/graph/GraphCanvas.tsx`       | AENDERUNG - wird zum SSR-Guard-Wrapper via `next/dynamic`  |
| `frontend/src/components/graph/GraphCanvasInner.tsx`  | NEU - Sigma-Rendering, Events, Layout, Reducer             |
| `frontend/src/components/graph/GraphControls.tsx`     | LOESCHEN - ersetzt durch react-sigma Controls              |
| `frontend/src/components/graph/EntitySearch.tsx`      | LOESCHEN - ersetzt durch `@react-sigma/graph-search`       |
| `frontend/src/hooks/useGraphData.ts`                  | AENDERUNG - liefert `graphology.Graph` statt `{nodes,links}` |
| `frontend/src/types/graph.ts`                         | AENDERUNG - `GraphData`-Alias entfernt, Node-Attributes-Typen ergaenzt |
| `frontend/src/app/graph/layout.tsx`                   | AENDERUNG - Sigma-Stylesheets einbinden                    |
| `frontend/src/app/graph/page.tsx`                     | AENDERUNG - nutzt neuen Canvas und entfernten Controls     |

### Tests

| Datei                                                          | Aenderung                                       |
|----------------------------------------------------------------|-------------------------------------------------|
| `frontend/src/__tests__/components/graph/GraphCanvas.test.tsx` | AENDERUNG - mockt `@react-sigma/core` via vi.mock, prueft SSR-Guard |
| `frontend/src/__tests__/hooks/useGraphData.test.ts`            | AENDERUNG - testet graphology-basiertes Merging (kein Duplizieren von Knoten/Kanten) |
| `frontend/src/__tests__/components/graph/EntitySearch.test.tsx`| LOESCHEN - Komponente entfaellt                 |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                               |
|-------------------|-------------|-----------------------------------------------------|
| Spring Boot (JVM) | Ja          | Keine Backend-Aenderung                             |
| KMP Library       | Nein        | Reines Frontend-Feature                             |
| Ktor/Wasm         | Nein        | Reines Frontend-Feature                             |

## Akzeptanzkriterien

- [ ] `/graph` rendert den Knowledge Graph via Sigma.js/WebGL, kein `react-force-graph-2d` im Bundle
- [ ] Hover auf einen Knoten faded alle nicht-benachbarten Knoten und blendet deren Labels aus
- [ ] ForceAtlas2-Layout laeuft in einem WebWorker und blockiert den Main-Thread nicht
- [ ] Zoom, Fullscreen und Layout-Toggle sind ueber die react-sigma Controls bedienbar
- [ ] MiniMap zeigt den aktuellen Viewport-Ausschnitt
- [ ] `@react-sigma/graph-search` findet Knoten per Label und zentriert die Kamera via `camera.animate`
- [ ] Rechtsklick auf einen Knoten triggert `expandNode` und fuegt neue Knoten/Kanten hinzu, ohne bestehende zu duplizieren
- [ ] `useGraphData` gibt eine `graphology.Graph`-Instanz zurueck, nicht mehr `{ nodes, links }`
- [ ] Sigma wird ausschliesslich client-seitig geladen (`next/dynamic` mit `ssr: false`), Build bricht nicht mit "window is not defined"
- [ ] Performance: 2000 Knoten / 4000 Kanten bleiben bei Pan/Zoom ueber 30 FPS
- [ ] Bestehende Funktionalitaet (Filter, NodeDetail, Collection-Auswahl) bleibt unveraendert
