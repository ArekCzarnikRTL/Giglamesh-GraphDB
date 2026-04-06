import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { GraphRagSources } from "@/components/query/sources/GraphRagSources";
import { SelectedEdge } from "@/types/query";

const edges: SelectedEdge[] = [
  {
    subject: "Photosynthesis",
    predicate: "produces",
    objectValue: "Oxygen",
    dataset: "biology",
    reasoning: "Direct mention in chunk 5",
    relevanceScore: 0.92,
  },
];

describe("GraphRagSources", () => {
  it("renders the triple, dataset, reasoning, and score", () => {
    render(<GraphRagSources edges={edges} />);
    expect(screen.getByText("Photosynthesis")).toBeInTheDocument();
    expect(screen.getByText("produces")).toBeInTheDocument();
    expect(screen.getByText("Oxygen")).toBeInTheDocument();
    expect(screen.getByText("biology")).toBeInTheDocument();
    expect(screen.getByText(/Direct mention/)).toBeInTheDocument();
    expect(screen.getByText("92%")).toBeInTheDocument();
  });

  it("renders an empty state when no edges are present", () => {
    render(<GraphRagSources edges={[]} />);
    expect(screen.getByText(/Keine Quellen/)).toBeInTheDocument();
  });
});
