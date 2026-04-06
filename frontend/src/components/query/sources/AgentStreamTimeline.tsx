"use client";

import { Badge } from "@/components/ui/badge";
import { AgentStreamToken } from "@/types/query";

interface Props {
  tokens: AgentStreamToken[];
}

const phaseStyle: Record<AgentStreamToken["type"], string> = {
  TEXT: "bg-gray-100 text-gray-700",
  THOUGHT: "bg-blue-100 text-blue-800",
  ACTION: "bg-amber-100 text-amber-800",
  OBSERVATION: "bg-violet-100 text-violet-800",
  ANSWER: "bg-emerald-100 text-emerald-800",
  ERROR: "bg-red-100 text-red-800",
};

export function AgentStreamTimeline({ tokens }: Props) {
  if (tokens.length === 0) {
    return <p className="text-sm text-muted-foreground">Warte auf Stream…</p>;
  }
  return (
    <div className="space-y-2">
      {tokens.map((t, i) => (
        <div
          key={i}
          className={`rounded-md p-2 text-sm ${phaseStyle[t.type] ?? ""}`}
        >
          <Badge variant="outline" className="mb-1">
            {t.type}
          </Badge>
          <pre className="whitespace-pre-wrap font-sans text-sm">{t.content}</pre>
        </div>
      ))}
    </div>
  );
}
