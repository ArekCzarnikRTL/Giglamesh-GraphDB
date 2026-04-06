import { describe, it, expect } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import { GRAPH_RAG_QUERY } from "@/graphql/query";
import { useGraphRag } from "@/hooks/useGraphRag";
import { ReactNode } from "react";

const successMock = {
  request: {
    query: GRAPH_RAG_QUERY,
    variables: { input: { question: "Q?", collectionId: "col-1" } },
  },
  result: {
    data: {
      graphRag: {
        sessionId: "s1",
        answer: "A.",
        selectedEdges: [],
        retrievedEdgeCount: 0,
        durationMs: 12,
      },
    },
  },
};

const errorMock = {
  request: {
    query: GRAPH_RAG_QUERY,
    variables: { input: { question: "boom", collectionId: "col-1" } },
  },
  error: new Error("backend down"),
};

function wrapper(mocks: Parameters<typeof MockedProvider>[0]["mocks"]) {
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <MockedProvider mocks={mocks} addTypename={false}>
      {children}
    </MockedProvider>
  );
  Wrapper.displayName = "MockWrapper";
  return Wrapper;
}

describe("useGraphRag", () => {
  it("returns data on success", async () => {
    const { result } = renderHook(() => useGraphRag(), {
      wrapper: wrapper([successMock]),
    });
    await act(async () => {
      await result.current.execute("Q?", "col-1");
    });
    await waitFor(() => expect(result.current.data?.answer).toBe("A."));
    expect(result.current.error).toBeUndefined();
  });

  it("exposes errors", async () => {
    const { result } = renderHook(() => useGraphRag(), {
      wrapper: wrapper([errorMock]),
    });
    await act(async () => {
      await result.current.execute("boom", "col-1");
    });
    await waitFor(() => expect(result.current.error).toBeDefined());
    expect(result.current.data).toBeUndefined();
  });
});
