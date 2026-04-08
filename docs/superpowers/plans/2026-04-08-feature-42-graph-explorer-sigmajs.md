# Feature 42: Graph Explorer Sigma.js Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the Graph Explorer at `/graph` from `react-force-graph-2d` to `@react-sigma/core` + `graphology` + ForceAtlas2 (WebWorker), preserving all existing functionality (filters, entity search, node detail, subgraph expansion).

**Architecture:** Frontend-only refactor in `frontend/`. The hook owns a mutable `graphology.Graph` instance plus a `version` counter (re-render trigger). The canvas is split into a thin SSR-guard wrapper (`GraphCanvas.tsx` via `next/dynamic({ssr:false})`) and an inner Sigma wiring component (`GraphCanvasInner.tsx`) that uses `useLoadGraph`, `useWorkerLayoutForceAtlas2`, `useRegisterEvents`, `useSetSettings`, and `useCamera`. Hover/select highlighting is delegated to two pure reducer factory functions in a new `lib/graph/highlight.ts` module so they can be unit-tested in jsdom without WebGL.

**Tech Stack:** Next.js 14 App Router, React 18, TypeScript, Apollo Client v4, `@react-sigma/core` ≥5, `@react-sigma/layout-forceatlas2`, `@react-sigma/minimap`, `@sigma/edge-curve`, `sigma`, `graphology`, `graphology-layout-forceatlas2`, pnpm, Vitest + jsdom + `@testing-library/react`.

**Spec:** `docs/superpowers/specs/2026-04-08-graph-explorer-sigmajs-design.md`

---

## Working agreements

- **Package manager:** pnpm exclusively. Do not run `npm install` or commit `package-lock.json`.
- **Working directory for all `pnpm` / `git` commands:** `frontend/` (the project's `frontend` subdir). Use `cd frontend && pnpm …` or run them from inside that directory.
- **Commits go straight to `main`** — no feature branches, no PRs. Never push to `origin` automatically.
- **Test runner:** `pnpm test` from `frontend/`. Failing tests block commits.
- **Lint:** `pnpm lint` from `frontend/`. The build will run `next lint` indirectly via `pnpm build`.
- **TDD:** every code task starts with a failing test (red), then minimal impl (green), then commit. Pure-function tasks follow this strictly. Sigma wiring components have no isolated tests (see spec test strategy) — verify them via `pnpm build` and the manual smoke test in Task 13.
- **One commit per task** (or per logical sub-step within a task — see commit cues in each task).
- **Do not run `pnpm dev`** in tasks; manual smoke test in Task 13 is the only place dev server is needed.

---

## File structure (created / modified / deleted)

### Created
- `frontend/src/lib/graph/highlight.ts` — pure reducer factories
- `frontend/src/__tests__/lib/graph/highlight.test.ts` — reducer tests
- `frontend/src/components/graph/GraphCanvasInner.tsx` — Sigma wiring (default export)

### Modified
- `frontend/package.json` — add Sigma deps, remove react-force-graph deps
- `frontend/src/types/graph.ts` — drop GraphData/GraphEdge/LayoutConfig; add NodeAttributes/EdgeAttributes; keep GraphNode as legacy display type
- `frontend/src/lib/graph/transforms.ts` — replace `quadsToGraphData` with `quadsToGraphologyGraph`; add `createEmptyGraph` and `applyEntityTypeFilter`; remove `mergeGraphData`, `quadToEdgeId`
- `frontend/src/__tests__/lib/graph/transforms.test.ts` — rewrite against new API
- `frontend/src/hooks/useGraphData.ts` — rewrite around graphology + version counter
- `frontend/src/__tests__/hooks/useGraphData.test.tsx` — rewrite to assert graphology API + version
- `frontend/src/components/graph/GraphCanvas.tsx` — collapse to SSR-guard wrapper
- `frontend/src/app/graph/page.tsx` — drop layoutConfig + canvasRef; add focus state; build NodeDetail node stub from graphology

### Deleted
- `frontend/src/components/graph/GraphControls.tsx`
- `frontend/src/__tests__/components/graph/GraphCanvas.test.tsx`

---

## Task overview

| # | Task | Type |
|---|---|---|
| 1 | Verify dependency package names via context7 | Research |
| 2 | Install Sigma + graphology dependencies, remove react-force-graph | Deps |
| 3 | Update `types/graph.ts` (NodeAttributes/EdgeAttributes, drop legacy) | Types |
| 4 | TDD: rewrite `lib/graph/transforms.ts` (quadsToGraphologyGraph, applyEntityTypeFilter) | Pure logic + tests |
| 5 | TDD: create `lib/graph/highlight.ts` (buildNodeReducer, buildEdgeReducer) | Pure logic + tests |
| 6 | TDD: rewrite `hooks/useGraphData.ts` against graphology | Hook + tests |
| 7 | Create `components/graph/GraphCanvasInner.tsx` (Sigma wiring) | Sigma component |
| 8 | Refactor `components/graph/GraphCanvas.tsx` to SSR-guard wrapper | Sigma component |
| 9 | Delete `GraphControls.tsx` and its test | Cleanup |
| 10 | Update `app/graph/page.tsx` | Page wiring |
| 11 | Delete `__tests__/components/graph/GraphCanvas.test.tsx` | Cleanup |
| 12 | Verify build & full test suite | Verification |
| 13 | Manual smoke test (dev server, qualitative perf check) | Verification |
| 14 | Write `docs/features/42-graph-explorer-sigmajs-done.md` | Docs |

---

## Task 1: Verify dependency package names via context7

**Goal:** Pin down the exact package names + versions before installing, since the spec lists best-guess names. Specifically resolve any ambiguity around `@sigma/edge-curve`, `@react-sigma/layout-forceatlas2` peer deps, and version compatibility with React 18.

**Files:** none (research only — output goes into your scratch notes for Task 2)

- [ ] **Step 1: Resolve `@react-sigma/core` package**

Run via Claude tool:
```
mcp__plugin_context7_context7__resolve-library-id
  libraryName: "@react-sigma/core"
  query: "react sigma v3 core SigmaContainer Next.js React 18"
```
Expected: returns library id like `/sim51/react-sigma`. Note the latest version that supports React 18.

- [ ] **Step 2: Query installation docs for the layout-forceatlas2 package**

Run:
```
mcp__plugin_context7_context7__query-docs
  libraryId: "/sim51/react-sigma"
  query: "install @react-sigma/layout-forceatlas2 peer dependencies graphology-layout-forceatlas2 useWorkerLayoutForceAtlas2"
```
Expected: doc snippet showing required peer deps. Note whether `graphology-layout-forceatlas2` must be installed explicitly.

- [ ] **Step 3: Verify `@sigma/edge-curve` exists and integration pattern**

Run:
```
mcp__plugin_context7_context7__resolve-library-id
  libraryName: "@sigma/edge-curve"
  query: "sigma edge curve curved edges multi-graph edgeProgramClasses"
```
Expected: library id (likely `/jacomyal/sigma.js` or similar). If a clear edge-curve package exists, query its docs:
```
mcp__plugin_context7_context7__query-docs
  libraryId: <id from above>
  query: "edgeProgramClasses curved edge program registration custom edge type"
```

If `@sigma/edge-curve` does NOT exist as a separate package or the integration looks brittle, **fall back** to the spec's documented fallback: use `defaultEdgeType: "arrow"` (the built-in arrow program), drop `@sigma/edge-curve` from the dependency list, and adjust `EdgeAttributes.type` to `"arrow"` in Task 3 and the edge-program registration in Task 7.

- [ ] **Step 4: Record decisions in scratch notes**

Write down for Task 2:
- Exact `pnpm add` command including any explicit peer deps
- Whether `@sigma/edge-curve` is in or out (curved vs arrow edges)
- The edge program key to use in `SIGMA_SETTINGS.edgeProgramClasses` and `EdgeAttributes.type`

No commit. This is research only.

---

## Task 2: Install Sigma + graphology dependencies

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/pnpm-lock.yaml` (auto-updated by pnpm)

- [ ] **Step 1: Remove obsolete dependencies**

Run from `frontend/`:
```bash
cd frontend
pnpm remove react-force-graph-2d d3-force canvas
```
Expected: pnpm reports removal of those three packages and updates `pnpm-lock.yaml`.

- [ ] **Step 2: Install Sigma + graphology**

Run from `frontend/` (substitute the curved-edge package per Task 1 outcome):
```bash
pnpm add @react-sigma/core @react-sigma/layout-forceatlas2 @react-sigma/minimap sigma graphology graphology-layout-forceatlas2
pnpm add -D graphology-types
```
If Task 1 confirmed `@sigma/edge-curve` is usable, also run:
```bash
pnpm add @sigma/edge-curve
```
Expected: pnpm installs the packages without peer-dep warnings. If a peer warning appears for `graphology-layout-forceatlas2` (or any other peer), install it explicitly before continuing.

- [ ] **Step 3: Verify the package.json diff**

Run:
```bash
git diff frontend/package.json
```
Expected output highlights:
- `react-force-graph-2d`, `d3-force` removed from `dependencies`
- `canvas` removed from `devDependencies`
- New entries under `dependencies`: `@react-sigma/core`, `@react-sigma/layout-forceatlas2`, `@react-sigma/minimap`, `sigma`, `graphology`, `graphology-layout-forceatlas2` (and `@sigma/edge-curve` if applicable)
- New entry under `devDependencies`: `graphology-types`

- [ ] **Step 4: Smoke-test the install by running the existing test suite**

Run from `frontend/`:
```bash
pnpm test
```
Expected: tests fail because `useGraphData` and `transforms` still import the old types — this is fine, we'll fix in Tasks 4 & 6. What we're verifying here is that **the install itself didn't break the test runner**: vitest must start, jsdom must initialize, and the failure must be a TS/import error, NOT a runtime crash inside vitest's bootstrap.

If vitest crashes before any test runs (e.g., missing canvas dep referenced from setup files), investigate before moving on. The spec predicts `canvas` is only needed by react-force-graph and is safe to drop — if anything else implicitly needed it, re-add it: `pnpm add -D canvas`.

- [ ] **Step 5: Commit**

Run from repo root:
```bash
git add frontend/package.json frontend/pnpm-lock.yaml
git commit -m "$(cat <<'EOF'
chore(frontend): swap react-force-graph for @react-sigma + graphology

Removes react-force-graph-2d, d3-force, and the canvas devDependency.
Adds @react-sigma/core, @react-sigma/layout-forceatlas2, @react-sigma/minimap,
sigma, graphology, graphology-layout-forceatlas2, and graphology-types
in preparation for the Sigma.js graph explorer migration (feature 42).

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Update `types/graph.ts`

**Files:**
- Modify: `frontend/src/types/graph.ts`

- [ ] **Step 1: Replace the file contents**

Write `frontend/src/types/graph.ts` with this exact content (substitute `EdgeAttributes.type` literal with `"arrow"` if Task 1 dropped `@sigma/edge-curve`):

```typescript
export type RdfTermType = "URI" | "LITERAL" | "BLANK_NODE" | "QUOTED_TRIPLE";

/**
 * Graphology node attributes — passed as Graph<NodeAttributes, EdgeAttributes>.
 * Includes the rendering fields Sigma needs (label, color, size, x, y) plus our
 * RDF-specific metadata (termType, isSubject, expanded).
 */
export interface NodeAttributes {
  label: string;
  termType: RdfTermType;
  isSubject: boolean;
  expanded: boolean;
  size: number;
  color: string;
  x: number;
  y: number;
}

/**
 * Graphology edge attributes. The `type` field must match a key registered
 * in SigmaContainer's edgeProgramClasses setting.
 */
export interface EdgeAttributes {
  predicate: string;
  dataset: string;
  label: string;
  size: number;
  type: "curved";
  color: string;
}

/**
 * Display type kept solely for the existing NodeDetail component, which
 * expects a flat snapshot of a node. Not used by Sigma rendering.
 */
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

/** Wire shape returned by the GraphMesh `triples` GraphQL query. */
export interface QuadDto {
  subject: string;
  predicate: string;
  object: string;
  dataset: string;
  objectType: string; // "URI" | "LITERAL" | "BLANK_NODE" | "QUOTED_TRIPLE"
  datatype?: string | null;
  language?: string | null;
}
```

Note: If Task 1 dropped `@sigma/edge-curve`, change `type: "curved"` to `type: "arrow"`.

- [ ] **Step 2: Verify TypeScript compiles for this file in isolation**

Run from `frontend/`:
```bash
pnpm exec tsc --noEmit
```
Expected: there will be many errors elsewhere (transforms, useGraphData, GraphCanvas, page.tsx, NodeDetail) because `GraphData`/`GraphEdge`/`LayoutConfig` are gone — that's expected and will be cleaned up by Tasks 4–10. Verify that **the errors are about missing imports, not syntax errors in `types/graph.ts` itself**.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/types/graph.ts
git commit -m "$(cat <<'EOF'
refactor(frontend/types): replace GraphData with graphology attribute types

Drops GraphData, GraphEdge, and LayoutConfig in favour of NodeAttributes
and EdgeAttributes, which graphology consumes via Graph<N,E> generics.
GraphNode is kept as a flat display type for the existing NodeDetail
component (which is intentionally not migrated).

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Rewrite `lib/graph/transforms.ts` (TDD)

**Files:**
- Modify: `frontend/src/lib/graph/transforms.ts`
- Modify: `frontend/src/__tests__/lib/graph/transforms.test.ts`

- [ ] **Step 1: Write the failing tests**

Replace `frontend/src/__tests__/lib/graph/transforms.test.ts` with:

```typescript
import { describe, it, expect } from "vitest";
import {
  applyEntityTypeFilter,
  createEmptyGraph,
  extractLabel,
  inferSubjectType,
  quadsToGraphologyGraph,
} from "@/lib/graph/transforms";
import { QuadDto } from "@/types/graph";

const RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

const q = (overrides: Partial<QuadDto> = {}): QuadDto => ({
  subject: "http://ex.org/a",
  predicate: "http://ex.org/p",
  object: "http://ex.org/b",
  dataset: "default",
  objectType: "URI",
  datatype: null,
  language: null,
  ...overrides,
});

describe("extractLabel", () => {
  it("returns suffix after last slash", () => {
    expect(extractLabel("http://ex.org/foo")).toBe("foo");
  });
  it("returns suffix after last hash", () => {
    expect(extractLabel("http://ex.org#bar")).toBe("bar");
  });
  it("returns input when no separator", () => {
    expect(extractLabel("plainlabel")).toBe("plainlabel");
  });
});

describe("inferSubjectType", () => {
  it("detects blank node", () => {
    expect(inferSubjectType("_:b1")).toBe("BLANK_NODE");
  });
  it("detects quoted triple", () => {
    expect(inferSubjectType("<<s|p|o>>")).toBe("QUOTED_TRIPLE");
  });
  it("defaults to URI", () => {
    expect(inferSubjectType("http://ex.org/a")).toBe("URI");
  });
});

describe("createEmptyGraph", () => {
  it("returns a directed multi graph", () => {
    const g = createEmptyGraph();
    expect(g.order).toBe(0);
    expect(g.size).toBe(0);
    expect(g.type).toBe("directed");
    expect(g.multi).toBe(true);
  });
});

describe("quadsToGraphologyGraph", () => {
  it("creates one node per distinct subject and object", () => {
    const g = quadsToGraphologyGraph([
      q({ subject: "a", object: "b" }),
      q({ subject: "a", object: "c", predicate: "p2" }),
    ]);
    expect(g.order).toBe(3);
    expect(g.size).toBe(2);
    expect(g.hasNode("a")).toBe(true);
    expect(g.hasNode("b")).toBe(true);
    expect(g.hasNode("c")).toBe(true);
  });

  it("marks subjects with isSubject=true", () => {
    const g = quadsToGraphologyGraph([q({ subject: "a", object: "b" })]);
    expect(g.getNodeAttribute("a", "isSubject")).toBe(true);
    expect(g.getNodeAttribute("b", "isSubject")).toBe(false);
  });

  it("uses LITERAL color and smaller size for literals", () => {
    const g = quadsToGraphologyGraph([
      q({ subject: "a", object: "lit", objectType: "LITERAL" }),
    ]);
    expect(g.getNodeAttribute("lit", "size")).toBe(4);
    expect(g.getNodeAttribute("lit", "termType")).toBe("LITERAL");
  });

  it("supports parallel edges with different predicates", () => {
    const g = quadsToGraphologyGraph([
      q({ subject: "a", object: "b", predicate: "p1" }),
      q({ subject: "a", object: "b", predicate: "p2" }),
    ]);
    expect(g.size).toBe(2);
    expect(g.hasEdge("a|p1|b")).toBe(true);
    expect(g.hasEdge("a|p2|b")).toBe(true);
  });

  it("merges into existing graph without duplicates", () => {
    const g = quadsToGraphologyGraph([q({ subject: "a", object: "b" })]);
    quadsToGraphologyGraph(
      [q({ subject: "a", object: "b" }), q({ subject: "a", object: "c", predicate: "p2" })],
      g,
    );
    expect(g.order).toBe(3);
    expect(g.size).toBe(2);
  });

  it("upgrades object-only nodes to subject when reused as subject", () => {
    const g = quadsToGraphologyGraph([q({ subject: "a", object: "b" })]);
    expect(g.getNodeAttribute("b", "isSubject")).toBe(false);
    quadsToGraphologyGraph([q({ subject: "b", object: "c" })], g);
    expect(g.getNodeAttribute("b", "isSubject")).toBe(true);
  });

  it("assigns starting positions x and y for ForceAtlas2", () => {
    const g = quadsToGraphologyGraph([q({ subject: "a", object: "b" })]);
    expect(typeof g.getNodeAttribute("a", "x")).toBe("number");
    expect(typeof g.getNodeAttribute("a", "y")).toBe("number");
  });

  it("sets edge label from predicate localname", () => {
    const g = quadsToGraphologyGraph([
      q({ subject: "a", predicate: "http://ex.org/knows", object: "b" }),
    ]);
    expect(g.getEdgeAttribute("a|http://ex.org/knows|b", "label")).toBe("knows");
  });
});

describe("applyEntityTypeFilter", () => {
  it("is a no-op when entityTypes is empty", () => {
    const g = quadsToGraphologyGraph([q({ subject: "a", object: "b" })]);
    applyEntityTypeFilter(g, []);
    expect(g.order).toBe(2);
  });

  it("drops subjects whose rdf:type is not in the allow list", () => {
    const g = quadsToGraphologyGraph([
      q({ subject: "alice", predicate: RDF_TYPE, object: "Person" }),
      q({ subject: "acme", predicate: RDF_TYPE, object: "Company" }),
      q({ subject: "alice", predicate: "knows", object: "bob" }),
    ]);
    applyEntityTypeFilter(g, ["Person"]);
    expect(g.hasNode("alice")).toBe(true);
    expect(g.hasNode("acme")).toBe(false);
  });
});
```

Note: If Task 1 dropped curved edges, the `extractLabel`/`inferSubjectType` exports must still be present in the new `transforms.ts` (legacy callers may exist; we keep them as named exports).

- [ ] **Step 2: Run the tests to confirm they fail**

Run from `frontend/`:
```bash
pnpm test -- src/__tests__/lib/graph/transforms.test.ts
```
Expected: all tests fail because `quadsToGraphologyGraph`, `createEmptyGraph`, and `applyEntityTypeFilter` are not exported yet.

- [ ] **Step 3: Implement `transforms.ts`**

Replace `frontend/src/lib/graph/transforms.ts` with (substitute `"curved"` → `"arrow"` in `addEdgeWithKey` if Task 1 dropped curved edges):

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

export function extractLabel(uri: string): string {
  const hash = uri.lastIndexOf("#");
  const slash = uri.lastIndexOf("/");
  const idx = Math.max(hash, slash);
  return idx >= 0 ? uri.slice(idx + 1) : uri;
}

export function inferSubjectType(uri: string): RdfTermType {
  if (uri.startsWith("_:")) return "BLANK_NODE";
  if (uri.startsWith("<<") && uri.endsWith(">>")) return "QUOTED_TRIPLE";
  return "URI";
}

export function createEmptyGraph(): Graph<NodeAttributes, EdgeAttributes> {
  return new Graph<NodeAttributes, EdgeAttributes>({
    type: "directed",
    multi: true,
    allowSelfLoops: true,
  });
}

/**
 * Inserts quads into a graphology graph, mutating it in place.
 * Idempotent: existing nodes and edges are not duplicated.
 *
 * @param target - existing graph to merge into. If omitted, a fresh graph is created.
 * @returns the same graph instance (or the freshly created one).
 */
export function quadsToGraphologyGraph(
  quads: QuadDto[],
  target?: Graph<NodeAttributes, EdgeAttributes>,
): Graph<NodeAttributes, EdgeAttributes> {
  const graph = target ?? createEmptyGraph();
  for (const quad of quads) {
    upsertNode(graph, quad.subject, inferSubjectType(quad.subject), true);
    const objectType = (quad.objectType as RdfTermType) ?? "URI";
    upsertNode(graph, quad.object, objectType, false);

    const edgeKey = `${quad.subject}|${quad.predicate}|${quad.object}`;
    if (!graph.hasEdge(edgeKey)) {
      graph.addEdgeWithKey(edgeKey, quad.subject, quad.object, {
        predicate: quad.predicate,
        dataset: quad.dataset,
        label: extractLabel(quad.predicate),
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
  graph.forEachEdge((edge, attrs) => {
    if (attrs.predicate === RDF_TYPE && allowed.has(graph.target(edge))) {
      allowedSubjects.add(graph.source(edge));
    }
  });
  const toDrop: string[] = [];
  graph.forEachNode((node, attrs) => {
    if (attrs.isSubject && !allowedSubjects.has(node)) toDrop.push(node);
  });
  for (const node of toDrop) graph.dropNode(node);
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run:
```bash
pnpm test -- src/__tests__/lib/graph/transforms.test.ts
```
Expected: all tests in this file pass. There may still be unrelated failures elsewhere — that's fine, we'll address them in subsequent tasks.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/graph/transforms.ts frontend/src/__tests__/lib/graph/transforms.test.ts
git commit -m "$(cat <<'EOF'
refactor(frontend/lib/graph): rewrite transforms around graphology

Replaces quadsToGraphData/mergeGraphData with quadsToGraphologyGraph,
which mutates a graphology Graph<NodeAttributes, EdgeAttributes> in
place. Adds createEmptyGraph and applyEntityTypeFilter helpers.

Tests cover idempotency, parallel edges (multi-graph), subject upgrade,
and entity-type filtering.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Create `lib/graph/highlight.ts` (TDD)

**Files:**
- Create: `frontend/src/lib/graph/highlight.ts`
- Create: `frontend/src/__tests__/lib/graph/highlight.test.ts`

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/__tests__/lib/graph/highlight.test.ts`:

```typescript
import { describe, it, expect } from "vitest";
import { buildEdgeReducer, buildNodeReducer } from "@/lib/graph/highlight";
import { quadsToGraphologyGraph } from "@/lib/graph/transforms";
import { QuadDto } from "@/types/graph";

const q = (s: string, p: string, o: string): QuadDto => ({
  subject: s,
  predicate: p,
  object: o,
  dataset: "default",
  objectType: "URI",
  datatype: null,
  language: null,
});

describe("buildNodeReducer", () => {
  it("returns identity when active node is null", () => {
    const g = quadsToGraphologyGraph([q("a", "p", "b")]);
    const reducer = buildNodeReducer(g, null);
    expect(reducer("a", { color: "#000" })).toEqual({ color: "#000" });
  });

  it("returns identity when active node does not exist in graph", () => {
    const g = quadsToGraphologyGraph([q("a", "p", "b")]);
    const reducer = buildNodeReducer(g, "ghost");
    expect(reducer("a", { color: "#000" })).toEqual({ color: "#000" });
  });

  it("highlights active node and its neighbors", () => {
    const g = quadsToGraphologyGraph([
      q("a", "p", "b"),
      q("a", "p", "c"),
      q("d", "p", "e"),
    ]);
    const reducer = buildNodeReducer(g, "a");
    expect(reducer("a", {}).highlighted).toBe(true);
    expect(reducer("b", {}).highlighted).toBe(true);
    expect(reducer("c", {}).highlighted).toBe(true);
  });

  it("fades non-neighbor nodes and clears their labels", () => {
    const g = quadsToGraphologyGraph([
      q("a", "p", "b"),
      q("d", "p", "e"),
    ]);
    const reducer = buildNodeReducer(g, "a");
    const faded = reducer("d", { color: "#fff", label: "Original" });
    expect(faded.label).toBe("");
    expect(faded.color).not.toBe("#fff");
  });
});

describe("buildEdgeReducer", () => {
  it("returns identity when active node is null", () => {
    const g = quadsToGraphologyGraph([q("a", "p", "b")]);
    const reducer = buildEdgeReducer(g, null);
    expect(reducer("a|p|b", { color: "#000" })).toEqual({ color: "#000" });
  });

  it("returns identity when active node does not exist in graph", () => {
    const g = quadsToGraphologyGraph([q("a", "p", "b")]);
    const reducer = buildEdgeReducer(g, "ghost");
    expect(reducer("a|p|b", { color: "#000" })).toEqual({ color: "#000" });
  });

  it("hides edges not incident to the active node", () => {
    const g = quadsToGraphologyGraph([
      q("a", "p", "b"),
      q("c", "p", "d"),
    ]);
    const reducer = buildEdgeReducer(g, "a");
    expect(reducer("a|p|b", {}).hidden).toBeUndefined();
    expect(reducer("c|p|d", {}).hidden).toBe(true);
  });

  it("colors incident edges with the highlight color", () => {
    const g = quadsToGraphologyGraph([q("a", "p", "b")]);
    const reducer = buildEdgeReducer(g, "a");
    const result = reducer("a|p|b", { color: "#999" });
    expect(result.color).not.toBe("#999");
  });
});
```

- [ ] **Step 2: Run the tests to confirm they fail**

Run:
```bash
pnpm test -- src/__tests__/lib/graph/highlight.test.ts
```
Expected: tests fail because `@/lib/graph/highlight` does not exist.

- [ ] **Step 3: Implement `highlight.ts`**

Create `frontend/src/lib/graph/highlight.ts`:

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

/**
 * Builds a node reducer that highlights the active node + its neighbors,
 * and dims everything else. Returns an identity function when no node is
 * active or the active node does not exist in the graph.
 */
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
    if (neighbors.has(node)) {
      return { ...data, highlighted: true };
    }
    return { ...data, color: FADED_COLOR, label: "" };
  };
}

/**
 * Builds an edge reducer that hides edges not incident to the active node
 * and colors incident edges with the highlight color.
 */
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

- [ ] **Step 4: Run the tests to confirm they pass**

Run:
```bash
pnpm test -- src/__tests__/lib/graph/highlight.test.ts
```
Expected: all tests in this file pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/graph/highlight.ts frontend/src/__tests__/lib/graph/highlight.test.ts
git commit -m "$(cat <<'EOF'
feat(frontend/lib/graph): add pure reducer factories for hover highlighting

buildNodeReducer and buildEdgeReducer return Sigma-compatible reducer
functions that highlight an active node + its neighbors and fade the
rest. Extracted as pure functions so they can be unit-tested in jsdom
without WebGL.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Rewrite `useGraphData` (TDD)

**Files:**
- Modify: `frontend/src/hooks/useGraphData.ts`
- Modify: `frontend/src/__tests__/hooks/useGraphData.test.tsx`

- [ ] **Step 1: Write the failing tests**

Replace `frontend/src/__tests__/hooks/useGraphData.test.tsx` with:

```tsx
import { describe, it, expect } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import { ReactNode } from "react";
import { useGraphData } from "@/hooks/useGraphData";
import {
  GRAPH_TRIPLES_QUERY,
  NODE_NEIGHBORS_QUERY,
} from "@/graphql/graph";

const quad = (s: string, p: string, o: string) => ({
  subject: s,
  predicate: p,
  object: o,
  dataset: "default",
  objectType: "URI",
  datatype: null,
  language: null,
});

const initialMock = {
  request: {
    query: GRAPH_TRIPLES_QUERY,
    variables: {
      collectionId: "c1",
      subject: null,
      predicate: null,
      object: null,
      dataset: null,
      limit: 500,
    },
  },
  result: {
    data: {
      triples: [quad("a", "p", "b"), quad("a", "p2", "c")],
    },
  },
};

const neighborMock = {
  request: {
    query: NODE_NEIGHBORS_QUERY,
    variables: { collectionId: "c1", entityUri: "b", limit: 50 },
  },
  result: {
    data: {
      asSubject: [quad("b", "p", "d")],
      asObject: [quad("a", "p", "b")],
    },
  },
};

const wrapper = (mocks: Parameters<typeof MockedProvider>[0]["mocks"]) => {
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <MockedProvider mocks={mocks} addTypename={false}>{children}</MockedProvider>
  );
  return Wrapper;
};

const emptyFilter = { datasets: [], predicates: [], entityTypes: [] };

describe("useGraphData", () => {
  it("starts with an empty graph and version 0", () => {
    const { result } = renderHook(() => useGraphData("c1"), {
      wrapper: wrapper([]),
    });
    expect(result.current.graph.order).toBe(0);
    expect(result.current.graph.size).toBe(0);
    expect(result.current.version).toBe(0);
  });

  it("loadInitial populates the graph and bumps version", async () => {
    const { result } = renderHook(() => useGraphData("c1"), {
      wrapper: wrapper([initialMock]),
    });

    await act(async () => {
      await result.current.loadInitial(emptyFilter);
    });

    await waitFor(() => {
      expect(result.current.version).toBeGreaterThan(0);
    });
    expect(result.current.graph.order).toBe(3);
    expect(result.current.graph.hasNode("a")).toBe(true);
    expect(result.current.graph.hasNode("b")).toBe(true);
    expect(result.current.graph.hasNode("c")).toBe(true);
  });

  it("expandNode merges neighbors without duplicates and marks expanded", async () => {
    const { result } = renderHook(() => useGraphData("c1"), {
      wrapper: wrapper([initialMock, neighborMock]),
    });

    await act(async () => {
      await result.current.loadInitial(emptyFilter);
    });
    await act(async () => {
      await result.current.expandNode("b");
    });

    await waitFor(() => {
      expect(result.current.graph.getNodeAttribute("b", "expanded")).toBe(true);
    });
    expect(result.current.graph.order).toBe(4); // a, b, c, d
    // The (a,p,b) triple appears in both initial and neighbor responses; it must not duplicate.
    expect(result.current.graph.hasEdge("a|p|b")).toBe(true);
    expect(result.current.graph.size).toBe(3); // (a,p,b), (a,p2,c), (b,p,d)
  });

  it("clear empties the graph and bumps version", async () => {
    const { result } = renderHook(() => useGraphData("c1"), {
      wrapper: wrapper([initialMock]),
    });
    await act(async () => {
      await result.current.loadInitial(emptyFilter);
    });
    const versionBefore = result.current.version;
    act(() => {
      result.current.clear();
    });
    expect(result.current.graph.order).toBe(0);
    expect(result.current.version).toBeGreaterThan(versionBefore);
  });

  it("loadInitial is a no-op when collectionId is empty", async () => {
    const { result } = renderHook(() => useGraphData(""), {
      wrapper: wrapper([]),
    });
    await act(async () => {
      await result.current.loadInitial(emptyFilter);
    });
    expect(result.current.graph.order).toBe(0);
    expect(result.current.version).toBe(0);
  });
});
```

- [ ] **Step 2: Run the tests to confirm they fail**

Run:
```bash
pnpm test -- src/__tests__/hooks/useGraphData.test.tsx
```
Expected: all tests fail because `useGraphData` still returns `graphData`/`loadInitial`/`expandNode` shaped around `GraphData`. The compile error or runtime error will mention `result.current.graph` being undefined.

- [ ] **Step 3: Rewrite `useGraphData.ts`**

Replace `frontend/src/hooks/useGraphData.ts` with:

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
import {
  EdgeAttributes,
  GraphFilter,
  NodeAttributes,
  QuadDto,
} from "@/types/graph";

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
    () => ({
      graph: graphRef.current!,
      version,
      loadInitial,
      expandNode,
      clear,
    }),
    [version, loadInitial, expandNode, clear],
  );
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run:
```bash
pnpm test -- src/__tests__/hooks/useGraphData.test.tsx
```
Expected: all five tests pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/hooks/useGraphData.ts frontend/src/__tests__/hooks/useGraphData.test.tsx
git commit -m "$(cat <<'EOF'
refactor(frontend/hooks): rewrite useGraphData around graphology + version

The hook now owns a mutable graphology Graph<NodeAttributes, EdgeAttributes>
in a useRef and exposes a version counter that is bumped after each
mutation (loadInitial, expandNode, clear). This eliminates the
GraphData/mergeGraphData round-trip and lets Sigma render the graph
directly via useLoadGraph(graph.copy()).

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Create `components/graph/GraphCanvasInner.tsx`

**Files:**
- Create: `frontend/src/components/graph/GraphCanvasInner.tsx`

This task has no isolated tests — see spec test strategy. Verification happens via Task 12 (build) and Task 13 (manual smoke test).

- [ ] **Step 1: Write `GraphCanvasInner.tsx`**

Create `frontend/src/components/graph/GraphCanvasInner.tsx` with the following content. **If Task 1 dropped `@sigma/edge-curve`**, remove the `EdgeCurveProgram` import, drop `edgeProgramClasses` from `SIGMA_SETTINGS`, and change `defaultEdgeType` to `"arrow"`.

```tsx
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
    // graphology graph is mutable; copy() snapshots the current state into sigma.
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

- [ ] **Step 2: TypeScript type-check this file**

Run from `frontend/`:
```bash
pnpm exec tsc --noEmit
```
Expected: this file should compile cleanly. There may still be errors in `GraphCanvas.tsx` and `app/graph/page.tsx` because they still reference the old API — those are addressed in Tasks 8 & 10. If `GraphCanvasInner.tsx` itself reports a type error, fix it before continuing.

If the type error mentions `useWorkerLayoutForceAtlas2` not being exported, double-check the package was installed in Task 2 and that the import path matches what the package exposes (some versions export from `@react-sigma/layout-forceatlas2/lib/worker` instead). Use context7 to verify if needed.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/graph/GraphCanvasInner.tsx
git commit -m "$(cat <<'EOF'
feat(frontend/graph): add Sigma.js GraphCanvasInner

The inner canvas component sets up a SigmaContainer with WebGL rendering,
runs ForceAtlas2 in a WebWorker via useWorkerLayoutForceAtlas2, registers
click/right-click/hover events through useRegisterEvents, and exposes a
declarative focus={id, nonce} prop wired to useCamera().gotoNode.

Hover/select highlighting delegates to the pure reducer factories in
lib/graph/highlight. Multi-edges between the same subject/object are
rendered as curved edges via @sigma/edge-curve.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Refactor `components/graph/GraphCanvas.tsx` to SSR-guard

**Files:**
- Modify: `frontend/src/components/graph/GraphCanvas.tsx`

- [ ] **Step 1: Replace the file contents**

Replace `frontend/src/components/graph/GraphCanvas.tsx` with:

```tsx
"use client";

import dynamic from "next/dynamic";
import { ComponentProps } from "react";

// Sigma touches `window` at module load time → ssr disabled.
const GraphCanvasInner = dynamic(() => import("./GraphCanvasInner"), {
  ssr: false,
  loading: () => (
    <div
      data-testid="graph-canvas-loading"
      className="h-full w-full animate-pulse bg-muted"
    />
  ),
});

export type GraphCanvasProps = ComponentProps<typeof GraphCanvasInner>;

export function GraphCanvas(props: GraphCanvasProps) {
  return <GraphCanvasInner {...props} />;
}
```

- [ ] **Step 2: Type-check**

Run:
```bash
pnpm exec tsc --noEmit
```
Expected: errors remaining in `app/graph/page.tsx` (still uses the old `GraphCanvas` API with `forwardRef`/`canvasRef.current?.centerOnNode` etc) and possibly `GraphControls.tsx` which is about to be deleted. `GraphCanvas.tsx` itself should compile.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/graph/GraphCanvas.tsx
git commit -m "$(cat <<'EOF'
refactor(frontend/graph): collapse GraphCanvas to an SSR-guard wrapper

GraphCanvas is now a thin re-export of GraphCanvasInner via
next/dynamic({ssr:false}), which is the only safe way to load Sigma
under the Next.js App Router (Sigma touches window at module load).
The forwardRef-based imperative handle is gone; consumers use the new
declarative focus={id, nonce} prop instead.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Delete `GraphControls.tsx`

**Files:**
- Delete: `frontend/src/components/graph/GraphControls.tsx`
- Delete: `frontend/src/__tests__/components/graph/GraphControls.test.tsx` (if it exists)

- [ ] **Step 1: Check whether a GraphControls test exists**

Run:
```bash
ls frontend/src/__tests__/components/graph/GraphControls.test.tsx 2>/dev/null && echo "exists" || echo "no test file"
```

- [ ] **Step 2: Delete the component file**

Run:
```bash
git rm frontend/src/components/graph/GraphControls.tsx
```
If Step 1 reported "exists":
```bash
git rm frontend/src/__tests__/components/graph/GraphControls.test.tsx
```

- [ ] **Step 3: Type-check**

Run:
```bash
pnpm exec tsc --noEmit
```
Expected: errors in `app/graph/page.tsx` about `GraphControls` and `LayoutConfig` imports — that's fine, Task 10 fixes them.

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
chore(frontend/graph): delete GraphControls component

The chargeStrength/linkDistance sliders steered d3-force parameters
that no longer apply to ForceAtlas2. Layout pause/resume is now handled
by LayoutForceAtlas2Control inside GraphCanvasInner.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Update `app/graph/page.tsx`

**Files:**
- Modify: `frontend/src/app/graph/page.tsx`

- [ ] **Step 1: Replace the file contents**

Replace `frontend/src/app/graph/page.tsx` with:

```tsx
"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@apollo/client/react";

import { GraphCanvas } from "@/components/graph/GraphCanvas";
import { GraphFilter } from "@/components/graph/GraphFilter";
import { NodeDetail } from "@/components/graph/NodeDetail";
import { EntitySearch } from "@/components/graph/EntitySearch";
import { CollectionSelector } from "@/components/documents/CollectionSelector";
import { useGraphData } from "@/hooks/useGraphData";
import { useActiveCollection } from "@/lib/collection-store";
import { GRAPH_METADATA_QUERY } from "@/graphql/graph";
import { GraphFilter as FilterType, GraphNode, RdfTermType } from "@/types/graph";

interface MetaData {
  graphMetadata: {
    datasets: string[];
    predicates: string[];
    entityTypes: string[];
  };
}

function GraphPageInner() {
  const { collectionId } = useActiveCollection();
  const searchParams = useSearchParams();
  const initialEntity = searchParams.get("entity");

  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [focus, setFocus] = useState<{ id: string; nonce: number } | null>(null);
  const [filter, setFilter] = useState<FilterType>({
    datasets: [],
    predicates: [],
    entityTypes: [],
  });

  const { graph, version, loadInitial, expandNode } = useGraphData(collectionId ?? "");

  const { data: metaData } = useQuery<MetaData>(GRAPH_METADATA_QUERY, {
    variables: { collectionId },
    skip: !collectionId,
  });

  const focusOn = (id: string) => setFocus({ id, nonce: Date.now() });

  useEffect(() => {
    if (!collectionId) return;
    let cancelled = false;
    void (async () => {
      await loadInitial(filter);
      if (cancelled) return;
      if (initialEntity) {
        await expandNode(initialEntity);
        if (cancelled) return;
        focusOn(initialEntity);
      }
    })();
    return () => {
      cancelled = true;
    };
    // initialEntity is read fresh per filter change; effect runs on filter change too.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [collectionId, filter, loadInitial, expandNode, initialEntity]);

  const selectedNode: GraphNode | null =
    selectedNodeId && graph.hasNode(selectedNodeId)
      ? {
          id: selectedNodeId,
          label: graph.getNodeAttribute(selectedNodeId, "label"),
          type: graph.getNodeAttribute(selectedNodeId, "termType") as RdfTermType,
          isSubject: graph.getNodeAttribute(selectedNodeId, "isSubject"),
          expanded: graph.getNodeAttribute(selectedNodeId, "expanded"),
          size: graph.getNodeAttribute(selectedNodeId, "size"),
        }
      : null;

  return (
    <main className="h-full flex flex-col">
      <header className="flex items-center gap-4 p-4 border-b">
        <h1 className="text-xl font-bold">Graph Explorer</h1>
        <CollectionSelector />
        {collectionId && (
          <EntitySearch
            collectionId={collectionId}
            onSelect={(uri) => {
              void expandNode(uri).then(() => focusOn(uri));
            }}
          />
        )}
      </header>
      <GraphFilter
        filter={filter}
        availableDatasets={metaData?.graphMetadata?.datasets ?? []}
        availablePredicates={metaData?.graphMetadata?.predicates ?? []}
        availableTypes={metaData?.graphMetadata?.entityTypes ?? []}
        onChange={setFilter}
      />
      <div className="flex-1 flex overflow-hidden">
        <div className="flex-1 relative">
          <GraphCanvas
            graph={graph}
            version={version}
            selectedNodeId={selectedNodeId}
            focus={focus}
            onNodeClick={(id) => setSelectedNodeId(id)}
            onNodeRightClick={(id) => {
              void expandNode(id);
            }}
          />
        </div>
        {selectedNode && collectionId && (
          <NodeDetail
            node={selectedNode}
            collectionId={collectionId}
            onExpand={(uri) => void expandNode(uri)}
            onClose={() => setSelectedNodeId(null)}
          />
        )}
      </div>
    </main>
  );
}

export default function GraphPage() {
  return (
    <Suspense fallback={null}>
      <GraphPageInner />
    </Suspense>
  );
}
```

- [ ] **Step 2: Type-check**

Run:
```bash
pnpm exec tsc --noEmit
```
Expected: clean — no errors. If `useActiveCollection`, `GRAPH_METADATA_QUERY`, or `NodeDetail`'s prop shape have moved or renamed since the spec was written, fix those references against the actual modules. The intended behavior is the same as the previous page.

- [ ] **Step 3: Run all tests**

Run:
```bash
pnpm test
```
Expected: pass. The tests cover hooks, transforms, highlight, and the unmodified `EntitySearch`/`GraphFilter`/`NodeDetail` components. The to-be-deleted `GraphCanvas.test.tsx` may still exist and fail — that's addressed in Task 11.

If `GraphCanvas.test.tsx` causes a hard test runner crash (e.g., trying to import from the new file that has a `.css` import), proceed to Task 11 first to delete it, then come back here.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/graph/page.tsx
git commit -m "$(cat <<'EOF'
refactor(frontend/graph): wire page.tsx to the new Sigma canvas + hook

Drops the layoutConfig state and the canvasRef forwardRef API. Adds a
focus={id, nonce} state that the new GraphCanvas consumes via the
internal FocusOnNode component. NodeDetail receives a freshly built
GraphNode snapshot from the graphology graph attributes (display
type kept for compat), so the existing NodeDetail component remains
unchanged.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Delete `__tests__/components/graph/GraphCanvas.test.tsx`

**Files:**
- Delete: `frontend/src/__tests__/components/graph/GraphCanvas.test.tsx`

- [ ] **Step 1: Delete the test file**

Run:
```bash
git rm frontend/src/__tests__/components/graph/GraphCanvas.test.tsx
```

- [ ] **Step 2: Run the full test suite**

Run from `frontend/`:
```bash
pnpm test
```
Expected: all tests pass.

- [ ] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
chore(frontend/test): drop obsolete GraphCanvas test

The test exercised react-force-graph's canvas drawing pipeline; the
new Sigma-based GraphCanvas is a thin wrapper around an SSR-guarded
dynamic import with no isolated logic worth testing. Hook + transforms
+ highlight reducers are tested directly in jsdom.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Verify build & full test suite

**Files:** none (verification)

- [ ] **Step 1: Lint**

Run from `frontend/`:
```bash
pnpm lint
```
Expected: clean. Address any errors before continuing — warnings are acceptable as long as the existing baseline did not introduce new ones.

- [ ] **Step 2: Type-check**

Run:
```bash
pnpm exec tsc --noEmit
```
Expected: clean.

- [ ] **Step 3: Test suite**

Run:
```bash
pnpm test
```
Expected: all tests pass.

- [ ] **Step 4: Production build**

Run:
```bash
pnpm build
```
Expected: build succeeds. **Specifically watch for "window is not defined" or "self is not defined" errors** — those would mean the SSR guard in `GraphCanvas.tsx` is not effective and Sigma is being evaluated at SSR time. If you see one:
- Verify that `GraphCanvas.tsx` uses `dynamic(..., { ssr: false })`
- Verify that no other module imports `GraphCanvasInner.tsx` directly without going through `GraphCanvas.tsx`
- Verify that `import "@react-sigma/core/lib/style.css"` is in `GraphCanvasInner.tsx`, NOT in `GraphCanvas.tsx` or `layout.tsx`

- [ ] **Step 5: Confirm bundle does not include react-force-graph**

Run from repo root:
```bash
grep -r "react-force-graph" frontend/.next 2>/dev/null | head -5
```
Expected: empty output. If anything matches, search the source for stray imports — use the parent agent's Grep tool with pattern `react-force-graph` and path `frontend/src/`. Any hit must be removed before this task is considered complete.

- [ ] **Step 6: No commit** (verification only — nothing to commit)

---

## Task 13: Manual smoke test (qualitative perf check)

**Files:** none (manual verification)

This task requires a human (or supervising agent) at the browser. The subagent runs the dev server in the background and reports the URL. The supervising agent tells the user to perform the checks.

- [ ] **Step 1: Start the dev server**

Run from `frontend/`:
```bash
pnpm dev
```
(Run in the background, e.g., `run_in_background: true`. Note the URL it prints — usually `http://localhost:3000`.)

- [ ] **Step 2: Manual checklist for the human**

Open `http://localhost:3000/graph` and verify:

1. **Page loads** without console errors. The "graph-canvas-loading" placeholder briefly appears, then the Sigma canvas mounts.
2. **Pick a collection** with at least ~50 triples.
3. **Node rendering:** nodes are colored by termType (URI=indigo, LITERAL=green). Labels are visible.
4. **Multi-edges:** if the dataset has multiple predicates between the same pair of entities, they render as separate curved edges. (If `@sigma/edge-curve` was dropped in Task 1, they overlap as straight arrows — acceptable.)
5. **ForceAtlas2 is running in a worker:** open DevTools → Performance, record 5 seconds of layout settling. Look at the Main thread track. There should be no continuous long task — work happens on a worker thread. (Qualitative: if pan/zoom feels smooth at ~100+ nodes, the architectural goal is met.)
6. **Hover highlight:** hovering a node fades the rest and clears their labels. Edges to non-neighbors are hidden.
7. **Click a node:** NodeDetail panel opens showing the node's URI/triples. The clicked node remains highlighted after the cursor leaves.
8. **Right-click a node:** the browser's native context menu does NOT appear, and `expandNode` runs (new neighbors fade in).
9. **Entity search (header input):** type ≥3 characters of an entity URI suffix; the dropdown should show backend search results. Selecting one expands it and the camera animates to it.
10. **Layout controls (bottom-right):** Zoom +/− buttons work. Fullscreen toggle works. ForceAtlas2 play/pause toggle works.
11. **Minimap (bottom-left):** shows the current viewport as a colored rectangle.
12. **Reload the page** with `?entity=<some-uri>` in the URL — the graph should load and center on that entity.

- [ ] **Step 3: Stop the dev server**

If running in background, kill it. Otherwise Ctrl+C.

- [ ] **Step 4: Capture issues for the done doc**

If any of the 12 checks failed, note them — they belong in the "Open questions / known issues" section of the done doc (Task 14). Critical functional regressions (1, 7, 9) must be fixed before Task 14; cosmetic regressions (5 perf, 11 minimap layout) can be documented and addressed later.

- [ ] **Step 5: No commit**

---

## Task 14: Write `docs/features/42-graph-explorer-sigmajs-done.md`

**Files:**
- Create: `docs/features/42-graph-explorer-sigmajs-done.md`

- [ ] **Step 1: Write the done doc**

Create `docs/features/42-graph-explorer-sigmajs-done.md` with this skeleton (fill in the `[…]` placeholders from your actual implementation experience):

```markdown
# Feature 42: Graph Explorer Sigma.js Migration — Done

**Implementation date:** 2026-04-08
**Spec:** `docs/superpowers/specs/2026-04-08-graph-explorer-sigmajs-design.md`
**Plan:** `docs/superpowers/plans/2026-04-08-feature-42-graph-explorer-sigmajs.md`

## Summary

Migrated the `/graph` page from `react-force-graph-2d` to `@react-sigma/core` v[…] +
`graphology` v[…] + ForceAtlas2 in a WebWorker. Hook ownership of the
graphology Graph + version-counter pattern, declarative focus prop, and
extracted reducer factories all landed as designed.

## What changed

- `frontend/src/lib/graph/transforms.ts` — rewritten around `quadsToGraphologyGraph`
- `frontend/src/lib/graph/highlight.ts` — new pure reducer factories
- `frontend/src/hooks/useGraphData.ts` — rewritten around graphology + version
- `frontend/src/components/graph/GraphCanvas.tsx` — collapsed to SSR-guard
- `frontend/src/components/graph/GraphCanvasInner.tsx` — new Sigma wiring
- `frontend/src/components/graph/GraphControls.tsx` — deleted
- `frontend/src/app/graph/page.tsx` — focus state, no canvasRef, no layoutConfig
- `frontend/src/types/graph.ts` — NodeAttributes/EdgeAttributes added, GraphData/GraphEdge/LayoutConfig dropped
- `frontend/package.json` — sigma + graphology in, react-force-graph + canvas out

## Deviations from the plan

[List anything that ended up different from the spec/plan, e.g.:
- Task 1: had to fall back to `defaultEdgeType: "arrow"` because `@sigma/edge-curve` …
- Task 7: `useWorkerLayoutForceAtlas2` import path was actually …
- Task 12: bundle still references … because …]

If nothing deviated, write "None — implementation matched the plan exactly."

## Open questions / known issues / tech debt

[Anything from Task 13 that didn't pass, anything you noticed during impl
that should be addressed later but didn't block this feature.]

If nothing, write "None."

## Verification

- [x] `pnpm lint` clean
- [x] `pnpm exec tsc --noEmit` clean
- [x] `pnpm test` passing — `[N]` tests
- [x] `pnpm build` succeeds without "window is not defined"
- [x] `react-force-graph-2d` not present in `frontend/.next` bundle
- [x] Manual smoke test (Task 13) passed
```

- [ ] **Step 2: Update memory if anything surprising came up**

If during implementation you hit a non-obvious gotcha (e.g., a Sigma version-specific quirk, a Next.js 14 interaction with `next/dynamic` that bit you, a peer-dep that needed explicit installation), consider whether it belongs in `~/.claude/projects/-Users-czarnik-IdeaProjects-GraphMesh/memory/` as a project memory. The MEMORY.md already has `project_frontend_stack.md` — if the gotcha is sticky, append it there rather than creating a new file.

- [ ] **Step 3: Commit**

```bash
git add docs/features/42-graph-explorer-sigmajs-done.md
git commit -m "$(cat <<'EOF'
docs(feature 42): mark graph explorer sigma.js migration as done

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Coverage check (spec → plan)

| Spec acceptance criterion | Task(s) |
|---|---|
| `/graph` renders via Sigma WebGL; no react-force-graph in bundle | 7, 8, 12 (Step 5) |
| `useGraphData` exposes graphology Graph + version | 6 |
| `quadsToGraphologyGraph` is idempotent | 4 (test cases) |
| Multi-edges render as curved edges | 4 (parallel edges test), 7 (edgeProgramClasses) |
| Hover fades non-neighbors and hides labels | 5 (highlight tests), 7 (GraphEvents) |
| Selected node stays highlighted on cursor leave | 7 (`hoveredNode ?? selectedNodeId`) |
| Right-click triggers expandNode + suppresses native menu | 7 (`preventSigmaDefault`), 13 (manual) |
| ForceAtlas2 in WebWorker | 7 (`useWorkerLayoutForceAtlas2`), 13 (DevTools check) |
| Zoom/Fullscreen/Layout/MiniMap controls visible | 7, 13 |
| EntitySearch finds entities not in graph + camera focus | 10 (`focusOn`), 13 |
| Sigma loaded client-only via dynamic({ssr:false}); build OK | 8, 12 (Step 4) |
| `pnpm test` passes without `canvas` devDep | 2, 12 |
| Old deps removed | 2 |
| `GraphControls.tsx` and `LayoutConfig` deleted | 3, 9 |
| Filter/NodeDetail/EntitySearch/CollectionSelector still work | 10, 13 |

## Risk handling (from spec "Open questions / risks")

| Risk | Where it's addressed |
|---|---|
| `@sigma/edge-curve` package name unverified | Task 1 (resolve + query), Task 2 (conditional install), Tasks 3/4/7 (conditional `arrow` fallback) |
| `@react-sigma/layout-forceatlas2` peer deps | Task 1 (query), Task 2 (explicit `graphology-layout-forceatlas2` install + warning check) |
| `useRef` lazy init in StrictMode | Task 6 uses the React-team-recommended `if (!ref.current)` idiom (StrictMode-safe) |
| `<WorkerLayout>` vs `LayoutForceAtlas2Control` conflict | Task 13 manual check 10 verifies the play/pause toggle works alongside auto-start; if it conflicts, drop `<WorkerLayout>` (note in done doc, fix inline) |
