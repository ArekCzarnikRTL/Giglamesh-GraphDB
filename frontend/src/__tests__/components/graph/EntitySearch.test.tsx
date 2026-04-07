import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MockedProvider } from "@apollo/client/testing/react";
import { ENTITY_SEARCH_QUERY } from "@/graphql/graph";
import { EntitySearch } from "@/components/graph/EntitySearch";

const searchMock = {
  request: {
    query: ENTITY_SEARCH_QUERY,
    variables: { collectionId: "c1", prefix: "ali", limit: 20 },
  },
  result: {
    data: {
      entitySearch: ["http://ex.org/Alice", "http://ex.org/Aligator"],
    },
  },
};

describe("EntitySearch", () => {
  it("shows suggestions after typing and triggers onSelect on click", async () => {
    const onSelect = vi.fn();
    const user = userEvent.setup();

    render(
      <MockedProvider mocks={[searchMock]} addTypename={false}>
        <EntitySearch collectionId="c1" onSelect={onSelect} />
      </MockedProvider>
    );

    const input = screen.getByPlaceholderText(/Entity suchen/i);
    await user.type(input, "ali");

    const option = await screen.findByText("http://ex.org/Alice");
    await user.click(option);

    await waitFor(() => {
      expect(onSelect).toHaveBeenCalledWith("http://ex.org/Alice");
    });
  });
});
