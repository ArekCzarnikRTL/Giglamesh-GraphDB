import { GraphData, GraphEdge, GraphNode, QuadDto, RdfTermType } from "@/types/graph";

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

export function quadToEdgeId(quad: QuadDto): string {
  return `${quad.subject}|${quad.predicate}|${quad.object}|${quad.dataset}`;
}

export function quadsToGraphData(quads: QuadDto[]): GraphData {
  const nodes = new Map<string, GraphNode>();
  const links: GraphEdge[] = [];

  for (const quad of quads) {
    if (!nodes.has(quad.subject)) {
      nodes.set(quad.subject, {
        id: quad.subject,
        label: extractLabel(quad.subject),
        type: inferSubjectType(quad.subject),
        isSubject: true,
        expanded: false,
        size: 6,
      });
    } else {
      const existing = nodes.get(quad.subject)!;
      existing.isSubject = true;
    }

    if (!nodes.has(quad.object)) {
      const objectType = (quad.objectType as RdfTermType) ?? "URI";
      nodes.set(quad.object, {
        id: quad.object,
        label: extractLabel(quad.object),
        type: objectType,
        isSubject: false,
        expanded: false,
        size: objectType === "LITERAL" ? 4 : 6,
      });
    }

    links.push({
      id: quadToEdgeId(quad),
      source: quad.subject,
      target: quad.object,
      predicate: quad.predicate,
      dataset: quad.dataset,
      label: extractLabel(quad.predicate),
    });
  }

  return { nodes: Array.from(nodes.values()), links };
}

export function mergeGraphData(
  existing: GraphData,
  incoming: GraphData,
  expandedNodeId?: string
): GraphData {
  const nodeMap = new Map(existing.nodes.map((n) => [n.id, n]));
  for (const node of incoming.nodes) {
    if (!nodeMap.has(node.id)) {
      nodeMap.set(node.id, { ...node });
    } else if (node.isSubject) {
      nodeMap.get(node.id)!.isSubject = true;
    }
  }
  if (expandedNodeId) {
    const target = nodeMap.get(expandedNodeId);
    if (target) target.expanded = true;
  }

  const linkIds = new Set(existing.links.map((l) => l.id));
  const merged: GraphEdge[] = [...existing.links];
  for (const link of incoming.links) {
    if (!linkIds.has(link.id)) {
      merged.push(link);
      linkIds.add(link.id);
    }
  }

  return { nodes: Array.from(nodeMap.values()), links: merged };
}
