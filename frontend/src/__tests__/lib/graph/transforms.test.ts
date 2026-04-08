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
