"use client";

import { useLazyQuery } from "@apollo/client/react";
import { useCallback } from "react";
import { GRAPH_RAG_QUERY } from "@/graphql/query";
import { GraphRagPayload } from "@/types/query";

interface GraphRagQueryData {
  graphRag: GraphRagPayload;
}

export function useGraphRag() {
  const [run, { data, loading, error }] =
    useLazyQuery<GraphRagQueryData>(GRAPH_RAG_QUERY, {
      fetchPolicy: "network-only",
    });

  const execute = useCallback(
    async (question: string, collectionId: string) => {
      await run({ variables: { input: { question, collectionId } } }).catch(
        () => {},
      );
    },
    [run],
  );

  return {
    execute,
    data: data?.graphRag,
    loading,
    error,
  };
}
