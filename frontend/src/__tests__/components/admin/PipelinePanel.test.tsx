// frontend/src/__tests__/components/admin/PipelinePanel.test.tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import {
  ADMIN_COLLECTIONS_QUERY,
  PIPELINE_DOCUMENTS_QUERY,
} from "@/graphql/admin";
import { PipelinePanel } from "@/components/admin/PipelinePanel";

const mocks = [
  {
    request: { query: ADMIN_COLLECTIONS_QUERY },
    result: {
      data: {
        collections: [
          {
            id: "col-1",
            name: "Annual Reports",
            description: null,
            tags: [],
            metadata: [],
            createdAt: "2026-01-01T00:00:00Z",
            updatedAt: "2026-01-01T00:00:00Z",
          },
        ],
      },
    },
  },
  {
    request: {
      query: PIPELINE_DOCUMENTS_QUERY,
      variables: {
        collectionId: "col-1",
        processingFilter: { state: "PROCESSING" },
        failedFilter: { state: "FAILED" },
      },
    },
    result: {
      data: {
        processing: {
          items: [
            {
              id: "doc-1",
              collectionId: "col-1",
              title: "Report A",
              state: "PROCESSING",
              createdAt: "2026-04-01T00:00:00Z",
            },
          ],
          totalCount: 1,
        },
        failed: {
          items: [
            {
              id: "doc-2",
              collectionId: "col-1",
              title: "Report B",
              state: "FAILED",
              createdAt: "2026-04-02T00:00:00Z",
            },
          ],
          totalCount: 1,
        },
      },
    },
  },
];

describe("PipelinePanel", () => {
  it("renders processing and failed lists for a given collection", async () => {
    render(
      <MockedProvider mocks={mocks} addTypename={false}>
        <PipelinePanel initialCollectionId="col-1" />
      </MockedProvider>,
    );

    expect(await screen.findByText("Report A")).toBeInTheDocument();
    expect(screen.getByText("Report B")).toBeInTheDocument();
    expect(screen.getByText(/In Verarbeitung/)).toBeInTheDocument();
    expect(screen.getByText(/Fehlgeschlagen/)).toBeInTheDocument();
  });
});
