"use client";

import { useMutation } from "@apollo/client/react";
import { IMPORT_ONTOLOGY_MUTATION } from "@/graphql/admin";
import { ASSIGN_ONTOLOGY_MUTATION } from "@/graphql/collection-data";
import { useState } from "react";

interface Props {
  collectionId: string;
  onClose: () => void;
  onSuccess: () => void;
}

export function UploadOntologyDialog({ collectionId, onClose, onSuccess }: Props) {
  const [key, setKey] = useState("");
  const [name, setName] = useState("");
  const [role, setRole] = useState("domain");
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const [importMutation] = useMutation(IMPORT_ONTOLOGY_MUTATION);
  const [assignMutation] = useMutation(ASSIGN_ONTOLOGY_MUTATION);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) return;
    setError("");
    setLoading(true);
    try {
      const text = await file.text();
      const content = btoa(unescape(encodeURIComponent(text)));
      const format = file.name.endsWith(".rdf") ? "RDFXML" : "TURTLE";
      await importMutation({
        variables: {
          input: { key, content, format, name: name || file.name, namespace: "http://example.org/ontology/" },
        },
      });
      await assignMutation({ variables: { collectionId, ontologyKey: key, role } });
      onSuccess();
    } catch (err) {
      setError((err as Error).message || "Upload fehlgeschlagen");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="w-full max-w-md rounded-lg border border-border bg-card p-6">
        <h2 className="mb-4 text-lg font-semibold">Ontologie hochladen</h2>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="mb-1 block text-sm">Datei (.ttl / .rdf)</label>
            <input type="file" accept=".ttl,.rdf" onChange={(e) => setFile(e.target.files?.[0] ?? null)} required className="w-full text-sm" />
          </div>
          <div>
            <label className="mb-1 block text-sm">Key</label>
            <input value={key} onChange={(e) => setKey(e.target.value)} placeholder="z.B. pharma-ontologie" required className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm" />
          </div>
          <div>
            <label className="mb-1 block text-sm">Name</label>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="z.B. Pharma-Domain-Ontologie" className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm" />
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
            <button type="submit" disabled={loading} className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50">
              {loading ? "Hochladen..." : "Hochladen & Zuordnen"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
