export type RdfTermType = "URI" | "LITERAL" | "BLANK_NODE" | "QUOTED_TRIPLE";

export interface GraphNode {
  id: string;
  label: string;
  type: RdfTermType;
  isSubject: boolean;
  expanded: boolean;
  size: number;
}

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  predicate: string;
  dataset: string;
  label: string;
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
  chargeStrength: number;
  linkDistance: number;
  centerStrength: number;
  collisionRadius: number;
}

/** Wire shape returned by the GraphMesh `triples` GraphQL query. */
export interface QuadDto {
  subject: string;
  predicate: string;
  object: string;
  dataset: string;
  objectType: string; // "URI" | "LITERAL" | "QUOTED_TRIPLE"
  datatype?: string | null;
  language?: string | null;
}
