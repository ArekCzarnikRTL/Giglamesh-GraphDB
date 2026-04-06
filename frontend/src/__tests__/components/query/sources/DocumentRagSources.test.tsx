import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { DocumentRagSources } from "@/components/query/sources/DocumentRagSources";
import { DocumentRagSource } from "@/types/query";

const sources: DocumentRagSource[] = [
  {
    chunkId: "c1",
    documentId: "doc-42",
    documentTitle: "Annual Report",
    pageNumber: 7,
    score: 0.81,
    snippet: "Revenue grew 12 percent.",
  },
];

describe("DocumentRagSources", () => {
  it("renders title link, page badge, snippet and score", () => {
    render(<DocumentRagSources sources={sources} />);
    const link = screen.getByRole("link", { name: "Annual Report" });
    expect(link).toHaveAttribute("href", "/documents/doc-42");
    expect(screen.getByText("Seite 7")).toBeInTheDocument();
    expect(screen.getByText("Revenue grew 12 percent.")).toBeInTheDocument();
    expect(screen.getByText("81%")).toBeInTheDocument();
  });

  it("renders empty state", () => {
    render(<DocumentRagSources sources={[]} />);
    expect(screen.getByText(/Keine Quellen/)).toBeInTheDocument();
  });
});
