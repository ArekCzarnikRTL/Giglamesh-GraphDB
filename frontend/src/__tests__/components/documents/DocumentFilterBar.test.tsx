import { useState } from "react";
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DocumentFilterBar } from "@/components/documents/DocumentFilterBar";
import type { DocumentFilter } from "@/types/document";

function Harness({
  initial,
  onChangeSpy,
}: {
  initial: DocumentFilter;
  onChangeSpy: (filter: DocumentFilter) => void;
}) {
  const [filter, setFilter] = useState<DocumentFilter>(initial);
  return (
    <DocumentFilterBar
      value={filter}
      onChange={(next) => {
        setFilter(next);
        onChangeSpy(next);
      }}
    />
  );
}

describe("DocumentFilterBar", () => {
  it("emits onChange when search input changes", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<Harness initial={{}} onChangeSpy={onChange} />);

    const input = screen.getByPlaceholderText(/suche/i);
    await user.type(input, "memo");

    expect(onChange).toHaveBeenLastCalledWith({ search: "memo" });
  });
});
