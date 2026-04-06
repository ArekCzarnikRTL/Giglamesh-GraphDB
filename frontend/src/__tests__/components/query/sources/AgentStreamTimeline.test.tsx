import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { AgentStreamTimeline } from "@/components/query/sources/AgentStreamTimeline";
import { AgentStreamToken } from "@/types/query";

const tokens: AgentStreamToken[] = [
  { content: "Lass mich nachdenken", type: "THOUGHT", endOfMessage: true, endOfStream: false },
  { content: "search(x)", type: "ACTION", endOfMessage: true, endOfStream: false },
  { content: "result", type: "OBSERVATION", endOfMessage: true, endOfStream: false },
  { content: "Final answer.", type: "ANSWER", endOfMessage: true, endOfStream: true },
];

describe("AgentStreamTimeline", () => {
  it("renders tokens with phase labels", () => {
    render(<AgentStreamTimeline tokens={tokens} />);
    expect(screen.getByText("THOUGHT")).toBeInTheDocument();
    expect(screen.getByText("ACTION")).toBeInTheDocument();
    expect(screen.getByText("OBSERVATION")).toBeInTheDocument();
    expect(screen.getByText("ANSWER")).toBeInTheDocument();
    expect(screen.getByText("Lass mich nachdenken")).toBeInTheDocument();
    expect(screen.getByText("Final answer.")).toBeInTheDocument();
  });
});
