// frontend/src/__tests__/components/admin/AdminDashboard.test.tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import {
  ADMIN_COLLECTIONS_QUERY,
  ADMIN_COLLECTION_COUNTS_QUERY,
} from "@/graphql/admin";
import { AdminDashboard } from "@/components/admin/AdminDashboard";

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
            tags: ["finance"],
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
      query: ADMIN_COLLECTION_COUNTS_QUERY,
      variables: {
        collectionId: "col-1",
        processingFilter: { state: "PROCESSING" },
        failedFilter: { state: "FAILED" },
      },
    },
    result: {
      data: {
        processing: { totalCount: 3 },
        failed: { totalCount: 1 },
      },
    },
  },
];

describe("AdminDashboard", () => {
  it("renders collection stats", async () => {
    render(
      <MockedProvider mocks={mocks} addTypename={false}>
        <AdminDashboard />
      </MockedProvider>,
    );

    // Collection row appears
    expect(await screen.findByText("Annual Reports")).toBeInTheDocument();
    // The metric tile labels are present (appear in both MetricCard and TableHeader)
    expect(screen.getAllByText("In Verarbeitung").length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText("Fehlgeschlagen").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("Mit Backlog")).toBeInTheDocument();
    // Tag from the collection renders (proves the row, not just the metric, mounted)
    expect(screen.getByText("finance")).toBeInTheDocument();
  });
});
