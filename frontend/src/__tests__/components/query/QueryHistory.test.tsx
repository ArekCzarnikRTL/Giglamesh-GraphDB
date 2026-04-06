import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryHistory } from "@/components/query/QueryHistory";
import { saveToHistory } from "@/lib/query-history";

describe("QueryHistory", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("lists entries from LocalStorage", () => {
    saveToHistory({ query: "first?", mode: "auto", collectionId: "c" });
    saveToHistory({ query: "second?", mode: "graph-rag", collectionId: "c" });
    render(<QueryHistory onSelect={() => {}} />);
    expect(screen.getByText("second?")).toBeInTheDocument();
    expect(screen.getByText("first?")).toBeInTheDocument();
  });

  it("calls onSelect with the entry when clicked", async () => {
    saveToHistory({ query: "pick me", mode: "graph-rag", collectionId: "col" });
    const onSelect = vi.fn();
    render(<QueryHistory onSelect={onSelect} />);
    await userEvent.click(screen.getByText("pick me"));
    expect(onSelect).toHaveBeenCalledWith(
      expect.objectContaining({ query: "pick me", mode: "graph-rag" }),
    );
  });

  it("clears entries", async () => {
    saveToHistory({ query: "x", mode: "auto", collectionId: "c" });
    render(<QueryHistory onSelect={() => {}} />);
    await userEvent.click(screen.getByRole("button", { name: /Leeren/ }));
    expect(screen.queryByText("x")).not.toBeInTheDocument();
  });

  it("renders empty state", () => {
    render(<QueryHistory onSelect={() => {}} />);
    expect(screen.getByText(/Kein Verlauf/)).toBeInTheDocument();
  });
});
