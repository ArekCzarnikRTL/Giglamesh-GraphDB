"use client";

import { useQuery } from "@apollo/client/react";
import { DOCUMENT_CHUNKS_QUERY } from "@/graphql/queries";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { DocumentChunk } from "@/types/document";

export function DocumentChunks({ documentId }: { documentId: string }) {
  const { data, loading } = useQuery<{ documentChunks: DocumentChunk[] }>(
    DOCUMENT_CHUNKS_QUERY,
    { variables: { documentId } }
  );

  return (
    <Card>
      <CardHeader>
        <CardTitle>Chunks</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2 text-sm">
        {loading && <Skeleton className="h-16 w-full" />}
        {!loading && data?.documentChunks.length === 0 && (
          <p className="text-muted-foreground">Keine Chunks.</p>
        )}
        {data?.documentChunks.map((c) => (
          <div key={c.id} className="border-b py-1">
            <div className="font-mono text-xs text-muted-foreground">
              {c.id}
            </div>
            <div>{c.title}</div>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}
