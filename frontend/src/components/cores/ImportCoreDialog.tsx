"use client";

import { useMutation, useQuery } from "@apollo/client/react";
import { IMPORT_CONTEXT_CORE_MUTATION } from "@/graphql/cores";
import { ADMIN_COLLECTIONS_QUERY } from "@/graphql/admin";
import type { AdminCollection } from "@/types/admin";
import { useState } from "react";

interface Props {
  coreId: string;
  version: string;
  onClose: () => void;
  onSuccess: () => void;
}

export function ImportCoreDialog({ coreId, version, onClose, onSuccess }: Props) {
  const [targetCollection, setTargetCollection] = useState("");
  const [strategy, setStrategy] = useState("FAIL");
  const [namespaceFrom, setNamespaceFrom] = useState("");
  const [namespaceTo, setNamespaceTo] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const { data: collectionsData } = useQuery<{ collections: AdminCollection[] }>(ADMIN_COLLECTIONS_QUERY);
  const [importMutation] = useMutation(IMPORT_CONTEXT_CORE_MUTATION);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      await importMutation({
        variables: {
          coreId,
          version,
          targetCollection,
          strategy,
          namespaceFrom: namespaceFrom || null,
          namespaceTo: namespaceTo || null,
        },
      });
      onSuccess();
    } catch (err) {
      setError((err as Error).message || "Import fehlgeschlagen");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="w-full max-w-md rounded-lg border border-border bg-card p-6">
        <h2 className="mb-4 text-lg font-semibold">
          Core importieren: {coreId}@{version}
        </h2>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="mb-1 block text-sm">Ziel-Collection</label>
            <select
              value={targetCollection}
              onChange={(e) => setTargetCollection(e.target.value)}
              required
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            >
              <option value="">Collection waehlen...</option>
              {collectionsData?.collections?.map((col) => (
                <option key={col.id} value={col.id}>
                  {col.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="mb-1 block text-sm">Konfliktstrategie</label>
            <select
              value={strategy}
              onChange={(e) => setStrategy(e.target.value)}
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            >
              <option value="FAIL">FAIL — Abbrechen wenn nicht leer</option>
              <option value="MERGE">MERGE — Hinzufuegen</option>
              <option value="REPLACE">REPLACE — Vorhandenes ersetzen</option>
            </select>
          </div>
          <div>
            <label className="mb-1 block text-sm">Namespace-Rewrite (optional)</label>
            <div className="flex gap-2">
              <input
                value={namespaceFrom}
                onChange={(e) => setNamespaceFrom(e.target.value)}
                placeholder="Von: http://old.org/"
                className="w-1/2 rounded-md border border-border bg-background px-3 py-2 text-sm"
              />
              <input
                value={namespaceTo}
                onChange={(e) => setNamespaceTo(e.target.value)}
                placeholder="Nach: http://new.org/"
                className="w-1/2 rounded-md border border-border bg-background px-3 py-2 text-sm"
              />
            </div>
          </div>
          {error && <p className="text-sm text-red-400">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className="rounded-md px-4 py-2 text-sm hover:bg-muted">
              Abbrechen
            </button>
            <button
              type="submit"
              disabled={loading}
              className="rounded-md bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50"
            >
              {loading ? "Importiere..." : "Importieren"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
