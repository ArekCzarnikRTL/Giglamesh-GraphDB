// frontend/src/__tests__/components/admin/ConfigEditor.test.tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import { CONFIG_KEYS_QUERY } from "@/graphql/admin";
import { ConfigEditor } from "@/components/admin/ConfigEditor";

const mocks = [
  {
    request: { query: CONFIG_KEYS_QUERY, variables: { type: null } },
    result: {
      data: {
        configKeys: [
          {
            id: "ONTOLOGY:default",
            type: "ONTOLOGY",
            key: "default",
            value: "{\"foo\": \"bar\"}",
            version: 2,
          },
        ],
      },
    },
  },
];

describe("ConfigEditor", () => {
  it("renders config entries", async () => {
    render(
      <MockedProvider mocks={mocks} addTypename={false}>
        <ConfigEditor />
      </MockedProvider>,
    );

    expect(await screen.findByText("default")).toBeInTheDocument();
    expect(screen.getByText(/Version 2/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "ONTOLOGY" })).toBeInTheDocument();
  });
});
