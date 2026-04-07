// frontend/src/components/admin/CollectionStatsRow.tsx
"use client";

import Link from "next/link";
import { useQuery } from "@apollo/client/react";
import { ADMIN_COLLECTION_COUNTS_QUERY } from "@/graphql/admin";
import { TableCell, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { AdminCollection } from "@/types/admin";

interface Props {
  collection: AdminCollection;
  onCounts?: (processing: number, failed: number) => void;
}

interface CountsData {
  processing: { totalCount: number };
  failed: { totalCount: number };
}

export function CollectionStatsRow({ collection, onCounts }: Props) {
  const { data, loading } = useQuery<CountsData>(ADMIN_COLLECTION_COUNTS_QUERY, {
    variables: {
      collectionId: collection.id,
      processingFilter: { state: "PROCESSING" },
      failedFilter: { state: "FAILED" },
    },
    fetchPolicy: "cache-and-network",
    pollInterval: 30000,
    onCompleted: (d) => {
      if (onCounts) {
        onCounts(d.processing.totalCount, d.failed.totalCount);
      }
    },
  });

  return (
    <TableRow>
      <TableCell className="font-medium">{collection.name}</TableCell>
      <TableCell>
        <div className="flex flex-wrap gap-1">
          {collection.tags.map((t) => (
            <Badge key={t} variant="secondary">
              {t}
            </Badge>
          ))}
        </div>
      </TableCell>
      <TableCell>
        {loading && !data ? (
          <Skeleton className="h-4 w-8" />
        ) : (
          <span>{data?.processing.totalCount ?? 0}</span>
        )}
      </TableCell>
      <TableCell>
        {loading && !data ? (
          <Skeleton className="h-4 w-8" />
        ) : (
          <span
            className={
              (data?.failed.totalCount ?? 0) > 0
                ? "font-bold text-red-600"
                : undefined
            }
          >
            {data?.failed.totalCount ?? 0}
          </span>
        )}
      </TableCell>
      <TableCell>
        <Link
          href={`/admin/pipeline?collectionId=${collection.id}`}
          className="text-sm text-blue-600 hover:underline"
        >
          Pipeline öffnen
        </Link>
      </TableCell>
    </TableRow>
  );
}
