"use client";

import { useState, useCallback } from "react";
import { useSubscription } from "@apollo/client/react";
import { AGENT_STREAM_SUBSCRIPTION } from "@/graphql/query";
import { AgentStreamToken } from "@/types/query";

interface AgentStreamData {
  agentStream: AgentStreamToken;
}

interface Variables {
  input: { question: string; collectionId: string };
}

export function useAgentStream() {
  const [variables, setVariables] = useState<Variables | null>(null);
  const [tokens, setTokens] = useState<AgentStreamToken[]>([]);
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState<Error | undefined>(undefined);

  useSubscription<AgentStreamData>(AGENT_STREAM_SUBSCRIPTION, {
    variables: variables ?? undefined,
    skip: variables === null,
    onData: ({ data }) => {
      const token = data.data?.agentStream;
      if (!token) return;
      setTokens((prev) => [...prev, token]);
      if (token.endOfStream) {
        setStreaming(false);
      }
    },
    onError: (err) => {
      setError(err);
      setStreaming(false);
    },
  });

  const execute = useCallback((question: string, collectionId: string) => {
    setTokens([]);
    setError(undefined);
    setStreaming(true);
    setVariables({ input: { question, collectionId } });
  }, []);

  const reset = useCallback(() => {
    setTokens([]);
    setError(undefined);
    setStreaming(false);
    setVariables(null);
  }, []);

  return { execute, reset, tokens, streaming, error };
}
