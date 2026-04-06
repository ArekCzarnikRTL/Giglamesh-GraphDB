import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import {
  DOCUMENT_QUERY,
  DOCUMENT_CHUNKS_QUERY,
  DOCUMENT_TRIPLES_QUERY,
} from "@/graphql/queries";
import { DocumentDetail } from "@/components/documents/DocumentDetail";

const mocks = [
  {
    request: { query: DOCUMENT_QUERY, variables: { id: "doc-1" } },
    result: {
      data: {
        document: {
          id: "doc-1",
          collectionId: "col-1",
          parentId: null,
          title: "My Doc",
          type: "SOURCE",
          state: "EXTRACTED",
          mimeType: "application/pdf",
          createdAt: "2026-01-01T00:00:00Z",
          metadata: [{ key: "author", value: "Alice" }],
          children: [],
        },
      },
    },
  },
  {
    request: { query: DOCUMENT_CHUNKS_QUERY, variables: { documentId: "doc-1" } },
    result: { data: { documentChunks: [] } },
  },
  {
    request: {
      query: DOCUMENT_TRIPLES_QUERY,
      variables: { collectionId: "col-1", subject: "doc-1" },
    },
    result: { data: { triples: [] } },
  },
];

describe("DocumentDetail", () => {
  it("renders document title and metadata", async () => {
    render(
      <MockedProvider mocks={mocks} addTypename={false}>
        <DocumentDetail documentId="doc-1" />
      </MockedProvider>
    );

    expect(
      await screen.findByRole("heading", { name: "My Doc", level: 2 })
    ).toBeInTheDocument();
    expect(screen.getByText("author")).toBeInTheDocument();
    expect(screen.getByText("Alice")).toBeInTheDocument();
  });
});
