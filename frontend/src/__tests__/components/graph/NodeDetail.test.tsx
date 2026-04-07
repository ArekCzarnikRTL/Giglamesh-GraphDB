import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MockedProvider } from "@apollo/client/testing/react";
import { GRAPH_TRIPLES_QUERY } from "@/graphql/graph";
import { NodeDetail } from "@/components/graph/NodeDetail";
import { GraphNode } from "@/types/graph";

const node: GraphNode = {
  id: "http://ex.org/a",
  label: "a",
  type: "URI",
  isSubject: true,
  expanded: false,
  size: 6,
};

const triplesMock = {
  request: {
    query: GRAPH_TRIPLES_QUERY,
    variables: {
      collectionId: "c1",
      subject: "http://ex.org/a",
      predicate: null,
      object: null,
      dataset: null,
      limit: 50,
    },
  },
  result: {
    data: {
      triples: [
        {
          subject: "http://ex.org/a",
          predicate: "http://ex.org/p",
          object: "value1",
          dataset: "default",
          objectType: "LITERAL",
          datatype: null,
          language: null,
        },
      ],
    },
  },
};

describe("NodeDetail", () => {
  it("loads and renders triples for the node", async () => {
    render(
      <MockedProvider mocks={[triplesMock]} addTypename={false}>
        <NodeDetail node={node} collectionId="c1" onExpand={vi.fn()} onClose={vi.fn()} />
      </MockedProvider>
    );
    await waitFor(() => {
      expect(screen.getByText(/value1/)).toBeInTheDocument();
    });
  });

  it("calls onExpand when 'Nachbarn laden' clicked", async () => {
    const onExpand = vi.fn();
    const user = userEvent.setup();
    render(
      <MockedProvider mocks={[triplesMock]} addTypename={false}>
        <NodeDetail node={node} collectionId="c1" onExpand={onExpand} onClose={vi.fn()} />
      </MockedProvider>
    );
    const btn = await screen.findByRole("button", { name: /Nachbarn laden/i });
    await user.click(btn);
    expect(onExpand).toHaveBeenCalledWith("http://ex.org/a");
  });
});
