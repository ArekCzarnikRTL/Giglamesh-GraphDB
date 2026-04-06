import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DocumentPagination } from "@/components/documents/DocumentPagination";

describe("DocumentPagination", () => {
  it("renders current page and total pages", () => {
    render(
      <DocumentPagination page={0} pageSize={10} totalCount={25} onPageChange={() => {}} />
    );
    expect(screen.getByText(/Seite 1 von 3/)).toBeInTheDocument();
  });

  it("disables previous on first page", () => {
    render(
      <DocumentPagination page={0} pageSize={10} totalCount={25} onPageChange={() => {}} />
    );
    expect(screen.getByRole("button", { name: /zurück/i })).toBeDisabled();
  });

  it("calls onPageChange with next page", async () => {
    const user = userEvent.setup();
    const onPageChange = vi.fn();
    render(
      <DocumentPagination page={0} pageSize={10} totalCount={25} onPageChange={onPageChange} />
    );
    await user.click(screen.getByRole("button", { name: /weiter/i }));
    expect(onPageChange).toHaveBeenCalledWith(1);
  });
});
