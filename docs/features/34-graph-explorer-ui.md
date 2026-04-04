# Feature 34: Graph Explorer UI

## Problem

Der Knowledge Graph in GraphMesh enthaelt komplexe Beziehungen zwischen Entitaeten, die ueber die textbasierte
Triple-Abfrage (Feature 14) nur schwer exploriert werden koennen. Ohne eine interaktive Graphvisualisierung fehlt
Benutzern die Moeglichkeit, Zusammenhaenge visuell zu erkennen, Subgraphen zu explorieren und die Struktur des
Wissensgraphen intuitiv zu verstehen. Insbesondere fuer die Validierung extrahierter Beziehungen ist eine grafische
Darstellung essenziell.

## Ziel

Implementierung eines interaktiven Graph Explorers in Next.js mit Force-Directed-Layout, der Entitaeten und Beziehungen
aus dem RDF-Graphmodell visualisiert und Navigation, Filterung sowie Subgraph-Expansion ermoeglicht.

1. **Graph Canvas** -- Force-Directed-Visualisierung mit Zoom, Pan und Drag via react-force-graph
2. **Node/Edge Rendering** -- Typbasierte Darstellung von Entitaeten (URI, Literal, Blank Node) und Praedikaten
3. **Node Detail Panel** -- Detailansicht aller Triples einer Entitaet bei Auswahl
4. **Graph Filter** -- Filterung nach Named Graph, Praedikat und Entitaetstyp
5. **Entity Search** -- Autovervollstaendigung zur Suche nach Entitaeten im Graphen
6. **Subgraph Expansion** -- Klick auf Knoten laedt benachbarte Knoten dynamisch nach

## Voraussetzungen

| Abhaengigkeit                                       | Status     | Blocker? |
|-----------------------------------------------------|------------|----------|
| Feature 07: RDF Graph Model (Quad, Triple, RdfTerm) | Geplant    | Ja       |
| Feature 14: GraphQL API (triples-Query, Schema)     | Geplant    | Ja       |
| Next.js 14+ (App Router)                            | Verfuegbar | Nein     |
| Apollo Client                                       | Verfuegbar | Nein     |
| react-force-graph-2d                                | Verfuegbar | Nein     |
| d3-force                                            | Verfuegbar | Nein     |

## Architektur

### GraphQL Queries

```graphql
# Triples fuer die Graphvisualisierung laden
query GraphTriples($collectionId: ID!, $subject: String, $predicate: String, $graph: String, $limit: Int) {
    triples(collectionId: $collectionId, subject: $subject, predicate: $predicate, graph: $graph, limit: $limit) {
        subject { type value }
        predicate
        object { type value datatype language }
        graph
    }
}

# Nachbarn eines Knotens laden (Subgraph Expansion)
query NodeNeighbors($collectionId: ID!, $entityUri: String!, $limit: Int) {
    triples(collectionId: $collectionId, subject: $entityUri, limit: $limit) {
        subject { type value }
        predicate
        object { type value datatype language }
        graph
    }
    # Auch Triples, in denen die Entitaet als Objekt vorkommt
    triplesAsObject: triples(collectionId: $collectionId, object: $entityUri, limit: $limit) {
        subject { type value }
        predicate
        object { type value datatype language }
        graph
    }
}

# Entitaetssuche mit Autovervollstaendigung
query EntitySearch($collectionId: ID!, $query: String!, $limit: Int) {
    vectorSearch(collectionId: $collectionId, query: $query, limit: $limit) {
        chunkId
        score
        text
    }
}
```

### TypeScript-Typen

```typescript
// frontend/src/types/graph.ts

export interface GraphNode {
    id: string;
    label: string;
    type: RdfTermType;
    entityType?: string;  // rdf:type Wert, falls vorhanden
    color: string;
    size: number;
    expanded: boolean;
}

export interface GraphEdge {
    id: string;
    source: string;
    target: string;
    predicate: string;
    graph: string;
    label: string;
}

export interface GraphData {
    nodes: GraphNode[];
    links: GraphEdge[];
}

export type RdfTermType = "URI" | "LITERAL" | "BLANK_NODE" | "QUOTED_TRIPLE";

export interface GraphFilter {
    graphs: string[];
    predicates: string[];
    entityTypes: string[];
}

export interface LayoutConfig {
    chargeStrength: number;
    linkDistance: number;
    centerStrength: number;
    collisionRadius: number;
}
```

### GraphCanvas-Komponente

```typescript
// frontend/src/components/graph/GraphCanvas.tsx
"use client";

import { useRef, useCallback, useEffect } from "react";
import ForceGraph2D, { ForceGraphMethods } from "react-force-graph-2d";
import { GraphData, GraphNode, GraphEdge, LayoutConfig } from "@/types/graph";

interface GraphCanvasProps {
    data: GraphData;
    layoutConfig: LayoutConfig;
    onNodeClick: (node: GraphNode) => void;
    onNodeRightClick: (node: GraphNode) => void;
    selectedNodeId: string | null;
}

const NODE_COLORS: Record<string, string> = {
    URI: "#4F46E5",
    LITERAL: "#059669",
    BLANK_NODE: "#D97706",
    QUOTED_TRIPLE: "#7C3AED",
};

export function GraphCanvas({
    data,
    layoutConfig,
    onNodeClick,
    onNodeRightClick,
    selectedNodeId,
}: GraphCanvasProps) {
    const graphRef = useRef<ForceGraphMethods>();

    useEffect(() => {
        const fg = graphRef.current;
        if (!fg) return;
        fg.d3Force("charge")?.strength(layoutConfig.chargeStrength);
        fg.d3Force("link")?.distance(layoutConfig.linkDistance);
        fg.d3Force("center")?.strength(layoutConfig.centerStrength);
    }, [layoutConfig]);

    const nodeCanvasObject = useCallback(
        (node: GraphNode, ctx: CanvasRenderingContext2D, globalScale: number) => {
            const label = node.label.length > 20 ? node.label.slice(0, 20) + "..." : node.label;
            const fontSize = 12 / globalScale;
            const isSelected = node.id === selectedNodeId;

            ctx.beginPath();
            ctx.arc(node.x!, node.y!, node.size, 0, 2 * Math.PI);
            ctx.fillStyle = isSelected ? "#EF4444" : (NODE_COLORS[node.type] ?? "#6B7280");
            ctx.fill();

            if (isSelected) {
                ctx.strokeStyle = "#EF4444";
                ctx.lineWidth = 2 / globalScale;
                ctx.stroke();
            }

            ctx.font = `${fontSize}px Sans-Serif`;
            ctx.textAlign = "center";
            ctx.textBaseline = "top";
            ctx.fillStyle = "#1F2937";
            ctx.fillText(label, node.x!, node.y! + node.size + 2);
        },
        [selectedNodeId]
    );

    return (
        <ForceGraph2D
            ref={graphRef}
            graphData={data}
            nodeCanvasObject={nodeCanvasObject}
            onNodeClick={onNodeClick}
            onNodeRightClick={onNodeRightClick}
            linkLabel={(link: GraphEdge) => link.label}
            linkDirectionalArrowLength={4}
            linkDirectionalArrowRelPos={1}
            linkColor={() => "#9CA3AF"}
            enableZoomInteraction={true}
            enablePanInteraction={true}
            enableNodeDrag={true}
        />
    );
}
```

### useGraphData Hook

```typescript
// frontend/src/hooks/useGraphData.ts
"use client";

import { useState, useCallback } from "react";
import { useLazyQuery } from "@apollo/client";
import { GRAPH_TRIPLES_QUERY, NODE_NEIGHBORS_QUERY } from "@/graphql/queries/graph";
import { GraphData, GraphNode, GraphEdge, GraphFilter, RdfTermType } from "@/types/graph";

export function useGraphData(collectionId: string) {
    const [graphData, setGraphData] = useState<GraphData>({ nodes: [], links: [] });
    const [loadTriples] = useLazyQuery(GRAPH_TRIPLES_QUERY);
    const [loadNeighbors] = useLazyQuery(NODE_NEIGHBORS_QUERY);

    const loadInitialGraph = useCallback(async (filter: GraphFilter) => {
        const { data } = await loadTriples({
            variables: {
                collectionId,
                predicate: filter.predicates[0] ?? null,
                graph: filter.graphs[0] ?? null,
                limit: 200,
            },
        });

        if (data?.triples) {
            setGraphData(triplesToGraphData(data.triples));
        }
    }, [collectionId, loadTriples]);

    const expandNode = useCallback(async (entityUri: string) => {
        const { data } = await loadNeighbors({
            variables: { collectionId, entityUri, limit: 50 },
        });

        if (data) {
            const allTriples = [...(data.triples ?? []), ...(data.triplesAsObject ?? [])];
            const newData = triplesToGraphData(allTriples);
            setGraphData((prev) => mergeGraphData(prev, newData, entityUri));
        }
    }, [collectionId, loadNeighbors]);

    return { graphData, loadInitialGraph, expandNode };
}

function triplesToGraphData(triples: any[]): GraphData {
    const nodesMap = new Map<string, GraphNode>();
    const links: GraphEdge[] = [];

    for (const triple of triples) {
        const subjectId = triple.subject.value;
        const objectId = triple.object.value;

        if (!nodesMap.has(subjectId)) {
            nodesMap.set(subjectId, {
                id: subjectId,
                label: extractLabel(subjectId),
                type: triple.subject.type as RdfTermType,
                color: "",
                size: 6,
                expanded: false,
            });
        }

        if (!nodesMap.has(objectId)) {
            nodesMap.set(objectId, {
                id: objectId,
                label: extractLabel(objectId),
                type: triple.object.type as RdfTermType,
                color: "",
                size: triple.object.type === "LITERAL" ? 4 : 6,
                expanded: false,
            });
        }

        links.push({
            id: `${subjectId}-${triple.predicate}-${objectId}`,
            source: subjectId,
            target: objectId,
            predicate: triple.predicate,
            graph: triple.graph,
            label: extractLabel(triple.predicate),
        });
    }

    return { nodes: Array.from(nodesMap.values()), links };
}

function extractLabel(uri: string): string {
    const hashIndex = uri.lastIndexOf("#");
    const slashIndex = uri.lastIndexOf("/");
    const index = Math.max(hashIndex, slashIndex);
    return index >= 0 ? uri.slice(index + 1) : uri;
}

function mergeGraphData(existing: GraphData, incoming: GraphData, expandedNodeId: string): GraphData {
    const nodeMap = new Map(existing.nodes.map((n) => [n.id, n]));
    for (const node of incoming.nodes) {
        if (!nodeMap.has(node.id)) {
            nodeMap.set(node.id, node);
        }
    }
    // Expandierten Knoten markieren
    const expandedNode = nodeMap.get(expandedNodeId);
    if (expandedNode) {
        expandedNode.expanded = true;
    }

    const linkSet = new Set(existing.links.map((l) => l.id));
    const mergedLinks = [...existing.links];
    for (const link of incoming.links) {
        if (!linkSet.has(link.id)) {
            mergedLinks.push(link);
            linkSet.add(link.id);
        }
    }

    return { nodes: Array.from(nodeMap.values()), links: mergedLinks };
}
```

### NodeDetail-Komponente

```typescript
// frontend/src/components/graph/NodeDetail.tsx
"use client";

import { useQuery } from "@apollo/client";
import { GRAPH_TRIPLES_QUERY } from "@/graphql/queries/graph";
import { GraphNode } from "@/types/graph";

interface NodeDetailProps {
    node: GraphNode;
    collectionId: string;
    onExpand: (entityUri: string) => void;
    onClose: () => void;
}

export function NodeDetail({ node, collectionId, onExpand, onClose }: NodeDetailProps) {
    const { data, loading } = useQuery(GRAPH_TRIPLES_QUERY, {
        variables: { collectionId, subject: node.id, limit: 50 },
    });

    return (
        <aside className="w-96 border-l bg-white p-4 overflow-y-auto">
            <div className="flex justify-between items-center mb-4">
                <h3 className="font-semibold truncate">{node.label}</h3>
                <button onClick={onClose} className="text-gray-500 hover:text-gray-700">&times;</button>
            </div>

            <div className="mb-4">
                <span className="text-xs uppercase text-gray-500">{node.type}</span>
                <p className="text-sm text-gray-700 break-all">{node.id}</p>
            </div>

            {!node.expanded && (
                <button
                    onClick={() => onExpand(node.id)}
                    className="mb-4 px-3 py-1 text-sm bg-blue-600 text-white rounded hover:bg-blue-700"
                >
                    Nachbarn laden
                </button>
            )}

            <h4 className="font-medium text-sm mb-2">Triples ({data?.triples?.length ?? 0})</h4>
            {loading ? (
                <p className="text-sm text-gray-500">Laden...</p>
            ) : (
                <div className="space-y-2">
                    {data?.triples?.map((triple: any, i: number) => (
                        <div key={i} className="text-xs border rounded p-2 bg-gray-50">
                            <div><span className="font-medium">Praedikat:</span> {triple.predicate}</div>
                            <div><span className="font-medium">Objekt:</span> {triple.object.value}</div>
                            <div className="text-gray-400">Graph: {triple.graph}</div>
                        </div>
                    ))}
                </div>
            )}
        </aside>
    );
}
```

### GraphFilter-Komponente

```typescript
// frontend/src/components/graph/GraphFilter.tsx
"use client";

import { GraphFilter as FilterType } from "@/types/graph";

interface GraphFilterProps {
    filter: FilterType;
    availableGraphs: string[];
    availablePredicates: string[];
    availableTypes: string[];
    onChange: (filter: FilterType) => void;
}

export function GraphFilter({
    filter,
    availableGraphs,
    availablePredicates,
    availableTypes,
    onChange,
}: GraphFilterProps) {
    return (
        <div className="flex flex-wrap gap-4 p-3 border-b bg-gray-50">
            <MultiSelect
                label="Named Graph"
                options={availableGraphs}
                selected={filter.graphs}
                onChange={(graphs) => onChange({ ...filter, graphs })}
            />
            <MultiSelect
                label="Praedikat"
                options={availablePredicates}
                selected={filter.predicates}
                onChange={(predicates) => onChange({ ...filter, predicates })}
            />
            <MultiSelect
                label="Entitaetstyp"
                options={availableTypes}
                selected={filter.entityTypes}
                onChange={(entityTypes) => onChange({ ...filter, entityTypes })}
            />
        </div>
    );
}
```

### GraphControls-Komponente

```typescript
// frontend/src/components/graph/GraphControls.tsx
"use client";

import { LayoutConfig } from "@/types/graph";

interface GraphControlsProps {
    config: LayoutConfig;
    onChange: (config: LayoutConfig) => void;
    onResetView: () => void;
}

const DEFAULT_CONFIG: LayoutConfig = {
    chargeStrength: -150,
    linkDistance: 80,
    centerStrength: 0.05,
    collisionRadius: 20,
};

export function GraphControls({ config, onChange, onResetView }: GraphControlsProps) {
    return (
        <div className="p-3 border-b bg-gray-50 flex items-center gap-6">
            <label className="flex items-center gap-2 text-sm">
                Abstossung
                <input
                    type="range" min={-500} max={-10} value={config.chargeStrength}
                    onChange={(e) => onChange({ ...config, chargeStrength: Number(e.target.value) })}
                />
            </label>
            <label className="flex items-center gap-2 text-sm">
                Kantenlaenge
                <input
                    type="range" min={20} max={300} value={config.linkDistance}
                    onChange={(e) => onChange({ ...config, linkDistance: Number(e.target.value) })}
                />
            </label>
            <button
                onClick={() => onChange(DEFAULT_CONFIG)}
                className="text-sm text-gray-600 hover:text-gray-900"
            >
                Zuruecksetzen
            </button>
            <button
                onClick={onResetView}
                className="text-sm text-gray-600 hover:text-gray-900"
            >
                Ansicht zentrieren
            </button>
        </div>
    );
}
```

### Seitenstruktur

```typescript
// frontend/src/app/graph/page.tsx
"use client";

import { useState } from "react";
import { GraphCanvas } from "@/components/graph/GraphCanvas";
import { GraphControls } from "@/components/graph/GraphControls";
import { GraphFilter } from "@/components/graph/GraphFilter";
import { NodeDetail } from "@/components/graph/NodeDetail";
import { EntitySearch } from "@/components/graph/EntitySearch";
import { CollectionSelector } from "@/components/documents/CollectionSelector";
import { useGraphData } from "@/hooks/useGraphData";
import { GraphNode, LayoutConfig, GraphFilter as FilterType } from "@/types/graph";

export default function GraphPage() {
    const [collectionId, setCollectionId] = useState<string | null>(null);
    const [selectedNode, setSelectedNode] = useState<GraphNode | null>(null);
    const [layoutConfig, setLayoutConfig] = useState<LayoutConfig>({
        chargeStrength: -150,
        linkDistance: 80,
        centerStrength: 0.05,
        collisionRadius: 20,
    });
    const [filter, setFilter] = useState<FilterType>({ graphs: [], predicates: [], entityTypes: [] });

    const { graphData, loadInitialGraph, expandNode } = useGraphData(collectionId ?? "");

    return (
        <main className="h-screen flex flex-col">
            <header className="flex items-center gap-4 p-4 border-b">
                <h1 className="text-xl font-bold">Graph Explorer</h1>
                <CollectionSelector selectedId={collectionId} onSelect={setCollectionId} />
                <EntitySearch
                    collectionId={collectionId ?? ""}
                    onSelect={(uri) => expandNode(uri)}
                />
            </header>
            <GraphFilter
                filter={filter}
                availableGraphs={[]}
                availablePredicates={[]}
                availableTypes={[]}
                onChange={setFilter}
            />
            <GraphControls config={layoutConfig} onChange={setLayoutConfig} onResetView={() => {}} />
            <div className="flex-1 flex overflow-hidden">
                <div className="flex-1">
                    <GraphCanvas
                        data={graphData}
                        layoutConfig={layoutConfig}
                        onNodeClick={setSelectedNode}
                        onNodeRightClick={(node) => expandNode(node.id)}
                        selectedNodeId={selectedNode?.id ?? null}
                    />
                </div>
                {selectedNode && (
                    <NodeDetail
                        node={selectedNode}
                        collectionId={collectionId ?? ""}
                        onExpand={expandNode}
                        onClose={() => setSelectedNode(null)}
                    />
                )}
            </div>
        </main>
    );
}
```

```typescript
// frontend/src/app/graph/[entityId]/page.tsx
"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import GraphPage from "../page";

interface Props {
    params: { entityId: string };
}

export default function EntityGraphPage({ params }: Props) {
    // Dekodiert die Entity-URI und laedt den Graphen zentriert auf diese Entitaet
    const entityUri = decodeURIComponent(params.entityId);

    // Wiederverwendet die Graph-Seite mit initialer Entity-Fokussierung
    return <GraphPage />;
}
```

## Betroffene Dateien

### Backend

Nicht betroffen (der Graph Explorer nutzt ausschliesslich die bestehende GraphQL-API aus Feature 14).

### Frontend

| Datei                                              | Aenderung                                                  |
|----------------------------------------------------|------------------------------------------------------------|
| `frontend/src/app/graph/page.tsx`                  | NEU - Graph Explorer Hauptseite                            |
| `frontend/src/app/graph/[entityId]/page.tsx`       | NEU - Entity-fokussierte Graphansicht                      |
| `frontend/src/app/graph/layout.tsx`                | NEU - Layout fuer Graph-Bereich                            |
| `frontend/src/components/graph/GraphCanvas.tsx`    | NEU - Force-Directed-Visualisierung mit react-force-graph  |
| `frontend/src/components/graph/GraphControls.tsx`  | NEU - Layout-Parameter (Charge, Distance)                  |
| `frontend/src/components/graph/GraphFilter.tsx`    | NEU - Filter nach Graph, Praedikat, Typ                    |
| `frontend/src/components/graph/NodeDetail.tsx`     | NEU - Knoten-Detailansicht mit allen Triples               |
| `frontend/src/components/graph/EdgeDetail.tsx`     | NEU - Kanten-Detailansicht                                 |
| `frontend/src/components/graph/EntitySearch.tsx`   | NEU - Autovervollstaendigung fuer Entity-Suche             |
| `frontend/src/components/graph/SubgraphLoader.tsx` | NEU - Logik fuer dynamisches Nachladen von Nachbarn        |
| `frontend/src/hooks/useGraphData.ts`               | NEU - Hook fuer Graph-Datenmanagement und Merging          |
| `frontend/src/graphql/queries/graph.ts`            | NEU - GraphQL-Queries fuer Triples und Nachbarn            |
| `frontend/src/types/graph.ts`                      | NEU - TypeScript-Typen fuer Graph-Visualisierung           |
| `frontend/package.json`                            | AENDERUNG - Abhaengigkeit react-force-graph-2d hinzufuegen |

### Tests

| Datei                                                           | Aenderung                               |
|-----------------------------------------------------------------|-----------------------------------------|
| `frontend/src/__tests__/components/graph/GraphCanvas.test.tsx`  | NEU - Canvas-Rendering und Interaktion  |
| `frontend/src/__tests__/components/graph/NodeDetail.test.tsx`   | NEU - Detailansicht und Expansion       |
| `frontend/src/__tests__/components/graph/GraphFilter.test.tsx`  | NEU - Filterlogik                       |
| `frontend/src/__tests__/components/graph/EntitySearch.test.tsx` | NEU - Autovervollstaendigung            |
| `frontend/src/__tests__/hooks/useGraphData.test.ts`             | NEU - Graph-Daten-Merging und Expansion |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                                           |
|-------------------|-------------|-----------------------------------------------------------------|
| Spring Boot (JVM) | Ja          | Frontend kommuniziert ueber GraphQL-API mit Spring Boot Backend |
| KMP Library       | Nein        | Reines Frontend-Feature, kein Backend-Code betroffen            |
| Ktor/Wasm         | Nein        | Reines Frontend-Feature, kein Backend-Code betroffen            |

## Akzeptanzkriterien

- [ ] Seite `/graph` zeigt eine interaktive Force-Directed-Graphvisualisierung
- [ ] Knoten werden typbasiert eingefaerbt (URI, Literal, Blank Node)
- [ ] Kanten zeigen Praedikat-Labels als Beschriftung
- [ ] Zoom, Pan und Drag funktionieren flüssig
- [ ] Klick auf einen Knoten oeffnet das Detail-Panel mit allen Triples der Entitaet
- [ ] Rechtsklick oder "Nachbarn laden"-Button erweitert den Graphen um benachbarte Knoten
- [ ] Bereits geladene Knoten und Kanten werden beim Nachladen nicht dupliziert
- [ ] Filter nach Named Graph, Praedikat und Entitaetstyp schraenkt die Anzeige ein
- [ ] Entity-Suche mit Autovervollstaendigung findet Entitaeten und zentriert den Graphen
- [ ] Layout-Parameter (Abstossung, Kantenlaenge) sind ueber Slider konfigurierbar
- [ ] Seite `/graph/[entityId]` laedt den Graphen zentriert auf die angegebene Entitaet
- [ ] Performance bleibt bei bis zu 500 Knoten und 1000 Kanten akzeptabel
- [ ] Bestehende Funktionalitaet bleibt unberuehrt
