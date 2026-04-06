"use client";

import { useRouter } from "next/navigation";
import { useActiveCollection } from "@/lib/collection-store";
import { DocumentUpload } from "@/components/documents/DocumentUpload";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

export default function UploadPage() {
  const { collectionId, hydrated } = useActiveCollection();
  const router = useRouter();

  if (!hydrated) return null;

  if (!collectionId) {
    return (
      <Alert>
        <AlertTitle>Keine Collection ausgewählt</AlertTitle>
        <AlertDescription>
          Bitte oben eine Collection auswählen, bevor du ein Dokument hochlädst.
        </AlertDescription>
      </Alert>
    );
  }

  return (
    <div className="max-w-2xl">
      <h2 className="text-xl font-semibold mb-4">Dokument hochladen</h2>
      <DocumentUpload
        collectionId={collectionId}
        onUploaded={(id) => router.push(`/documents/${id}`)}
      />
    </div>
  );
}
