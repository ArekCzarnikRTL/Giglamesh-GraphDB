"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@apollo/client/react";
import { DOCUMENTS_QUERY } from "@/graphql/queries";
import { DocumentFilter, DocumentPage } from "@/types/document";
import { DocumentListItem } from "./DocumentListItem";
import { DocumentFilterBar } from "./DocumentFilterBar";
import { DocumentPagination } from "./DocumentPagination";
import {
  Table,
  TableBody,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

interface Props {
  collectionId: string;
}

interface DocumentsQueryData {
  documents: DocumentPage;
}

const PAGE_SIZE = 20;

export function DocumentList({ collectionId }: Props) {
  const [filter, setFilter] = useState<DocumentFilter>({});
  const [page, setPage] = useState(0);

  const { data, loading, error, startPolling, stopPolling } = useQuery<DocumentsQueryData>(DOCUMENTS_QUERY, {
    variables: { collectionId, filter, page, pageSize: PAGE_SIZE },
    fetchPolicy: "cache-and-network",
  });

  useEffect(() => {
    const hasInProgress = data?.documents.items.some(
      (d) => d.state === "UPLOADED" || d.state === "PROCESSING",
    );
    if (hasInProgress) {
      startPolling(3000);
    } else {
      stopPolling();
    }
  }, [data, startPolling, stopPolling]);

  return (
    <div className="space-y-4">
      <DocumentFilterBar
        value={filter}
        onChange={(f) => {
          setFilter(f);
          setPage(0);
        }}
      />

      {loading && !data && (
        <div className="space-y-2">
          <Skeleton className="h-9 w-full" />
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
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Titel</TableHead>
                <TableHead>Typ</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>MIME</TableHead>
                <TableHead>Erstellt</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.documents.items.map((d) => (
                <DocumentListItem key={d.id} document={d} />
              ))}
            </TableBody>
          </Table>
          <DocumentPagination
            page={page}
            pageSize={PAGE_SIZE}
            totalCount={data.documents.totalCount}
            onPageChange={setPage}
          />
        </>
      )}
    </div>
  );
}
