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

const wrapper = (mocks: any[]) => {
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <MockedProvider mocks={mocks} addTypename={false}>{children}</MockedProvider>
  );
  return Wrapper;
};

describe("useGraphData", () => {
  it("loadInitial populates graphData", async () => {
    const { result } = renderHook(() => useGraphData("c1"), {
      wrapper: wrapper([initialMock]),
    });

    await act(async () => {
      await result.current.loadInitial({ datasets: [], predicates: [], entityTypes: [] });
    });

    await waitFor(() => {
      expect(result.current.graphData.nodes.length).toBeGreaterThan(0);
    });
    expect(result.current.graphData.nodes.map((n) => n.id).sort()).toEqual(["a", "b", "c"]);
  });

  it("expandNode merges neighbors without duplicates and marks expanded", async () => {
    const { result } = renderHook(() => useGraphData("c1"), {
      wrapper: wrapper([initialMock, neighborMock]),
    });

    await act(async () => {
      await result.current.loadInitial({ datasets: [], predicates: [], entityTypes: [] });
    });
    await act(async () => {
      await result.current.expandNode("b");
    });

    await waitFor(() => {
      expect(result.current.graphData.nodes.find((n) => n.id === "b")?.expanded).toBe(true);
    });
    expect(result.current.graphData.nodes.map((n) => n.id).sort()).toEqual(["a", "b", "c", "d"]);
    const apb = result.current.graphData.links.filter(
      (l) => l.source === "a" && l.predicate === "p" && l.target === "b"
    );
    expect(apb).toHaveLength(1);
  });
});
