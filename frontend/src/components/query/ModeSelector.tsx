"use client";

import { Button } from "@/components/ui/button";
import { QueryMode } from "@/types/query";

interface Props {
  value: QueryMode;
  onChange: (mode: QueryMode) => void;
}

const MODES: { value: QueryMode; label: string; title: string }[] = [
  { value: "auto", label: "Auto / NLP", title: "Automatische Moduswahl" },
  { value: "graph-rag", label: "Graph RAG", title: "Antworten aus dem Knowledge Graph" },
  { value: "document-rag", label: "Document RAG", title: "Antworten aus Dokumenten" },
  { value: "agent-stream", label: "Agent (Streaming)", title: "ReAct-Agent mit Token-Streaming" },
];

export function ModeSelector({ value, onChange }: Props) {
  return (
    <div className="flex flex-wrap gap-2">
      {MODES.map((m) => (
        <Button
          key={m.value}
          type="button"
          size="sm"
          variant={m.value === value ? "default" : "outline"}
          aria-pressed={m.value === value}
          title={m.title}
          onClick={() => onChange(m.value)}
        >
          {m.label}
        </Button>
      ))}
    </div>
  );
}
