import { describe, it, expect } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import { AGENT_STREAM_SUBSCRIPTION } from "@/graphql/query";
import { useAgentStream } from "@/hooks/useAgentStream";
import { ReactNode } from "react";

const mock = {
  request: {
    query: AGENT_STREAM_SUBSCRIPTION,
    variables: { input: { question: "Q", collectionId: "col" } },
  },
  result: {
    data: {
      agentStream: {
        content: "Final answer.",
        type: "ANSWER",
        endOfMessage: true,
        endOfStream: true,
      },
    },
  },
};

const wrapper = ({ children }: { children: ReactNode }) => (
  <MockedProvider mocks={[mock]} addTypename={false}>
    {children}
  </MockedProvider>
);

describe("useAgentStream", () => {
  it("collects tokens and finalizes on endOfStream", async () => {
    const { result } = renderHook(() => useAgentStream(), { wrapper });
    act(() => {
      result.current.execute("Q", "col");
    });
    await waitFor(() => expect(result.current.tokens).toHaveLength(1));
    expect(result.current.tokens[0].content).toBe("Final answer.");
    expect(result.current.tokens[0].type).toBe("ANSWER");
    await waitFor(() => expect(result.current.streaming).toBe(false));
  });

  it("reset clears tokens and variables", async () => {
    const { result } = renderHook(() => useAgentStream(), { wrapper });
    act(() => {
      result.current.execute("Q", "col");
    });
    await waitFor(() => expect(result.current.tokens.length).toBeGreaterThan(0));
    act(() => {
      result.current.reset();
    });
    expect(result.current.tokens).toEqual([]);
    expect(result.current.streaming).toBe(false);
  });
});
