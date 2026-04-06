import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ModeSelector } from "@/components/query/ModeSelector";

describe("ModeSelector", () => {
  it("renders four mode buttons and marks the selected one", () => {
    render(<ModeSelector value="graph-rag" onChange={() => {}} />);
    expect(screen.getByRole("button", { name: /Auto/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Graph RAG/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Document RAG/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Agent/ })).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /Graph RAG/ }),
    ).toHaveAttribute("aria-pressed", "true");
  });

  it("calls onChange when a mode is clicked", async () => {
    const onChange = vi.fn();
    render(<ModeSelector value="auto" onChange={onChange} />);
    await userEvent.click(screen.getByRole("button", { name: /Document RAG/ }));
    expect(onChange).toHaveBeenCalledWith("document-rag");
  });
});
