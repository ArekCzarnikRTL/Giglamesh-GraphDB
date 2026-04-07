import { describe, it, expect, vi } from "vitest";

vi.mock("next/dynamic", () => ({
  default: () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const Mock = (props: any) => <div data-testid="force-graph-mock" data-nodes={props.graphData.nodes.length} />;
    return Mock;
  },
}));

import { render, screen } from "@testing-library/react";
import { GraphCanvas } from "@/components/graph/GraphCanvas";
import { GraphData } from "@/types/graph";

const data: GraphData = {
  nodes: [
    { id: "a", label: "a", type: "URI", isSubject: true, expanded: false, size: 6 },
    { id: "b", label: "b", type: "URI", isSubject: false, expanded: false, size: 6 },
  ],
  links: [
    { id: "a|p|b|d", source: "a", target: "b", predicate: "p", dataset: "d", label: "p" },
  ],
};

describe("GraphCanvas", () => {
  it("forwards graphData to ForceGraph2D", async () => {
    render(
      <GraphCanvas
        data={data}
        layoutConfig={{ chargeStrength: -150, linkDistance: 80, centerStrength: 0.05, collisionRadius: 20 }}
        selectedNodeId={null}
        onNodeClick={vi.fn()}
        onNodeRightClick={vi.fn()}
      />
    );
    const mock = await screen.findByTestId("force-graph-mock");
    expect(mock.getAttribute("data-nodes")).toBe("2");
  });
});
