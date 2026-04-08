# Feature 42: Graph Explorer auf Sigma.js — Design Spec

**Date:** 2026-04-08
**Status:** Approved (brainstorming complete)
**Source feature doc:** `docs/features/42-graph-explorer-sigmajs.md`
**Related:** Feature 34 (existing Graph Explorer with react-force-graph-2d)

## Goal

Migrate the existing Graph Explorer (`/graph` page) from `react-force-graph-2d` to `@react-sigma/core` + `graphology`
+ ForceAtlas2 (in a WebWorker), to gain WebGL rendering and more headroom for large subgraphs. The migration is
**frontend-only**: no backend, no GraphQL schema, no test infrastructure changes outside the `frontend/` directory.

## Non-Goals (YAGNI)

- Layout-tuning sliders (gravity/scalingRatio/slowDown). FA2 defaults are hardcoded; tune in code if needed later.
- `@react-sigma/graph-search`. The existing backend-driven `EntitySearch` is preserved; an in-graph fuzzy search is
  not added.
- Persistent node positions (e.g., localStorage caching of FA2 results).
- Edge-hover highlighting. Only node hover is supported, matching current behavior.
- Cluster/community detection.
- Performance benchmarks as automated tests. Worker-Layout is the architectural guarantee; performance is qualitatively
  verified during code review and manual smoke testing.

## Decisions Resolved During Brainstorming

| # | Question | Decision | Rationale |
|---|---|---|---|
| 1 | Where does `graphology.Graph` live? | **Hook owns it (`useRef`), exposes instance + `version` counter** | Sigma-native, no double conversion in hot path, hook is single source of truth |
| 2 | Re-render trigger | **Manual `setVersion(v=>v+1)` after each mutation** | Mutations only happen in 2 callsites; explicit, easy to test |
| 3 | `centerOnNode` API | **Declarative `focus={id, nonce}` prop + internal `<FocusOnNode>` using `useCamera().gotoNode`** | Idiomatic React, avoids ref-forwarding through `next/dynamic`, matches official Sigma docs pattern |
| 4 | `GraphControls` (sliders) | **Delete entirely** | d3-force tuning params don't apply to FA2; `LayoutForceAtlas2Control` button covers 99% of needs |
| 5 | EntitySearch component | **Keep existing backend-driven `EntitySearch`, do not install `@react-sigma/graph-search`** | Avoids functional regression (backend search finds entities not yet loaded into the graph); saves a dependency |
| 6 | Test strategy | **Test hook + transforms + extracted reducers; no `GraphCanvas` test; no `vi.mock` for Sigma** | graphology has a pure API that runs in jsdom; Sigma wiring is trivial and not worth mocking |
| 7 | `canvas` devDep | **Remove** | Only needed by react-force-graph-2d; if anything breaks, re-add |
| 8 | Multi-edges (parallel predicates) | **`multi: true` + `addEdgeWithKey` + `@sigma/edge-curve` for visual disambiguation** | RDF requires multi-graphs; curved edges make parallel predicates visible |
| 9 | "30 FPS at 2000 nodes" criterion | **Replaced with qualitative "Worker-Layout, no main-thread blocking"** | FPS not automatable; the architectural guarantee is what we actually verify |

## Architecture

### Component topology

```
frontend/src/
├── app/graph/
│   ├── layout.tsx              ✏️  no changes (Sigma stylesheet imported in Inner)
│   └── page.tsx                ✏️  drop layoutConfig + canvasRef; add focus state
├── components/graph/
│   ├── GraphCanvas.tsx         ✏️  thin SSR-guard wrapper using next/dynamic
│   ├── GraphCanvasInner.tsx    ➕  NEW – SigmaContainer + child wiring components
│   │     ├── <LoadGraphFromStore>
│   │     ├── <WorkerLayout>
│   │     ├── <GraphEvents>
│   │     └── <FocusOnNode>
│   ├── GraphControls.tsx       ❌  DELETE
│   ├── GraphFilter.tsx         ✓   unchanged
│   ├── EntitySearch.tsx        ✓   unchanged
│   └── NodeDetail.tsx          ✓   unchanged (consumes legacy GraphNode display type)
├── hooks/
│   └── useGraphData.ts         ✏️  rewritten – holds graphology.Graph + version
├── lib/graph/
│   ├── transforms.ts           ✏️  rewritten – quadsToGraphologyGraph(quads, target?)
│   └── highlight.ts            ➕  NEW – pure buildNodeReducer / buildEdgeReducer
└── types/graph.ts              ✏️  drop GraphData/GraphEdge/LayoutConfig; add NodeAttributes/EdgeAttributes; keep GraphNode (display type for NodeDetail)
```

### Data flow

```
GraphQL triples query
        ↓
useGraphData
   • holds graphology.Graph in useRef
   • mutates in place via quadsToGraphologyGraph
   • bumps version after each mutation
        ↓ {graph, version, loadInitial, expandNode, clear}
page.tsx
   • holds selectedNodeId, focus={id, nonce}, filter
   • passes graph + version + focus to GraphCanvas
   • on EntitySearch select → expandNode + setFocus
   • on right-click → expandNode
        ↓
GraphCanvas (next/dynamic, ssr:false)
        ↓
GraphCanvasInner
   ├── SigmaContainer (settings: defaultEdgeType "curved", edgeProgramClasses)
   │   ├── <LoadGraphFromStore graph version /> → sigma.loadGraph(graph.copy())
   │   ├── <WorkerLayout /> → useWorkerLayoutForceAtlas2 start/kill
   │   ├── <GraphEvents /> → registerEvents + setSettings(nodeReducer/edgeReducer)
   │   ├── <FocusOnNode focus /> → useCamera().gotoNode(focus.id) on nonce change
   │   └── ControlsContainer
   │       ├── ZoomControl, FullScreenControl
   │       ├── LayoutForceAtlas2Control
   │       └── MiniMap
```

### Data model — `types/graph.ts`

```typescript
export type RdfTermType = "URI" | "LITERAL" | "BLANK_NODE" | "QUOTED_TRIPLE";

// Graphology node attributes — passed as Graph<NodeAttributes, EdgeAttributes>
export interface NodeAttributes {
  label: string;
  termType: RdfTermType;
  isSubject: boolean;
  expanded: boolean;
  size: number;
  color: string;
  x: number;        // required by ForceAtlas2 as starting position
  y: number;
}

export interface EdgeAttributes {
  predicate: string;
  dataset: string;
  label: string;
  size: number;
  type: "curved";   // matches edgeProgramClasses key
  color: string;
}

// Display type — kept solely for NodeDetail.tsx compatibility (not used by Sigma rendering)
export interface GraphNode {
  id: string;
  label: string;
  type: RdfTermType;
  isSubject: boolean;
  expanded: boolean;
  size: number;
}

export interface GraphFilter {
  datasets: string[];
  predicates: string[];
  entityTypes: string[];
}

// Wire format — unchanged
export interface QuadDto {
  subject: string;
  predicate: string;
  object: string;
  dataset: string;
  objectType: string;     // "URI" | "LITERAL" | "BLANK_NODE" | "QUOTED_TRIPLE"
  datatype?: string | null;
  language?: string | null;
}

// REMOVED: GraphData, GraphEdge, LayoutConfig
```

### `lib/graph/transforms.ts` — graphology builder

```typescript
import Graph from "graphology";
import { EdgeAttributes, NodeAttributes, QuadDto, RdfTermType } from "@/types/graph";

const NODE_COLORS: Record<RdfTermType, string> = {
  URI: "#4F46E5",
  LITERAL: "#059669",
  BLANK_NODE: "#D97706",
  QUOTED_TRIPLE: "#7C3AED",
};

const RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

export function createEmptyGraph(): Graph<NodeAttributes, EdgeAttributes> {
  return new Graph<NodeAttributes, EdgeAttributes>({
    type: "directed",
    multi: true,
    allowSelfLoops: true,
  });
}

/**
 * Inserts quads into a graphology graph, mutating it in place.
 * Idempotent: existing nodes/edges are not duplicated.
 *
 * @param target - existing graph to merge into. If omitted, a fresh graph is created.
 * @returns the same graph instance (or the freshly created one)
 */
export function quadsToGraphologyGraph(
  quads: QuadDto[],
  target?: Graph<NodeAttributes, EdgeAttributes>,
): Graph<NodeAttributes, EdgeAttributes> {
  const graph = target ?? createEmptyGraph();
  for (const q of quads) {
    upsertNode(graph, q.subject, "URI", true);
    upsertNode(graph, q.object, q.objectType as RdfTermType, false);
    const edgeKey = `${q.subject}|${q.predicate}|${q.object}`;
    if (!graph.hasEdge(edgeKey)) {
      graph.addEdgeWithKey(edgeKey, q.subject, q.object, {
        predicate: q.predicate,
        dataset: q.dataset,
        label: extractLabel(q.predicate),
        size: 1.5,
        type: "curved",
        color: "#6B7280",
      });
    }
  }
  return graph;
}

function upsertNode(
  graph: Graph<NodeAttributes, EdgeAttributes>,
  id: string,
  termType: RdfTermType,
  isSubject: boolean,
): void {
  if (graph.hasNode(id)) {
    if (isSubject) graph.setNodeAttribute(id, "isSubject", true);
    return;
  }
  graph.addNode(id, {
    label: extractLabel(id),
    termType,
    isSubject,
    expanded: false,
    size: termType === "LITERAL" ? 4 : 6,
    color: NODE_COLORS[termType],
    x: Math.random(),
    y: Math.random(),
  });
}

export function extractLabel(uri: string): string {
  const i = Math.max(uri.lastIndexOf("#"), uri.lastIndexOf("/"));
  return i >= 0 ? uri.slice(i + 1) : uri;
}

/**
 * Filters a graph in place: drops subjects whose rdf:type triple does not match
 * any of the allowed entityTypes. Edges to/from removed nodes are dropped by graphology.
 */
export function applyEntityTypeFilter(
  graph: Graph<NodeAttributes, EdgeAttributes>,
  entityTypes: string[],
): void {
  if (entityTypes.length === 0) return;
  const allowed = new Set(entityTypes);
  const allowedSubjects = new Set<string>();
  graph.forEachEdge((edge, attrs, source) => {
    if (attrs.predicate === RDF_TYPE && allowed.has(graph.target(edge))) {
      allowedSubjects.add(source);
    }
  });
  const toDrop: string[] = [];
  graph.forEachNode((node, attrs) => {
    if (attrs.isSubject && !allowedSubjects.has(node)) toDrop.push(node);
  });
  for (const node of toDrop) graph.dropNode(node);
}
```

Note: `dropNode` is called outside the `forEachNode` iteration to avoid mutating the graph mid-iteration.

### `lib/graph/highlight.ts` — pure reducer factories

```typescript
import Graph from "graphology";
import { EdgeAttributes, NodeAttributes } from "@/types/graph";

const FADED_COLOR = "#1F2937";
const HIGHLIGHT_EDGE_COLOR = "#4F46E5";

export interface NodeDisplay {
  label?: string;
  color?: string;
  highlighted?: boolean;
  hidden?: boolean;
  size?: number;
}

export interface EdgeDisplay {
  label?: string;
  color?: string;
  hidden?: boolean;
  size?: number;
}

export function buildNodeReducer(
  graph: Graph<NodeAttributes, EdgeAttributes>,
  activeNode: string | null,
): (node: string, data: NodeDisplay) => NodeDisplay {
  if (!activeNode || !graph.hasNode(activeNode)) {
    return (_node, data) => data;
  }
  const neighbors = new Set(graph.neighbors(activeNode));
  neighbors.add(activeNode);
  return (node, data) => {
    if (neighbors.has(node)) return { ...data, highlighted: true };
    return { ...data, color: FADED_COLOR, label: "" };
  };
}

export function buildEdgeReducer(
  graph: Graph<NodeAttributes, EdgeAttributes>,
  activeNode: string | null,
): (edge: string, data: EdgeDisplay) => EdgeDisplay {
  if (!activeNode || !graph.hasNode(activeNode)) {
    return (_edge, data) => data;
  }
  return (edge, data) => {
    const [s, t] = graph.extremities(edge);
    if (s === activeNode || t === activeNode) {
      return { ...data, color: HIGHLIGHT_EDGE_COLOR };
    }
    return { ...data, hidden: true };
  };
}
```

### `hooks/useGraphData.ts` — rewritten

```typescript
"use client";

import { useCallback, useMemo, useRef, useState } from "react";
import { useApolloClient } from "@apollo/client/react";
import Graph from "graphology";

import { GRAPH_TRIPLES_QUERY, NODE_NEIGHBORS_QUERY } from "@/graphql/graph";
import {
  applyEntityTypeFilter,
  createEmptyGraph,
  quadsToGraphologyGraph,
} from "@/lib/graph/transforms";
import { EdgeAttributes, GraphFilter, NodeAttributes, QuadDto } from "@/types/graph";

const INITIAL_LIMIT = 500;
const NEIGHBOR_LIMIT = 50;

export interface UseGraphDataResult {
  graph: Graph<NodeAttributes, EdgeAttributes>;
  version: number;
  loadInitial: (filter: GraphFilter) => Promise<void>;
  expandNode: (entityUri: string) => Promise<void>;
  clear: () => void;
}

export function useGraphData(collectionId: string): UseGraphDataResult {
  const client = useApolloClient();
  const graphRef = useRef<Graph<NodeAttributes, EdgeAttributes>>();
  if (!graphRef.current) graphRef.current = createEmptyGraph();
  const [version, setVersion] = useState(0);

  const bump = useCallback(() => setVersion((v) => v + 1), []);

  const loadInitial = useCallback(
    async (filter: GraphFilter): Promise<void> => {
      if (!collectionId) return;
      const { data } = await client.query<{ triples: QuadDto[] }>({
        query: GRAPH_TRIPLES_QUERY,
        variables: {
          collectionId,
          subject: null,
          predicate: filter.predicates.length === 1 ? filter.predicates[0] : null,
          object: null,
          dataset: filter.datasets.length === 1 ? filter.datasets[0] : null,
          limit: INITIAL_LIMIT,
        },
        fetchPolicy: "network-only",
      });
      const graph = graphRef.current!;
      graph.clear();
      quadsToGraphologyGraph(data?.triples ?? [], graph);
      applyEntityTypeFilter(graph, filter.entityTypes);
      bump();
    },
    [client, collectionId, bump],
  );

  const expandNode = useCallback(
    async (entityUri: string): Promise<void> => {
      if (!collectionId) return;
      const { data } = await client.query<{
        asSubject: QuadDto[];
        asObject: QuadDto[];
      }>({
        query: NODE_NEIGHBORS_QUERY,
        variables: { collectionId, entityUri, limit: NEIGHBOR_LIMIT },
        fetchPolicy: "network-only",
      });
      const graph = graphRef.current!;
      const all = [...(data?.asSubject ?? []), ...(data?.asObject ?? [])];
      quadsToGraphologyGraph(all, graph);
      if (graph.hasNode(entityUri)) {
        graph.setNodeAttribute(entityUri, "expanded", true);
      }
      bump();
    },
    [client, collectionId, bump],
  );

  const clear = useCallback(() => {
    graphRef.current?.clear();
    bump();
  }, [bump]);

  return useMemo(
    () => ({ graph: graphRef.current!, version, loadInitial, expandNode, clear }),
    [version, loadInitial, expandNode, clear],
  );
}
```

### `components/graph/GraphCanvas.tsx` — SSR guard

```typescript
"use client";

import dynamic from "next/dynamic";
import { ComponentProps } from "react";

const GraphCanvasInner = dynamic(() => import("./GraphCanvasInner"), {
  ssr: false,
  loading: () => (
    <div data-testid="graph-canvas-loading" className="h-full w-full animate-pulse bg-muted" />
  ),
});

export type GraphCanvasProps = ComponentProps<typeof GraphCanvasInner>;
export function GraphCanvas(props: GraphCanvasProps) {
  return <GraphCanvasInner {...props} />;
}
```

### `components/graph/GraphCanvasInner.tsx` — Sigma wiring

```typescript
"use client";

import "@react-sigma/core/lib/style.css";

import {
  ControlsContainer,
  FullScreenControl,
  SigmaContainer,
  ZoomControl,
  useCamera,
  useLoadGraph,
  useRegisterEvents,
  useSetSettings,
  useSigma,
} from "@react-sigma/core";
import {
  LayoutForceAtlas2Control,
  useWorkerLayoutForceAtlas2,
} from "@react-sigma/layout-forceatlas2";
import { MiniMap } from "@react-sigma/minimap";
import EdgeCurveProgram from "@sigma/edge-curve";
import Graph from "graphology";
import { FC, useEffect, useState } from "react";

import { buildEdgeReducer, buildNodeReducer } from "@/lib/graph/highlight";
import { EdgeAttributes, NodeAttributes } from "@/types/graph";

export interface GraphCanvasInnerProps {
  graph: Graph<NodeAttributes, EdgeAttributes>;
  version: number;
  selectedNodeId: string | null;
  focus: { id: string; nonce: number } | null;
  onNodeClick: (nodeId: string) => void;
  onNodeRightClick: (nodeId: string) => void;
}

const SIGMA_STYLE = { height: "100%", width: "100%" };

const SIGMA_SETTINGS = {
  allowInvalidContainer: true,
  defaultEdgeType: "curved",
  edgeProgramClasses: { curved: EdgeCurveProgram },
  renderEdgeLabels: true,
  labelRenderedSizeThreshold: 8,
  labelDensity: 0.2,
  labelColor: { color: "#E5E7EB" },
};

const FA2_SETTINGS = { slowDown: 10, gravity: 1, scalingRatio: 10 };

const LoadGraphFromStore: FC<{
  graph: Graph<NodeAttributes, EdgeAttributes>;
  version: number;
}> = ({ graph, version }) => {
  const loadGraph = useLoadGraph();
  useEffect(() => {
    loadGraph(graph.copy());
  }, [graph, version, loadGraph]);
  return null;
};

const WorkerLayout: FC = () => {
  const { start, kill } = useWorkerLayoutForceAtlas2({ settings: FA2_SETTINGS });
  useEffect(() => {
    start();
    return () => kill();
  }, [start, kill]);
  return null;
};

const GraphEvents: FC<{
  selectedNodeId: string | null;
  onNodeClick: (id: string) => void;
  onNodeRightClick: (id: string) => void;
}> = ({ selectedNodeId, onNodeClick, onNodeRightClick }) => {
  const sigma = useSigma();
  const registerEvents = useRegisterEvents();
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
    const active = hoveredNode ?? selectedNodeId;
    const sigmaGraph = sigma.getGraph() as Graph<NodeAttributes, EdgeAttributes>;
    setSettings({
      nodeReducer: buildNodeReducer(sigmaGraph, active),
      edgeReducer: buildEdgeReducer(sigmaGraph, active),
    });
  }, [hoveredNode, selectedNodeId, sigma, setSettings]);

  return null;
};

const FocusOnNode: FC<{ focus: { id: string; nonce: number } | null }> = ({ focus }) => {
  const { gotoNode } = useCamera({ duration: 600 });
  useEffect(() => {
    if (focus) gotoNode(focus.id);
  }, [focus, gotoNode]);
  return null;
};

const GraphCanvasInner: FC<GraphCanvasInnerProps> = ({
  graph,
  version,
  selectedNodeId,
  focus,
  onNodeClick,
  onNodeRightClick,
}) => (
  <div data-testid="graph-canvas" className="h-full w-full">
    <SigmaContainer style={SIGMA_STYLE} settings={SIGMA_SETTINGS}>
      <LoadGraphFromStore graph={graph} version={version} />
      <WorkerLayout />
      <GraphEvents
        selectedNodeId={selectedNodeId}
        onNodeClick={onNodeClick}
        onNodeRightClick={onNodeRightClick}
      />
      <FocusOnNode focus={focus} />

      <ControlsContainer position="bottom-right">
        <ZoomControl />
        <FullScreenControl />
        <LayoutForceAtlas2Control settings={FA2_SETTINGS} />
      </ControlsContainer>
      <ControlsContainer position="bottom-left">
        <MiniMap width="140px" height="140px" />
      </ControlsContainer>
    </SigmaContainer>
  </div>
);

export default GraphCanvasInner;
```

### `app/graph/page.tsx` — wiring changes

The page is restructured around the new hook + canvas API. Notable changes:

- `layoutConfig`/`setLayoutConfig` removed.
- `canvasRef` removed; `focus` state added.
- `EntitySearch.onSelect` now triggers `expandNode(uri).then(() => focusOn(uri))`.
- Right-click forwards to `expandNode` directly.
- `NodeDetail` consumes a constructed `GraphNode` stub built from graphology attributes (display-only — kept for
  compatibility, since `NodeDetail.tsx` itself remains unchanged).

```typescript
const focusOn = (id: string) => setFocus({ id, nonce: Date.now() });

<GraphCanvas
  graph={graph}
  version={version}
  selectedNodeId={selectedNodeId}
  focus={focus}
  onNodeClick={(id) => setSelectedNodeId(id)}
  onNodeRightClick={(id) => { void expandNode(id); }}
/>

<NodeDetail
  node={{
    id: selectedNodeId,
    label: graph.hasNode(selectedNodeId) ? graph.getNodeAttribute(selectedNodeId, "label") : selectedNodeId,
    type: graph.hasNode(selectedNodeId) ? graph.getNodeAttribute(selectedNodeId, "termType") : "URI",
    isSubject: graph.hasNode(selectedNodeId) ? graph.getNodeAttribute(selectedNodeId, "isSubject") : true,
    expanded: graph.hasNode(selectedNodeId) ? graph.getNodeAttribute(selectedNodeId, "expanded") : false,
    size: 6,
  }}
  collectionId={collectionId}
  onExpand={(uri) => void expandNode(uri)}
  onClose={() => setSelectedNodeId(null)}
/>
```

### Dependencies

**Add (pnpm):**
- `@react-sigma/core`
- `@react-sigma/layout-forceatlas2`
- `@react-sigma/minimap`
- `@sigma/edge-curve`
- `sigma` (peer)
- `graphology`
- `graphology-types`
- `graphology-layout-forceatlas2` (peer of layout package)

**Remove (pnpm):**
- `react-force-graph-2d`
- `d3-force`
- `canvas` (devDep)

The implementer should verify exact peer-dependency requirements via context7 / `pnpm install` output during the
implementation step. If `graphology-layout-forceatlas2` or other peers are auto-pulled by `@react-sigma/layout-forceatlas2`,
they need not be installed explicitly.

## Testing

### Files to delete

- `frontend/src/__tests__/components/graph/GraphCanvas.test.tsx` — tested react-force-graph canvas drawing; obsolete

### Files to update

- `frontend/src/__tests__/lib/graph/transforms.test.ts` — rewrite against new `quadsToGraphologyGraph` API
- `frontend/src/__tests__/hooks/useGraphData.test.tsx` — rewrite to assert graphology API (`graph.order`, `graph.size`,
  `graph.hasNode`, `graph.getNodeAttribute`) and `version` counter

### Files to add

- `frontend/src/__tests__/lib/graph/highlight.test.ts` — pure reducer functions

### Test cases

**`transforms.test.ts`:**
1. Creates nodes and edges from quads
2. Merging into existing graph: no duplicates
3. Multi-edges with different predicates between the same subject/object
4. Subjects marked with `isSubject=true`, objects with `isSubject=false`
5. LITERAL nodes get smaller size and LITERAL color
6. `applyEntityTypeFilter` drops subjects whose rdf:type does not match

**`useGraphData.test.tsx`:**
1. `loadInitial` populates the graph and bumps version
2. `expandNode` merges new triples without duplicating existing ones
3. `expandNode` marks the entity attribute `expanded=true`
4. `clear` empties the graph and bumps version
5. `loadInitial` with empty filter does not throw

**`highlight.test.ts`:**
1. `buildNodeReducer` returns identity when no active node
2. `buildNodeReducer` highlights active node + neighbors, fades others
3. `buildEdgeReducer` hides edges not incident to active node
4. Works with multi-edges (`graph.extremities` works for parallel edges)

### What we deliberately do NOT test

- `GraphCanvasInner.tsx` — pure Sigma wiring, no logic worth isolating; runs in WebGL which jsdom does not provide.
- `<LoadGraphFromStore>`, `<WorkerLayout>`, `<GraphEvents>`, `<FocusOnNode>` — would require mocking the entire
  `@react-sigma/*` surface; mocks are fragile and test the mock, not the code.
- Sigma rendering output — that is Sigma's responsibility, not ours.

### Mocking

No `vi.mock` for `@react-sigma/*`. graphology runs in jsdom out of the box. Apollo continues to be mocked via the
existing `MockedProvider` setup.

## Acceptance criteria

- [ ] `/graph` renders the knowledge graph via Sigma.js/WebGL; `react-force-graph-2d` is gone from `package.json` and
  the build output
- [ ] `useGraphData` exposes a `graphology.Graph` instance and a `version` counter; mutations occur exclusively via
  `loadInitial`/`expandNode`/`clear`
- [ ] `quadsToGraphologyGraph` is idempotent: calling it twice with the same quads does not duplicate nodes/edges
- [ ] Multi-edges (multiple predicates between the same subject/object) render as separate, curved edges via
  `@sigma/edge-curve`
- [ ] Hovering a node fades non-neighbor nodes and hides their labels (`buildNodeReducer`/`buildEdgeReducer`)
- [ ] A clicked node remains highlighted even when the cursor leaves it
- [ ] Right-click on a node triggers `expandNode` and suppresses the native browser context menu
  (`e.preventSigmaDefault()`)
- [ ] ForceAtlas2 layout runs in a WebWorker via `useWorkerLayoutForceAtlas2`; no long tasks visible in the
  DevTools Performance profile during layout
- [ ] `LayoutForceAtlas2Control`, `ZoomControl`, `FullScreenControl`, `MiniMap` are visible and functional
- [ ] The header `EntitySearch` (existing component) finds entities not yet in the graph, loads them via `expandNode`,
  and centers the camera through the `focus={id, nonce}` prop → `useCamera().gotoNode`
- [ ] Sigma is loaded client-only via `next/dynamic({ssr: false})`; `pnpm build` runs without "window is not defined"
- [ ] `pnpm test` passes without the `canvas` devDependency
- [ ] `react-force-graph-2d`, `d3-force`, and `canvas` are removed from `package.json`
- [ ] `GraphControls.tsx` and the `LayoutConfig` type are deleted
- [ ] `GraphFilter`, `NodeDetail`, `EntitySearch`, and `CollectionSelector` continue to function as before

## Open questions / risks

1. **`@sigma/edge-curve` package name and edgeProgramClasses key** — context7 confirmed the integration pattern
   conceptually, but exact import path and program class name should be verified during implementation. Fallback:
   ship with `defaultEdgeType: "arrow"` and skip curved edges if integration is more involved than expected.
2. **Peer dependencies of `@react-sigma/layout-forceatlas2`** — `graphology-layout-forceatlas2` may be required as
   an explicit install. Verify with `pnpm install` warnings.
3. **`useGraphData` ref initialization on Suspense re-mount** — using `useRef` with lazy init in render is safe but
   the React StrictMode double-invoke can produce two different refs. Mitigation: the lazy init pattern
   (`if (!graphRef.current) graphRef.current = createEmptyGraph();`) is the React-team-recommended idiom and is
   StrictMode-safe.
4. **`LayoutForceAtlas2Control` vs custom `<WorkerLayout>`** — both manage the FA2 worker. Running both
   simultaneously may cause double-start. Decision: keep `<WorkerLayout>` for auto-start on mount; the
   `LayoutForceAtlas2Control` button toggles the same underlying worker via shared sigma context. Verify during
   implementation that they don't conflict; if they do, drop `<WorkerLayout>` and rely on the control's `autoRunFor`
   prop.
