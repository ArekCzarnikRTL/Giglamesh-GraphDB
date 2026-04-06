"use client";

import { useCallback, useEffect, useState } from "react";
import { useDropzone } from "react-dropzone";
import { useMutation, useQuery } from "@apollo/client/react";
import { UPLOAD_DOCUMENT } from "@/graphql/mutations";
import { DOCUMENT_QUERY } from "@/graphql/queries";
import { Progress } from "@/components/ui/progress";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { DocumentState, DocumentDetail } from "@/types/document";
import { toast } from "sonner";

interface Props {
  collectionId: string;
  onUploaded?: (documentId: string) => void;
}

interface UploadDocumentData {
  uploadDocument: {
    id: string;
    collectionId: string;
    title: string;
    mimeType: string;
    state: DocumentState;
    type: string;
  };
}

interface DocumentQueryData {
  document: DocumentDetail | null;
}

const STAGE_PROGRESS: Record<DocumentState, number> = {
  UPLOADED: 35,
  PROCESSING: 70,
  EXTRACTED: 100,
  FAILED: 100,
};

function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result as string;
      resolve(result.split(",")[1] ?? "");
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

export function DocumentUpload({ collectionId, onUploaded }: Props) {
  const [documentId, setDocumentId] = useState<string | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [uploadDocument, { loading: uploading }] =
    useMutation<UploadDocumentData>(UPLOAD_DOCUMENT);

  const { data: pollData, stopPolling } = useQuery<DocumentQueryData>(
    DOCUMENT_QUERY,
    {
      variables: { id: documentId },
      skip: !documentId,
      pollInterval: 2000,
    },
  );

  const state: DocumentState | undefined = pollData?.document?.state;
  const progress = state
    ? STAGE_PROGRESS[state] ?? 35
    : uploading
      ? 15
      : documentId
        ? 35
        : 0;

  useEffect(() => {
    if (state === "EXTRACTED" || state === "FAILED") {
      stopPolling();
      if (state === "EXTRACTED" && documentId && onUploaded) {
        onUploaded(documentId);
      }
      if (state === "FAILED") {
        toast.error("Extraktion fehlgeschlagen");
      }
    }
  }, [state, stopPolling, documentId, onUploaded]);

  const onDrop = useCallback(
    async (accepted: File[]) => {
      const file = accepted[0];
      if (!file) return;
      setUploadError(null);
      try {
        const base64 = await fileToBase64(file);
        const { data } = await uploadDocument({
          variables: {
            input: {
              collectionId,
              title: file.name,
              mimeType: file.type || "application/pdf",
              content: base64,
              metadata: null,
            },
          },
        });
        const id = data?.uploadDocument?.id;
        if (id) {
          setDocumentId(id);
          toast.success(`${file.name} hochgeladen`);
        }
      } catch (err) {
        const msg = err instanceof Error ? err.message : "Upload fehlgeschlagen";
        setUploadError(msg);
        toast.error(msg);
      }
    },
    [collectionId, uploadDocument],
  );

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    onDrop,
    accept: { "application/pdf": [".pdf"] },
    maxFiles: 1,
    multiple: false,
  });

  return (
    <div className="space-y-4">
      <div
        {...getRootProps()}
        className={`flex h-48 cursor-pointer items-center justify-center rounded-md border-2 border-dashed ${
          isDragActive ? "border-primary bg-muted" : "border-muted-foreground/30"
        }`}
      >
        <input {...getInputProps()} aria-label="Datei wählen" />
        <p className="text-sm text-muted-foreground">
          {isDragActive
            ? "PDF hier ablegen…"
            : "PDF hier ablegen oder klicken zum Auswählen"}
        </p>
      </div>

      {(uploading || documentId) && (
        <div className="space-y-2">
          <Progress value={progress} />
          <p className="text-sm text-muted-foreground">
            {state ? `Status: ${state}` : "Lade hoch…"}
          </p>
        </div>
      )}

      {uploadError && (
        <Alert variant="destructive">
          <AlertTitle>Upload fehlgeschlagen</AlertTitle>
          <AlertDescription>{uploadError}</AlertDescription>
        </Alert>
      )}
    </div>
  );
}
