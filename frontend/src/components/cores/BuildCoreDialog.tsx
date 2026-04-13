"use client";

import { useMutation, useQuery } from "@apollo/client/react";
import { BUILD_CONTEXT_CORE_MUTATION } from "@/graphql/cores";
import { ADMIN_COLLECTIONS_QUERY, LIST_ONTOLOGIES_QUERY } from "@/graphql/admin";
import type { AdminCollection } from "@/types/admin";
import { useState } from "react";

interface Props {
  onClose: () => void;
  onSuccess: () => void;
}

export function BuildCoreDialog({ onClose, onSuccess }: Props) {
  const [coreId, setCoreId] = useState("");
  const [version, setVersion] = useState("1.0.0");
  const [sourceCollection, setSourceCollection] = useState("");
  const [description, setDescription] = useState("");
  const [tags, setTags] = useState("");
  const [ontologyKey, setOntologyKey] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const { data: collectionsData } = useQuery<{ collections: AdminCollection[] }>(ADMIN_COLLECTIONS_QUERY);
  const { data: ontologiesData } = useQuery<{ listOntologies: { key: string; name: string }[] }>(LIST_ONTOLOGIES_QUERY);
  const [buildMutation] = useMutation(BUILD_CONTEXT_CORE_MUTATION);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      await buildMutation({
        variables: {
          coreId,
          version,
          sourceCollection,
          description: description || null,
          tags: tags ? tags.split(",").map((t) => t.trim()) : null,
          ontologyKey: ontologyKey || null,
        },
      });
      onSuccess();
    } catch (err) {
      setError((err as Error).message || "Build fehlgeschlagen");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="w-full max-w-md rounded-lg border border-border bg-card p-6">
        <h2 className="mb-4 text-lg font-semibold">Context Core bauen</h2>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="mb-1 block text-sm">Core ID</label>
            <input
              value={coreId}
              onChange={(e) => setCoreId(e.target.value)}
              placeholder="z.B. pharma-base"
              required
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm">Version</label>
            <input
              value={version}
              onChange={(e) => setVersion(e.target.value)}
              placeholder="1.0.0"
              required
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm">Quell-Collection</label>
            <select
              value={sourceCollection}
              onChange={(e) => setSourceCollection(e.target.value)}
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
            <label className="mb-1 block text-sm">Ontologie (optional)</label>
            <select
              value={ontologyKey}
              onChange={(e) => setOntologyKey(e.target.value)}
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            >
              <option value="">Keine Ontologie</option>
              {ontologiesData?.listOntologies?.map((ont) => (
                <option key={ont.key} value={ont.key}>
                  {ont.name || ont.key}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="mb-1 block text-sm">Beschreibung (optional)</label>
            <input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm">Tags (kommagetrennt, optional)</label>
            <input
              value={tags}
              onChange={(e) => setTags(e.target.value)}
              placeholder="stage, v1"
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            />
          </div>
          {error && <p className="text-sm text-red-400">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className="rounded-md px-4 py-2 text-sm hover:bg-muted">
              Abbrechen
            </button>
            <button
              type="submit"
              disabled={loading}
              className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {loading ? "Baue..." : "Core bauen"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
