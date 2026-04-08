import Graph from "graphology";
import { EdgeAttributes, NodeAttributes } from "@/types/graph";

const FADED_COLOR = "#1F2937";
const HIGHLIGHT_EDGE_COLOR = "#4F46E5";
const HIGHLIGHT_NODE_COLOR = "#818CF8";

export interface NodeDisplay {
  label?: string;
  color?: string;
  highlighted?: boolean;
  forceLabel?: boolean;
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
    if (node === activeNode) {
      // Active node: boosted color + forceLabel, but NOT highlighted:true
      // (sigma's built-in hover drawing paints a white rect behind highlighted
      // labels, which is unreadable on a dark theme).
      return {
        ...data,
        color: HIGHLIGHT_NODE_COLOR,
        forceLabel: true,
        size: (data.size ?? 6) * 1.4,
      };
    }
    if (neighbors.has(node)) {
      return { ...data, forceLabel: true };
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
