"use client";

import { useLazyQuery } from "@apollo/client/react";
import { useCallback } from "react";
import { DOCUMENT_RAG_QUERY } from "@/graphql/query";
import { DocumentRagPayload } from "@/types/query";

interface DocumentRagQueryData {
  documentRag: DocumentRagPayload;
}

export function useDocumentRag() {
  const [run, { data, loading, error, reset }] =
    useLazyQuery<DocumentRagQueryData>(DOCUMENT_RAG_QUERY, {
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
    data: data?.documentRag,
    loading,
    error,
    reset,
  };
}
