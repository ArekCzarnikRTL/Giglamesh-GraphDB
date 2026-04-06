"use client";

import { Button } from "@/components/ui/button";
import { AssistantMessage } from "@/types/query";
import { GraphRagSources } from "./sources/GraphRagSources";
import { DocumentRagSources } from "./sources/DocumentRagSources";
import { NlpQueryMeta } from "./sources/NlpQueryMeta";
import { AgentStreamTimeline } from "./sources/AgentStreamTimeline";

interface Props {
  message: AssistantMessage;
  onClose: () => void;
}

export function SourcePanel({ message, onClose }: Props) {
  return (
    <aside
      className="w-96 shrink-0 overflow-y-auto border-l bg-background p-4"
      aria-label="Quellen"
    >
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold">Quellen</h2>
        <Button type="button" size="sm" variant="ghost" onClick={onClose}>
          Schliessen
        </Button>
      </div>
      {message.mode === "graph-rag" && message.graphRag && (
        <GraphRagSources edges={message.graphRag.selectedEdges} />
      )}
      {message.mode === "document-rag" && message.documentRag && (
        <DocumentRagSources sources={message.documentRag.sources} />
      )}
      {message.mode === "auto" && message.nlpQuery && (
        <NlpQueryMeta payload={message.nlpQuery} />
      )}
      {message.mode === "agent-stream" && message.agentStream && (
        <AgentStreamTimeline tokens={message.agentStream.tokens} />
      )}
    </aside>
  );
}
