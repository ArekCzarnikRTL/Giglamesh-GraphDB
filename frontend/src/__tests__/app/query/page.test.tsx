import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import QueryPage from "@/app/query/page";
import { COLLECTIONS_QUERY } from "@/graphql/queries";

const collectionsMock = {
  request: { query: COLLECTIONS_QUERY },
  result: {
    data: {
      collections: [{ id: "col-1", name: "Default", description: null }],
    },
  },
};

describe("QueryPage", () => {
  it("shows empty state when no collection is selected", async () => {
    render(
      <MockedProvider mocks={[collectionsMock]} addTypename={false}>
        <QueryPage />
      </MockedProvider>,
    );
    expect(
      await screen.findByText(/Bitte eine Collection auswaehlen/),
    ).toBeInTheDocument();
  });
});
