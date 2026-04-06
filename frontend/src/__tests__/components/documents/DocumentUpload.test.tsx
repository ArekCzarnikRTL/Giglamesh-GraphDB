import { describe, it, expect } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MockedProvider } from "@apollo/client/testing/react";
import { UPLOAD_DOCUMENT } from "@/graphql/mutations";
import { DocumentUpload } from "@/components/documents/DocumentUpload";

const file = new File(["hello"], "test.pdf", { type: "application/pdf" });

const mocks = [
  {
    request: {
      query: UPLOAD_DOCUMENT,
      variables: {
        input: {
          collectionId: "col-1",
          title: "test.pdf",
          mimeType: "application/pdf",
          content: btoa("hello"),
          metadata: null,
        },
      },
    },
    result: {
      data: {
        uploadDocument: {
          id: "doc-new",
          collectionId: "col-1",
          title: "test.pdf",
          mimeType: "application/pdf",
          state: "UPLOADED",
          type: "SOURCE",
        },
      },
    },
  },
];

describe("DocumentUpload", () => {
  it("fires upload mutation and shows progress bar after dropping a file", async () => {
    const user = userEvent.setup();

    render(
      <MockedProvider mocks={mocks} addTypename={false}>
        <DocumentUpload collectionId="col-1" />
      </MockedProvider>,
    );

    const input = screen.getByLabelText(/datei wählen/i) as HTMLInputElement;
    await user.upload(input, file);

    await waitFor(() =>
      expect(screen.getByRole("progressbar")).toBeInTheDocument(),
    );
  });
});
