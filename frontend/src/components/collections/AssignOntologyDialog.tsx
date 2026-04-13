"use client";

import { useMutation, useQuery } from "@apollo/client/react";
import { LIST_ONTOLOGIES_QUERY } from "@/graphql/admin";
import { ASSIGN_ONTOLOGY_MUTATION } from "@/graphql/collection-data";
import { useState } from "react";

interface Props {
  collectionId: string;
  existingKeys: string[];
  onClose: () => void;
  onSuccess: () => void;
}

export function AssignOntologyDialog({ collectionId, existingKeys, onClose, onSuccess }: Props) {
  const [ontologyKey, setOntologyKey] = useState("");
  const [role, setRole] = useState("domain");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const { data } = useQuery<{ listOntologies: { key: string; name: string }[] }>(LIST_ONTOLOGIES_QUERY);
  const [assignMutation] = useMutation(ASSIGN_ONTOLOGY_MUTATION);

  const available = data?.listOntologies?.filter((o) => !existingKeys.includes(o.key)) ?? [];

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!ontologyKey) return;
    setError("");
    setLoading(true);
    try {
      await assignMutation({ variables: { collectionId, ontologyKey, role } });
      onSuccess();
    } catch (err) {
      setError((err as Error).message || "Zuordnung fehlgeschlagen");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="w-full max-w-md rounded-lg border border-border bg-card p-6">
        <h2 className="mb-4 text-lg font-semibold">Bestehende Ontologie zuordnen</h2>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="mb-1 block text-sm">Ontologie</label>
            <select value={ontologyKey} onChange={(e) => setOntologyKey(e.target.value)} required className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm">
              <option value="">Ontologie waehlen...</option>
              {available.map((o) => (
                <option key={o.key} value={o.key}>{o.name || o.key}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="mb-1 block text-sm">Rolle</label>
            <select value={role} onChange={(e) => setRole(e.target.value)} className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm">
              <option value="domain">Domain</option>
              <option value="upper">Upper</option>
              <option value="skos">SKOS</option>
              <option value="custom">Custom</option>
            </select>
          </div>
          {error && <p className="text-sm text-red-400">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className="rounded-md px-4 py-2 text-sm hover:bg-muted">Abbrechen</button>
            <button type="submit" disabled={loading || !ontologyKey} className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50">
              {loading ? "Zuordnen..." : "Zuordnen"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
