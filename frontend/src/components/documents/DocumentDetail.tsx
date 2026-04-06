"use client";

import { useQuery } from "@apollo/client/react";
import { DOCUMENT_QUERY } from "@/graphql/queries";
import { DocumentDetail as DocDetailType } from "@/types/document";
import { DocumentMetadata } from "./DocumentMetadata";
import { DocumentHierarchy } from "./DocumentHierarchy";
import { DocumentChunks } from "./DocumentChunks";
import { ExtractedTriples } from "./ExtractedTriples";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

export function DocumentDetail({ documentId }: { documentId: string }) {
  const { data, loading, error } = useQuery<{
    document: DocDetailType | null;
  }>(DOCUMENT_QUERY, { variables: { id: documentId } });

  if (loading) return <Skeleton className="h-64 w-full" />;
  if (error)
    return (
      <Alert variant="destructive">
        <AlertTitle>Fehler</AlertTitle>
        <AlertDescription>{error.message}</AlertDescription>
      </Alert>
    );
  if (!data?.document)
    return (
      <Alert>
        <AlertTitle>Nicht gefunden</AlertTitle>
        <AlertDescription>
          Dokument {documentId} existiert nicht.
        </AlertDescription>
      </Alert>
    );

  const doc = data.document;

  return (
    <div className="grid gap-4 md:grid-cols-3">
      <div className="space-y-4 md:col-span-2">
        <h2 className="text-2xl font-bold">{doc.title}</h2>
        <DocumentMetadata document={doc} />
        <DocumentHierarchy
          parentId={doc.parentId}
          childDocuments={doc.children}
        />
        <DocumentChunks documentId={doc.id} />
      </div>
      <aside>
        <ExtractedTriples collectionId={doc.collectionId} documentId={doc.id} />
      </aside>
    </div>
  );
}
