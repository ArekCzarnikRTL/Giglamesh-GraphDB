// frontend/src/components/admin/AdminDashboard.tsx
"use client";

import { useCallback, useState } from "react";
import { useQuery } from "@apollo/client/react";
import { ADMIN_COLLECTIONS_QUERY } from "@/graphql/admin";
import { AdminCollection } from "@/types/admin";
import { MetricCard } from "./MetricCard";
import { CollectionStatsRow } from "./CollectionStatsRow";
import { PurgePanel } from "./PurgePanel";
import {
  Table,
  TableBody,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

interface CollectionsData {
  collections: AdminCollection[];
}

export function AdminDashboard() {
  const { data, loading, error } = useQuery<CollectionsData>(
    ADMIN_COLLECTIONS_QUERY,
    {
      fetchPolicy: "cache-and-network",
      pollInterval: 30000,
    },
  );

  const [counts, setCounts] = useState<
    Record<string, { processing: number; failed: number }>
  >({});

  const handleCounts = useCallback(
    (id: string, processing: number, failed: number) => {
      setCounts((prev) => ({ ...prev, [id]: { processing, failed } }));
    },
    [],
  );

  const totalCollections = data?.collections.length ?? 0;
  const totalProcessing = Object.values(counts).reduce(
    (sum, c) => sum + c.processing,
    0,
  );
  const totalFailed = Object.values(counts).reduce(
    (sum, c) => sum + c.failed,
    0,
  );
  const collectionsWithBacklog = Object.values(counts).filter(
    (c) => c.processing > 0,
  ).length;

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <MetricCard label="Collections" value={totalCollections} />
        <MetricCard label="In Verarbeitung" value={totalProcessing} />
        <MetricCard label="Fehlgeschlagen" value={totalFailed} />
        <MetricCard label="Mit Backlog" value={collectionsWithBacklog} />
      </div>

      {loading && !data && (
        <div className="space-y-2">
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
        </div>
      )}

      {error && (
        <Alert variant="destructive">
          <AlertTitle>Fehler</AlertTitle>
          <AlertDescription>{error.message}</AlertDescription>
        </Alert>
      )}

      {data && data.collections.length === 0 && (
        <p className="text-muted-foreground">Noch keine Collections.</p>
      )}

      {data && data.collections.length > 0 && (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead>Tags</TableHead>
              <TableHead>In Verarbeitung</TableHead>
              <TableHead>Fehlgeschlagen</TableHead>
              <TableHead>Aktionen</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.collections.map((c) => (
              <CollectionStatsRow
                key={c.id}
                collection={c}
                onCounts={handleCounts}
              />
            ))}
          </TableBody>
        </Table>
      )}

      <PurgePanel />
    </div>
  );
}
