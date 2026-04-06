"use client";

import { useActiveCollection } from "@/lib/collection-store";
import { DocumentList } from "@/components/documents/DocumentList";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

export default function DocumentsPage() {
  const { collectionId, hydrated } = useActiveCollection();

  if (!hydrated) return null;

  if (!collectionId) {
    return (
      <Alert>
        <AlertTitle>Keine Collection ausgewählt</AlertTitle>
        <AlertDescription>
          Bitte oben eine Collection auswählen, um Dokumente zu sehen.
        </AlertDescription>
      </Alert>
    );
  }

  return <DocumentList collectionId={collectionId} />;
}
