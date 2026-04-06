"use client";

import { useQuery } from "@apollo/client/react";
import { DOCUMENT_TRIPLES_QUERY } from "@/graphql/queries";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Quad } from "@/types/document";

interface Props {
  collectionId: string;
  documentId: string;
}

export function ExtractedTriples({ collectionId, documentId }: Props) {
  // Subject convention: producers in the codebase use the bare documentId
  // (see extraction/decoder/PageExtractedProducer.kt which sets
  // subject = event.documentId).
  const { data, loading } = useQuery<{ triples: Quad[] }>(
    DOCUMENT_TRIPLES_QUERY,
    {
      variables: { collectionId, subject: documentId },
    }
  );

  return (
    <Card>
      <CardHeader>
        <CardTitle>Extrahierte Triples</CardTitle>
      </CardHeader>
      <CardContent className="space-y-1 text-xs font-mono">
        {loading && <Skeleton className="h-16 w-full" />}
        {!loading && (data?.triples.length ?? 0) === 0 && (
          <p className="text-muted-foreground">Keine Triples.</p>
        )}
        {data?.triples.map((t, i) => (
          <div key={i} className="border-b py-1">
            <span className="text-blue-600">{t.subject}</span>{" "}
            <span className="text-green-700">{t.predicate}</span>{" "}
            <span>{t.object}</span>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}
