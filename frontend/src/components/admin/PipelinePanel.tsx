"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@apollo/client/react";
import {
  ADMIN_COLLECTIONS_QUERY,
  PIPELINE_DOCUMENTS_QUERY,
} from "@/graphql/admin";
import { AdminCollection, PipelineDocument } from "@/types/admin";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { PipelineDocumentList } from "./PipelineDocumentList";

interface CollectionsData {
  collections: AdminCollection[];
}

interface PipelineData {
  processing: { items: PipelineDocument[]; totalCount: number };
  failed: { items: PipelineDocument[]; totalCount: number };
}

interface Props {
  initialCollectionId?: string;
}

function PipelinePanelInner({ initialCollectionId }: Props) {
  const search = useSearchParams();
  const urlCollectionId = search?.get("collectionId") ?? null;

  const [collectionId, setCollectionId] = useState<string | null>(
    initialCollectionId ?? urlCollectionId ?? null,
  );

  // Sync if URL param changes after mount (e.g., navigation from dashboard)
  useEffect(() => {
    if (urlCollectionId && urlCollectionId !== collectionId) {
      setCollectionId(urlCollectionId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlCollectionId]);

  const { data: collectionsData } = useQuery<CollectionsData>(
    ADMIN_COLLECTIONS_QUERY,
  );

  const processingTotalRef = useRef(0);

  const { data, loading, error } = useQuery<PipelineData>(
    PIPELINE_DOCUMENTS_QUERY,
    {
      variables: {
        collectionId,
        processingFilter: { state: "PROCESSING" },
        failedFilter: { state: "FAILED" },
      },
      skip: !collectionId,
      fetchPolicy: "cache-and-network",
      pollInterval: 10000,
      skipPollAttempt: () => processingTotalRef.current === 0,
    },
  );

  useEffect(() => {
    processingTotalRef.current = data?.processing.totalCount ?? 0;
  }, [data]);

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <span className="text-sm text-muted-foreground">Collection:</span>
        <Select
          value={collectionId ?? undefined}
          onValueChange={(v) => setCollectionId(v)}
        >
          <SelectTrigger className="w-72">
            <SelectValue placeholder="Collection auswählen…">
              {(value) =>
                collectionsData?.collections.find((c) => c.id === value)?.name ??
                value
              }
            </SelectValue>
          </SelectTrigger>
          <SelectContent>
            {collectionsData?.collections.map((c) => (
              <SelectItem key={c.id} value={c.id}>
                {c.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {!collectionId && (
        <p className="text-muted-foreground">Bitte Collection auswählen.</p>
      )}

      {collectionId && loading && !data && (
        <div className="space-y-2">
          <Skeleton className="h-9 w-full" />
          <Skeleton className="h-9 w-full" />
        </div>
      )}

      {error && (
        <Alert variant="destructive">
          <AlertTitle>Fehler</AlertTitle>
          <AlertDescription>{error.message}</AlertDescription>
        </Alert>
      )}

      {data && (
        <>
          <PipelineDocumentList
            title="In Verarbeitung"
            documents={data.processing.items}
            emptyMessage="Keine Dokumente in Verarbeitung."
          />
          <PipelineDocumentList
            title="Fehlgeschlagen"
            documents={data.failed.items}
            emptyMessage="Keine fehlgeschlagenen Dokumente."
          />
        </>
      )}
    </div>
  );
}

export function PipelinePanel(props: Props) {
  return (
    <Suspense fallback={<Skeleton className="h-9 w-full" />}>
      <PipelinePanelInner {...props} />
    </Suspense>
  );
}
