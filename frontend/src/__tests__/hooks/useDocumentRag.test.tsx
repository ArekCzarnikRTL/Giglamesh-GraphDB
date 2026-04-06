import { describe, it, expect } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import { DOCUMENT_RAG_QUERY } from "@/graphql/query";
import { useDocumentRag } from "@/hooks/useDocumentRag";
import { ReactNode } from "react";

const mock = {
  request: {
    query: DOCUMENT_RAG_QUERY,
    variables: { input: { question: "What?", collectionId: "col-1" } },
  },
  result: {
    data: {
      documentRag: {
        sessionId: "s",
        answer: "Because.",
        sources: [
          {
            chunkId: "ch1",
            documentId: "doc1",
            documentTitle: "Doc",
            pageNumber: 3,
            score: 0.9,
            snippet: "...",
          },
        ],
        retrievedChunkCount: 1,
        durationMs: 7,
      },
    },
  },
};

const wrapper = ({ children }: { children: ReactNode }) => (
  <MockedProvider mocks={[mock]} addTypename={false}>
    {children}
  </MockedProvider>
);

describe("useDocumentRag", () => {
  it("returns data on success", async () => {
    const { result } = renderHook(() => useDocumentRag(), { wrapper });
    await act(async () => {
      await result.current.execute("What?", "col-1");
    });
    await waitFor(() => expect(result.current.data?.answer).toBe("Because."));
    expect(result.current.data?.sources).toHaveLength(1);
  });
});
