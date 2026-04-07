import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { GraphFilter } from "@/components/graph/GraphFilter";

describe("GraphFilter", () => {
  it("renders dataset, predicate and entityType selects", () => {
    render(
      <GraphFilter
        filter={{ datasets: [], predicates: [], entityTypes: [] }}
        availableDatasets={["d1", "d2"]}
        availablePredicates={["p1"]}
        availableTypes={["t1"]}
        onChange={vi.fn()}
      />
    );
    expect(screen.getByLabelText(/Dataset/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Prädikat/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Typ/i)).toBeInTheDocument();
  });

  it("calls onChange when dataset selected", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(
      <GraphFilter
        filter={{ datasets: [], predicates: [], entityTypes: [] }}
        availableDatasets={["d1", "d2"]}
        availablePredicates={[]}
        availableTypes={[]}
        onChange={onChange}
      />
    );
    await user.selectOptions(screen.getByLabelText(/Dataset/i), "d1");
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ datasets: ["d1"] })
    );
  });
});
