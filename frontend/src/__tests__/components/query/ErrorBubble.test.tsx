import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ErrorBubble } from "@/components/query/ErrorBubble";

describe("ErrorBubble", () => {
  it("renders the message and triggers onRetry with the original query", async () => {
    const onRetry = vi.fn();
    render(
      <ErrorBubble
        message="backend down"
        originalQuery="What is X?"
        onRetry={onRetry}
      />,
    );
    expect(screen.getByText(/backend down/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Erneut/ }));
    expect(onRetry).toHaveBeenCalledWith("What is X?");
  });
});
