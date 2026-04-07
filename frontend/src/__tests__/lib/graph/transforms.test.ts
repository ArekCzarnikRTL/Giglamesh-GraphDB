import { describe, it, expect } from "vitest";
import {
  extractLabel,
  inferSubjectType,
  quadsToGraphData,
  mergeGraphData,
  quadToEdgeId,
} from "@/lib/graph/transforms";
import { QuadDto } from "@/types/graph";

const q = (overrides: Partial<QuadDto>): QuadDto => ({
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

describe("quadsToGraphData", () => {
  it("creates one node per distinct subject and object", () => {
    const data = quadsToGraphData([
      q({ subject: "a", object: "b" }),
      q({ subject: "a", object: "c", predicate: "p2" }),
    ]);
    expect(data.nodes.map((n) => n.id).sort()).toEqual(["a", "b", "c"]);
    expect(data.links).toHaveLength(2);
  });

  it("marks subjects with isSubject=true", () => {
    const data = quadsToGraphData([q({ subject: "a", object: "b" })]);
    expect(data.nodes.find((n) => n.id === "a")?.isSubject).toBe(true);
    expect(data.nodes.find((n) => n.id === "b")?.isSubject).toBe(false);
  });

  it("uses objectType for object node type", () => {
    const data = quadsToGraphData([
      q({ subject: "a", object: "lit", objectType: "LITERAL" }),
    ]);
    expect(data.nodes.find((n) => n.id === "lit")?.type).toBe("LITERAL");
  });
});

describe("quadToEdgeId", () => {
  it("includes all four components", () => {
    expect(quadToEdgeId(q({ subject: "a", predicate: "p", object: "b", dataset: "d" })))
      .toBe("a|p|b|d");
  });
});

describe("mergeGraphData", () => {
  it("does not duplicate nodes or links", () => {
    const a = quadsToGraphData([q({ subject: "a", object: "b" })]);
    const b = quadsToGraphData([q({ subject: "a", object: "b" })]);
    const merged = mergeGraphData(a, b);
    expect(merged.nodes).toHaveLength(2);
    expect(merged.links).toHaveLength(1);
  });

  it("marks expanded node when expandedNodeId given", () => {
    const a = quadsToGraphData([q({ subject: "a", object: "b" })]);
    const merged = mergeGraphData(a, { nodes: [], links: [] }, "a");
    expect(merged.nodes.find((n) => n.id === "a")?.expanded).toBe(true);
  });
});
