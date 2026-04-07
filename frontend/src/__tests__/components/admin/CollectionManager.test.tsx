// frontend/src/__tests__/components/admin/CollectionManager.test.tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import { ADMIN_COLLECTIONS_QUERY } from "@/graphql/admin";
import { CollectionManager } from "@/components/admin/CollectionManager";

const mocks = [
  {
    request: { query: ADMIN_COLLECTIONS_QUERY },
    result: {
      data: {
        collections: [
          {
            id: "col-1",
            name: "Annual Reports",
            description: "Quarterly reports",
            tags: ["finance"],
            metadata: [],
            createdAt: "2026-01-01T00:00:00Z",
            updatedAt: "2026-01-02T00:00:00Z",
          },
        ],
      },
    },
  },
];

describe("CollectionManager", () => {
  it("renders the collections table", async () => {
    render(
      <MockedProvider mocks={mocks} addTypename={false}>
        <CollectionManager />
      </MockedProvider>,
    );

    expect(await screen.findByText("Annual Reports")).toBeInTheDocument();
    expect(screen.getByText("Quarterly reports")).toBeInTheDocument();
    expect(screen.getByText("finance")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /neue collection/i })).toBeInTheDocument();
  });
});
