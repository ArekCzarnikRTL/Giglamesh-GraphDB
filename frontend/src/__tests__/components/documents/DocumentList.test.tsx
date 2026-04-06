import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import { DOCUMENTS_QUERY } from "@/graphql/queries";
import { DocumentList } from "@/components/documents/DocumentList";

const mocks = [
  {
    request: {
      query: DOCUMENTS_QUERY,
      variables: {
        collectionId: "col-1",
        filter: {},
        page: 0,
        pageSize: 20,
      },
    },
    result: {
      data: {
        documents: {
          items: [
            {
              id: "doc-1",
              collectionId: "col-1",
              parentId: null,
              title: "Annual Report",
              type: "SOURCE",
              state: "EXTRACTED",
              mimeType: "application/pdf",
              createdAt: "2026-01-01T00:00:00Z",
            },
          ],
          totalCount: 1,
          hasNextPage: false,
        },
      },
    },
  },
];

describe("DocumentList", () => {
  it("renders fetched documents", async () => {
    render(
      <MockedProvider mocks={mocks} addTypename={false}>
        <DocumentList collectionId="col-1" />
      </MockedProvider>,
    );

    expect(await screen.findByText("Annual Report")).toBeInTheDocument();
    expect(screen.getByText(/1 Dokumente/)).toBeInTheDocument();
  });
});
