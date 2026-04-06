import { describe, it, expect } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import { NLP_QUERY } from "@/graphql/query";
import { useNlpQuery } from "@/hooks/useNlpQuery";
import { ReactNode } from "react";

const mock = {
  request: {
    query: NLP_QUERY,
    variables: { input: { question: "Hi?", collectionId: "col-1" } },
  },
  result: {
    data: {
      nlpQuery: {
        answer: "Hello.",
        detectedIntent: {
          intent: "GRAPH_QUERY",
          confidence: 0.8,
          reasoning: "looks graphy",
        },
        wasReformulated: false,
        effectiveQuestion: "Hi?",
        durationMs: 5,
        sources: ["src1", "src2"],
      },
    },
  },
};

const wrapper = ({ children }: { children: ReactNode }) => (
  <MockedProvider mocks={[mock]} addTypename={false}>
    {children}
  </MockedProvider>
);

describe("useNlpQuery", () => {
  it("returns data on success", async () => {
    const { result } = renderHook(() => useNlpQuery(), { wrapper });
    await act(async () => {
      await result.current.execute("Hi?", "col-1");
    });
    await waitFor(() => expect(result.current.data?.answer).toBe("Hello."));
    expect(result.current.data?.detectedIntent.intent).toBe("GRAPH_QUERY");
    expect(result.current.data?.sources).toEqual(["src1", "src2"]);
  });
});
