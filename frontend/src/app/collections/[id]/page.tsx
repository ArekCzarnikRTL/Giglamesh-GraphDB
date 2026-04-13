"use client";

import { useQuery } from "@apollo/client/react";
import { useParams } from "next/navigation";
import { ADMIN_COLLECTIONS_QUERY } from "@/graphql/admin";
import type { AdminCollection } from "@/types/admin";
import { OntologySection } from "@/components/collections/OntologySection";

export default function CollectionDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { data } = useQuery<{ collections: AdminCollection[] }>(ADMIN_COLLECTIONS_QUERY);
  const collection = data?.collections?.find((c) => c.id === id);

  return (
    <main className="p-6">
      <div className="mb-8">
        <h1 className="text-2xl font-bold">{collection?.name ?? id}</h1>
        {collection?.description && <p className="mt-1 text-muted-foreground">{collection.description}</p>}
        {collection?.tags && collection.tags.length > 0 && (
          <div className="mt-2 flex gap-2">
            {collection.tags.map((tag) => (
              <span key={tag} className="rounded-full bg-secondary px-2 py-0.5 text-xs">{tag}</span>
            ))}
          </div>
        )}
      </div>
      <div className="space-y-10">
        <OntologySection collectionId={id} />
      </div>
    </main>
  );
}
