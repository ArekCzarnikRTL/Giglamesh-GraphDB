"use client";

import { useEffect, useRef, useState } from "react";
import { ModeSelector } from "./ModeSelector";
import { QueryInput } from "./QueryInput";
import { QueryMessage as QueryMessageView } from "./QueryMessage";
import { SourcePanel } from "./SourcePanel";
import { useGraphRag } from "@/hooks/useGraphRag";
import { useDocumentRag } from "@/hooks/useDocumentRag";
import { useNlpQuery } from "@/hooks/useNlpQuery";
import { useAgentStream } from "@/hooks/useAgentStream";
import { saveToHistory } from "@/lib/query-history";
import {
  AssistantMessage,
  QueryMessage,
  QueryMode,
  UserMessage,
} from "@/types/query";

interface Props {
  collectionId: string;
}

function newId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function QueryChat({ collectionId }: Props) {
  const [mode, setMode] = useState<QueryMode>("auto");
  const [messages, setMessages] = useState<QueryMessage[]>([]);
  const [selected, setSelected] = useState<AssistantMessage | null>(null);
  const pendingIdRef = useRef<string | null>(null);
  const lastQueryRef = useRef<string>("");
  const endRef = useRef<HTMLDivElement>(null);

  const graphRag = useGraphRag();
  const documentRag = useDocumentRag();
  const nlpQuery = useNlpQuery();
  const agentStream = useAgentStream();

  // Auto-scroll
  useEffect(() => {
    if (typeof endRef.current?.scrollIntoView === "function") {
      endRef.current.scrollIntoView({ behavior: "smooth" });
    }
  }, [messages]);

  function finalize(update: (m: AssistantMessage) => AssistantMessage) {
    const pid = pendingIdRef.current;
    pendingIdRef.current = null;
    setMessages((prev) =>
      prev.map((m) =>
        m.id === pid && m.role === "assistant"
          ? update(m)
          : m,
      ),
    );
  }

  function finalizeError(message: string) {
    if (!pendingIdRef.current) return;
    finalize((m) => ({
      ...m,
      status: "error",
      error: { message, originalQuery: lastQueryRef.current },
    }));
  }

  // graph-rag finalize
  useEffect(() => {
    if (!graphRag.data || !pendingIdRef.current) return;
    finalize((m) => ({ ...m, status: "done", graphRag: graphRag.data }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [graphRag.data]);
  useEffect(() => {
    if (graphRag.error) finalizeError(graphRag.error.message);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [graphRag.error]);

  // document-rag finalize
  useEffect(() => {
    if (!documentRag.data || !pendingIdRef.current) return;
    finalize((m) => ({ ...m, status: "done", documentRag: documentRag.data }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documentRag.data]);
  useEffect(() => {
    if (documentRag.error) finalizeError(documentRag.error.message);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documentRag.error]);

  // nlp finalize
  useEffect(() => {
    if (!nlpQuery.data || !pendingIdRef.current) return;
    finalize((m) => ({ ...m, status: "done", nlpQuery: nlpQuery.data }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nlpQuery.data]);
  useEffect(() => {
    if (nlpQuery.error) finalizeError(nlpQuery.error.message);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nlpQuery.error]);

  // agent-stream live update
  useEffect(() => {
    if (!pendingIdRef.current) return;
    if (agentStream.tokens.length === 0) return;
    const finalAnswer = agentStream.tokens
      .filter((t) => t.type === "ANSWER")
      .map((t) => t.content)
      .join("");
    const isDone = agentStream.tokens.some((t) => t.endOfStream);
    setMessages((prev) =>
      prev.map((m) =>
        m.id === pendingIdRef.current && m.role === "assistant"
          ? {
              ...m,
              status: isDone ? "done" : "streaming",
              agentStream: { tokens: agentStream.tokens, finalAnswer },
            }
          : m,
      ),
    );
    if (isDone) {
      pendingIdRef.current = null;
    }
  }, [agentStream.tokens]);
  useEffect(() => {
    if (agentStream.error) finalizeError(agentStream.error.message);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentStream.error]);

  function handleSubmit(question: string) {
    lastQueryRef.current = question;
    saveToHistory({ query: question, mode, collectionId });

    const userMsg: UserMessage = {
      id: newId(),
      role: "user",
      content: question,
      mode,
      collectionId,
      timestamp: Date.now(),
    };
    const assistantMsg: AssistantMessage = {
      id: newId(),
      role: "assistant",
      mode,
      collectionId,
      status: mode === "agent-stream" ? "streaming" : "pending",
      timestamp: Date.now(),
      ...(mode === "agent-stream"
        ? { agentStream: { tokens: [], finalAnswer: "" } }
        : {}),
    };
    pendingIdRef.current = assistantMsg.id;
    setMessages((prev) => [...prev, userMsg, assistantMsg]);

    if (mode === "graph-rag") {
      graphRag.execute(question, collectionId);
    } else if (mode === "document-rag") {
      documentRag.execute(question, collectionId);
    } else if (mode === "auto") {
      nlpQuery.execute(question, collectionId);
    } else if (mode === "agent-stream") {
      agentStream.reset();
      agentStream.execute(question, collectionId);
    }
  }

  const isLoading =
    graphRag.loading ||
    documentRag.loading ||
    nlpQuery.loading ||
    agentStream.streaming;

  return (
    <div className="flex flex-1 overflow-hidden">
      <div className="flex flex-1 flex-col">
        <div className="border-b p-4">
          <ModeSelector value={mode} onChange={setMode} />
        </div>
        <div className="flex-1 space-y-4 overflow-y-auto p-4">
          {messages.length === 0 && (
            <p className="text-sm text-muted-foreground">
              Stelle eine Frage, um zu beginnen.
            </p>
          )}
          {messages.map((m) => (
            <QueryMessageView
              key={m.id}
              message={m}
              onShowSources={(msg) => setSelected(msg)}
              onRetry={(q) => handleSubmit(q)}
            />
          ))}
          <div ref={endRef} />
        </div>
        <QueryInput disabled={isLoading} onSubmit={handleSubmit} />
      </div>
      {selected && (
        <SourcePanel message={selected} onClose={() => setSelected(null)} />
      )}
    </div>
  );
}
