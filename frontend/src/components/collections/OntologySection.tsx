"use client";

import { useMutation, useQuery } from "@apollo/client/react";
import { COLLECTION_ONTOLOGIES_QUERY, UNASSIGN_ONTOLOGY_MUTATION } from "@/graphql/collection-data";
import type { CollectionOntology } from "@/types/collection-data";
import { UploadOntologyDialog } from "./UploadOntologyDialog";
import { AssignOntologyDialog } from "./AssignOntologyDialog";
import { useState } from "react";

interface Props {
  collectionId: string;
}

export function OntologySection({ collectionId }: Props) {
  const [showUpload, setShowUpload] = useState(false);
  const [showAssign, setShowAssign] = useState(false);

  const { data, refetch } = useQuery<{ collectionOntologies: CollectionOntology[] }>(
    COLLECTION_ONTOLOGIES_QUERY, { variables: { collectionId } }
  );
  const [unassignMutation] = useMutation(UNASSIGN_ONTOLOGY_MUTATION);

  const ontologies = data?.collectionOntologies ?? [];

  const handleUnassign = async (ontologyKey: string) => {
    if (!confirm(`Zuordnung von "${ontologyKey}" wirklich entfernen?`)) return;
    await unassignMutation({ variables: { collectionId, ontologyKey } });
    refetch();
  };

  const handleSuccess = () => { setShowUpload(false); setShowAssign(false); refetch(); };

  return (
    <section>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-semibold">Ontologien</h2>
        <div className="flex gap-2">
          <button onClick={() => setShowAssign(true)} className="rounded-md border border-border px-3 py-1.5 text-sm hover:bg-muted">Bestehende zuordnen</button>
          <button onClick={() => setShowUpload(true)} className="rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700">Hochladen</button>
        </div>
      </div>
      {ontologies.length === 0 ? (
        <p className="text-sm text-muted-foreground">Keine Ontologien zugeordnet.</p>
      ) : (
        <div className="overflow-hidden rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="bg-muted/50">
              <tr>
                <th className="px-4 py-2 text-left font-medium">Key</th>
                <th className="px-4 py-2 text-left font-medium">Name</th>
                <th className="px-4 py-2 text-left font-medium">Rolle</th>
                <th className="px-4 py-2 text-left font-medium">Klassen</th>
                <th className="px-4 py-2 text-left font-medium">Properties</th>
                <th className="px-4 py-2 text-right font-medium">Aktion</th>
              </tr>
            </thead>
            <tbody>
              {ontologies.map((o) => (
                <tr key={o.ontologyKey} className="border-t border-border">
                  <td className="px-4 py-2 font-mono text-xs">{o.ontologyKey}</td>
                  <td className="px-4 py-2">{o.ontology?.name ?? "—"}</td>
                  <td className="px-4 py-2"><span className="rounded-full bg-secondary px-2 py-0.5 text-xs">{o.role}</span></td>
                  <td className="px-4 py-2">{o.ontology?.classCount ?? 0}</td>
                  <td className="px-4 py-2">{(o.ontology?.objectPropertyCount ?? 0) + (o.ontology?.datatypePropertyCount ?? 0)}</td>
                  <td className="px-4 py-2 text-right">
                    <button onClick={() => handleUnassign(o.ontologyKey)} className="text-xs text-red-400 hover:text-red-300">Entfernen</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {showUpload && <UploadOntologyDialog collectionId={collectionId} onClose={() => setShowUpload(false)} onSuccess={handleSuccess} />}
      {showAssign && <AssignOntologyDialog collectionId={collectionId} existingKeys={ontologies.map((o) => o.ontologyKey)} onClose={() => setShowAssign(false)} onSuccess={handleSuccess} />}
    </section>
  );
}
