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
    // Active node gets a boosted color, not `highlighted: true` (which would
    // trigger sigma's white-rectangle hover label background).
    expect(reducer("a", { size: 6 }).forceLabel).toBe(true);
    expect(reducer("a", { size: 6 }).color).toBeDefined();
    // Neighbors get forceLabel so their labels stay visible.
    expect(reducer("b", {}).forceLabel).toBe(true);
    expect(reducer("c", {}).forceLabel).toBe(true);
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
