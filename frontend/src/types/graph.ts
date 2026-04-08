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
 * in SigmaContainer's edgeProgramClasses setting (or a built-in like "arrow").
 */
export interface EdgeAttributes {
  predicate: string;
  dataset: string;
  label: string;
  size: number;
  type: "arrow";
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
