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
