"use client";

import { useMutation } from "@apollo/client/react";
import { useState } from "react";
import { IMPORT_RDF_MUTATION } from "@/graphql/admin";

interface Props {
  collectionId: string;
  onClose: () => void;
  onSuccess: () => void;
}

export function UploadDataDialog({ collectionId, onClose, onSuccess }: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [dataset, setDataset] = useState("");
  const [generateEmbeddings, setGenerateEmbeddings] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const [importMutation] = useMutation(IMPORT_RDF_MUTATION);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) return;
    setError("");
    setLoading(true);
    try {
      const content = btoa(await file.text());
      let format = "TURTLE";
      if (file.name.endsWith(".rdf")) format = "RDFXML";
      else if (file.name.endsWith(".nt")) format = "NTRIPLES";
      await importMutation({
        variables: {
          input: { collectionId, content, format, dataset: dataset || null, generateEmbeddings },
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
        <h2 className="mb-4 text-lg font-semibold">RDF-Daten importieren</h2>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="mb-1 block text-sm">Datei (.ttl / .rdf / .nt)</label>
            <input type="file" accept=".ttl,.rdf,.nt" onChange={(e) => setFile(e.target.files?.[0] ?? null)} required className="w-full text-sm" />
          </div>
          <div>
            <label className="mb-1 block text-sm">Dataset (optional)</label>
            <input value={dataset} onChange={(e) => setDataset(e.target.value)} placeholder="z.B. import-2026-04" className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm" />
          </div>
          <div className="flex items-center gap-2">
            <input type="checkbox" id="embeddings" checked={generateEmbeddings} onChange={(e) => setGenerateEmbeddings(e.target.checked)} className="rounded" />
            <label htmlFor="embeddings" className="text-sm">Embeddings erzeugen</label>
          </div>
          {error && <p className="text-sm text-red-400">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className="rounded-md px-4 py-2 text-sm hover:bg-muted">Abbrechen</button>
            <button type="submit" disabled={loading} className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50">
              {loading ? "Importieren..." : "Importieren"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
