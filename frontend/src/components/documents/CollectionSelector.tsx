"use client";

import { useQuery } from "@apollo/client/react";
import { COLLECTIONS_QUERY } from "@/graphql/queries";
import { useActiveCollection } from "@/lib/collection-store";
import { Collection } from "@/types/document";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";

interface CollectionsQueryData {
  collections: Collection[];
}

export function CollectionSelector() {
  const { collectionId, setCollectionId } = useActiveCollection();
  const { data, loading, error } = useQuery<CollectionsQueryData>(COLLECTIONS_QUERY);

  if (loading) return <Skeleton className="h-9 w-56" />;
  if (error) return <div className="text-sm text-red-600">Fehler: {error.message}</div>;

  return (
    <Select
      value={collectionId ?? undefined}
      onValueChange={(v) => setCollectionId(v)}
    >
      <SelectTrigger className="w-56">
        <SelectValue placeholder="Collection auswählen…">
          {(value) =>
            data?.collections.find((c) => c.id === value)?.name ?? value
          }
        </SelectValue>
      </SelectTrigger>
      <SelectContent>
        {data?.collections.map((col) => (
          <SelectItem key={col.id} value={col.id}>
            {col.name}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
