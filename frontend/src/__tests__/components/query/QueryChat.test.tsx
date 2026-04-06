import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MockedProvider } from "@apollo/client/testing/react";
import { QueryChat } from "@/components/query/QueryChat";
import { NLP_QUERY } from "@/graphql/query";

const nlpMock = {
  request: {
    query: NLP_QUERY,
    variables: { input: { question: "Hello?", collectionId: "col-1" } },
  },
  result: {
    data: {
      nlpQuery: {
        answer: "World.",
        detectedIntent: {
          intent: "GRAPH_QUERY",
          confidence: 0.9,
          reasoning: "r",
        },
        wasReformulated: false,
        effectiveQuestion: "Hello?",
        durationMs: 1,
        sources: [],
      },
    },
  },
};

describe("QueryChat", () => {
  it("submits a question and renders the assistant answer", async () => {
    render(
      <MockedProvider mocks={[nlpMock]} addTypename={false}>
        <QueryChat collectionId="col-1" />
      </MockedProvider>,
    );

    const input = screen.getByLabelText("Frage");
    await userEvent.type(input, "Hello?");
    await userEvent.click(screen.getByRole("button", { name: /Senden/ }));

    // user bubble
    expect(screen.getAllByText("Hello?").length).toBeGreaterThan(0);
    // assistant answer eventually appears
    expect(await screen.findByText("World.")).toBeInTheDocument();
  });
});
