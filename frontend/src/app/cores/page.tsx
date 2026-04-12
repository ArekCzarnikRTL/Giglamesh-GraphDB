"use client";

import { useQuery, useMutation } from "@apollo/client/react";
import { CONTEXT_CORES_QUERY, DELETE_CONTEXT_CORE_MUTATION } from "@/graphql/cores";
import type { ContextCoresData } from "@/types/cores";
import { BuildCoreDialog } from "@/components/cores/BuildCoreDialog";
import { ImportCoreDialog } from "@/components/cores/ImportCoreDialog";
import { useState } from "react";

export default function CoresPage() {
  const { data, loading, refetch } = useQuery<ContextCoresData>(CONTEXT_CORES_QUERY);
  const [deleteMutation] = useMutation(DELETE_CONTEXT_CORE_MUTATION);
  const [showBuild, setShowBuild] = useState(false);
  const [importTarget, setImportTarget] = useState<{ coreId: string; version: string } | null>(null);

  const handleDelete = async (coreId: string, version: string) => {
    if (!confirm(`Context Core ${coreId}@${version} wirklich loeschen?`)) return;
    await deleteMutation({ variables: { coreId, version } });
    refetch();
  };

  return (
    <div className="mx-auto max-w-4xl p-6">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold">Context Cores</h1>
        <button
          onClick={() => setShowBuild(true)}
          className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          Core bauen
        </button>
      </div>

      {loading && <p className="text-muted-foreground">Laden...</p>}

      {!loading && data?.contextCores?.length === 0 && (
        <p className="text-muted-foreground">Noch keine Context Cores vorhanden.</p>
      )}

      <div className="grid gap-4">
        {data?.contextCores?.map((core) => (
          <div
            key={`${core.coreId}-${core.version}`}
            className="rounded-lg border border-border bg-card p-4"
          >
            <div className="flex items-start justify-between">
              <div>
                <h3 className="font-semibold">
                  {core.coreId}
                  <span className="ml-2 text-sm font-normal text-muted-foreground">
                    v{core.version}
                  </span>
                </h3>
                {core.description && (
                  <p className="mt-1 text-sm text-muted-foreground">{core.description}</p>
                )}
                <div className="mt-2 flex flex-wrap gap-4 text-xs text-muted-foreground">
                  <span>{core.stats.quadCount} Quads</span>
                  <span>{core.stats.entityCount} Entitaeten</span>
                  <span>{core.stats.chunkEmbeddingCount} Embeddings</span>
                  <span>Collection: {core.sourceCollection}</span>
                </div>
                {core.tags.length > 0 && (
                  <div className="mt-2 flex gap-1">
                    {core.tags.map((tag: string) => (
                      <span
                        key={tag}
                        className="rounded-full bg-blue-500/10 px-2 py-0.5 text-xs text-blue-400"
                      >
                        {tag}
                      </span>
                    ))}
                  </div>
                )}
              </div>
              <div className="flex gap-2">
                <button
                  onClick={() => setImportTarget({ coreId: core.coreId, version: core.version })}
                  className="text-xs text-green-400 hover:text-green-300"
                >
                  Importieren
                </button>
                <button
                  onClick={() => handleDelete(core.coreId, core.version)}
                  className="text-xs text-red-400 hover:text-red-300"
                >
                  Loeschen
                </button>
              </div>
            </div>
          </div>
        ))}
      </div>

      {showBuild && (
        <BuildCoreDialog
          onClose={() => setShowBuild(false)}
          onSuccess={() => { setShowBuild(false); refetch(); }}
        />
      )}

      {importTarget && (
        <ImportCoreDialog
          coreId={importTarget.coreId}
          version={importTarget.version}
          onClose={() => setImportTarget(null)}
          onSuccess={() => { setImportTarget(null); refetch(); }}
        />
      )}
    </div>
  );
}
