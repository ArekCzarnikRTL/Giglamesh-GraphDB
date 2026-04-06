"use client";

import { useLazyQuery } from "@apollo/client/react";
import { useCallback } from "react";
import { NLP_QUERY } from "@/graphql/query";
import { NlpQueryPayload } from "@/types/query";

interface NlpQueryData {
  nlpQuery: NlpQueryPayload;
}

export function useNlpQuery() {
  const [run, { data, loading, error }] = useLazyQuery<NlpQueryData>(
    NLP_QUERY,
    { fetchPolicy: "network-only" },
  );

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
    data: data?.nlpQuery,
    loading,
    error,
  };
}
