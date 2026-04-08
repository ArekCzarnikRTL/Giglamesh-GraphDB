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
        type: "arrow",
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
