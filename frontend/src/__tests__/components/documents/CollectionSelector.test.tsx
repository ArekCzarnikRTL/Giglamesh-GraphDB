import { describe, it, expect, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MockedProvider } from "@apollo/client/testing/react";
import { COLLECTIONS_QUERY } from "@/graphql/queries";
import { CollectionSelector } from "@/components/documents/CollectionSelector";

const mocks = [
  {
    request: { query: COLLECTIONS_QUERY },
    result: {
      data: {
        collections: [
          { id: "col-1", name: "Reports", description: null },
          { id: "col-2", name: "Memos", description: null },
        ],
      },
    },
  },
];

describe("CollectionSelector", () => {
  beforeEach(() => window.localStorage.clear());

  it("renders fetched collections after loading", async () => {
    const user = userEvent.setup();
    render(
      <MockedProvider mocks={mocks} addTypename={false}>
        <CollectionSelector />
      </MockedProvider>
    );

    const trigger = await screen.findByRole("combobox");
    await user.click(trigger);

    expect(await screen.findByRole("option", { name: "Reports" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Memos" })).toBeInTheDocument();
  });

  it("persists selection to localStorage", async () => {
    const user = userEvent.setup();
    render(
      <MockedProvider mocks={mocks} addTypename={false}>
        <CollectionSelector />
      </MockedProvider>
    );

    const trigger = await screen.findByRole("combobox");
    await user.click(trigger);
    await user.click(await screen.findByRole("option", { name: "Reports" }));

    expect(window.localStorage.getItem("graphmesh.activeCollectionId")).toBe("col-1");
  });
});
