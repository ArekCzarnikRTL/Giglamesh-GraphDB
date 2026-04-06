"use client";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { AgentStreamTimeline } from "./sources/AgentStreamTimeline";
import { ErrorBubble } from "./ErrorBubble";
import { AssistantMessage, QueryMessage as QM } from "@/types/query";

interface Props {
  message: QM;
  onShowSources: (msg: AssistantMessage) => void;
  onRetry: (originalQuery: string) => void;
}

export function QueryMessage({ message, onShowSources, onRetry }: Props) {
  if (message.role === "user") {
    return (
      <div className="flex justify-end">
        <div className="max-w-2xl rounded-lg bg-primary px-3 py-2 text-sm text-primary-foreground">
          {message.content}
        </div>
      </div>
    );
  }

  if (message.status === "error" && message.error) {
    return (
      <div className="flex justify-start">
        <ErrorBubble
          message={message.error.message}
          originalQuery={message.error.originalQuery}
          onRetry={onRetry}
        />
      </div>
    );
  }

  if (message.status === "pending") {
    return (
      <div className="flex justify-start">
        <Skeleton className="h-12 w-2/3" />
      </div>
    );
  }

  const answer =
    message.graphRag?.answer ??
    message.documentRag?.answer ??
    message.nlpQuery?.answer ??
    message.agentStream?.finalAnswer ??
    "";

  return (
    <div className="flex justify-start">
      <div className="max-w-3xl space-y-2 rounded-lg border bg-muted/30 p-3">
        {message.status === "streaming" && message.agentStream ? (
          <AgentStreamTimeline tokens={message.agentStream.tokens} />
        ) : (
          <p className="whitespace-pre-wrap text-sm">{answer}</p>
        )}
        {message.status === "done" && (
          <div className="flex justify-end">
            <Button
              type="button"
              size="sm"
              variant="ghost"
              onClick={() => onShowSources(message)}
            >
              Quellen anzeigen
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}
