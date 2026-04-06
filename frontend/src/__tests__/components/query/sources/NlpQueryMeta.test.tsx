import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { NlpQueryMeta } from "@/components/query/sources/NlpQueryMeta";
import { NlpQueryPayload } from "@/types/query";

const payload: NlpQueryPayload = {
  answer: "...",
  detectedIntent: {
    intent: "GRAPH_QUERY",
    confidence: 0.88,
    reasoning: "Mentions entities",
  },
  wasReformulated: true,
  effectiveQuestion: "Reformulated question",
  durationMs: 9,
  sources: ["src-a", "src-b"],
};

describe("NlpQueryMeta", () => {
  it("renders intent, reformulation note and string sources", () => {
    render(<NlpQueryMeta payload={payload} />);
    expect(screen.getByText("GRAPH_QUERY")).toBeInTheDocument();
    expect(screen.getByText("88%")).toBeInTheDocument();
    expect(screen.getByText(/Mentions entities/)).toBeInTheDocument();
    expect(screen.getByText(/Reformuliert/)).toBeInTheDocument();
    expect(screen.getByText("Reformulated question")).toBeInTheDocument();
    expect(screen.getByText("src-a")).toBeInTheDocument();
    expect(screen.getByText("src-b")).toBeInTheDocument();
  });
});
