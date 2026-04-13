"use client";

import { useQuery } from "@apollo/client/react";
import { ADMIN_COLLECTIONS_QUERY } from "@/graphql/admin";
import type { AdminCollection } from "@/types/admin";
import Link from "next/link";

export default function CollectionsPage() {
  const { data, loading } = useQuery<{ collections: AdminCollection[] }>(ADMIN_COLLECTIONS_QUERY);

  return (
    <main className="p-6">
      <h1 className="mb-6 text-2xl font-bold">Collections</h1>
      {loading && <p className="text-muted-foreground">Laden...</p>}
      <div className="grid gap-4">
        {data?.collections?.map((col) => (
          <Link
            key={col.id}
            href={`/collections/${col.id}`}
            className="block rounded-lg border border-border p-4 hover:bg-muted/50 transition-colors"
          >
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-lg font-semibold">{col.name}</h2>
                {col.description && (
                  <p className="mt-1 text-sm text-muted-foreground">{col.description}</p>
                )}
              </div>
              <div className="flex gap-2">
                {col.tags?.map((tag) => (
                  <span key={tag} className="rounded-full bg-secondary px-2 py-0.5 text-xs">
                    {tag}
                  </span>
                ))}
              </div>
            </div>
          </Link>
        ))}
      </div>
    </main>
  );
}
