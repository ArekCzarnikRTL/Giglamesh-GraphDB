"use client";

import { useMutation, useQuery } from "@apollo/client/react";
import { COLLECTION_DATA_STATS_QUERY, DELETE_TRIPLES_MUTATION } from "@/graphql/collection-data";
import type { CollectionDataStats } from "@/types/collection-data";
import { UploadDataDialog } from "./UploadDataDialog";
import { useState } from "react";

interface Props {
  collectionId: string;
}

export function DataSection({ collectionId }: Props) {
  const [showUpload, setShowUpload] = useState(false);

  const { data, refetch } = useQuery<{ collectionDataStats: CollectionDataStats }>(
    COLLECTION_DATA_STATS_QUERY, { variables: { collectionId } }
  );
  const [deleteMutation] = useMutation(DELETE_TRIPLES_MUTATION);

  const stats = data?.collectionDataStats;

  const handleDeleteDataset = async (ds: string) => {
    if (!confirm(`Alle Tripel im Dataset "${ds}" wirklich loeschen?`)) return;
    await deleteMutation({ variables: { collectionId, dataset: ds } });
    refetch();
  };

  const handleDeleteAll = async () => {
    if (!confirm("ALLE Tripel in dieser Collection wirklich loeschen? Diese Aktion kann nicht rueckgaengig gemacht werden.")) return;
    await deleteMutation({ variables: { collectionId, dataset: null } });
    refetch();
  };

  const handleSuccess = () => { setShowUpload(false); refetch(); };

  return (
    <section>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-semibold">RDF-Daten</h2>
        <div className="flex gap-2">
          {stats && stats.tripleCount > 0 && (
            <button onClick={handleDeleteAll} className="rounded-md border border-red-400/50 px-3 py-1.5 text-sm text-red-400 hover:bg-red-400/10">Alle loeschen</button>
          )}
          <button onClick={() => setShowUpload(true)} className="rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700">Daten hochladen</button>
        </div>
      </div>
      {stats ? (
        <>
          <div className="mb-6 grid grid-cols-3 gap-4">
            <div className="rounded-lg border border-border p-4">
              <div className="text-2xl font-bold">{stats.tripleCount.toLocaleString()}</div>
              <div className="text-sm text-muted-foreground">Tripel</div>
            </div>
            <div className="rounded-lg border border-border p-4">
              <div className="text-2xl font-bold">{stats.entityCount.toLocaleString()}</div>
              <div className="text-sm text-muted-foreground">Entitaeten</div>
            </div>
            <div className="rounded-lg border border-border p-4">
              <div className="text-2xl font-bold">{stats.predicateCount.toLocaleString()}</div>
              <div className="text-sm text-muted-foreground">Praedikate</div>
            </div>
          </div>
          {stats.datasets.length > 0 && (
            <div className="overflow-hidden rounded-lg border border-border">
              <table className="w-full text-sm">
                <thead className="bg-muted/50">
                  <tr>
                    <th className="px-4 py-2 text-left font-medium">Dataset</th>
                    <th className="px-4 py-2 text-right font-medium">Aktion</th>
                  </tr>
                </thead>
                <tbody>
                  {stats.datasets.map((ds) => (
                    <tr key={ds} className="border-t border-border">
                      <td className="px-4 py-2 font-mono text-xs">{ds}</td>
                      <td className="px-4 py-2 text-right">
                        <button onClick={() => handleDeleteDataset(ds)} className="text-xs text-red-400 hover:text-red-300">Loeschen</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      ) : (
        <p className="text-sm text-muted-foreground">Keine Daten vorhanden.</p>
      )}
      {showUpload && <UploadDataDialog collectionId={collectionId} onClose={() => setShowUpload(false)} onSuccess={handleSuccess} />}
    </section>
  );
}
