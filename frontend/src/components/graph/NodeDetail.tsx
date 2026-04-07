"use client";

import { useQuery } from "@apollo/client/react";
import { GRAPH_TRIPLES_QUERY } from "@/graphql/graph";
import { GraphNode, QuadDto } from "@/types/graph";

interface NodeDetailProps {
  node: GraphNode;
  collectionId: string;
  onExpand: (entityUri: string) => void;
  onClose: () => void;
}

interface TriplesData {
  triples: QuadDto[];
}

export function NodeDetail({ node, collectionId, onExpand, onClose }: NodeDetailProps) {
  const { data, loading } = useQuery<TriplesData>(GRAPH_TRIPLES_QUERY, {
    variables: {
      collectionId,
      subject: node.id,
      predicate: null,
      object: null,
      dataset: null,
      limit: 50,
    },
  });

  return (
    <aside className="w-96 overflow-y-auto border-l border-border bg-card p-4 text-card-foreground">
      <div className="mb-4 flex items-center justify-between">
        <h3 className="truncate font-semibold">{node.label}</h3>
        <button
          onClick={onClose}
          className="text-muted-foreground hover:text-foreground"
          aria-label="Schließen"
        >
          ×
        </button>
      </div>

      <div className="mb-4">
        <span className="text-xs uppercase text-muted-foreground">{node.type}</span>
        <p className="break-all text-sm text-foreground">{node.id}</p>
      </div>

      {!node.expanded && (
        <button
          onClick={() => onExpand(node.id)}
          className="mb-4 rounded-md bg-primary px-3 py-1 text-sm text-primary-foreground hover:bg-primary/90"
        >
          Nachbarn laden
        </button>
      )}

      <h4 className="mb-2 text-sm font-medium">
        Triples ({data?.triples?.length ?? 0})
      </h4>
      {loading ? (
        <p className="text-sm text-muted-foreground">Laden...</p>
      ) : (
        <div className="space-y-2">
          {data?.triples?.map((t, i) => (
            <div key={i} className="rounded-md border border-border bg-muted/30 p-2 text-xs text-foreground">
              <div>
                <span className="font-medium">Prädikat:</span> {t.predicate}
              </div>
              <div>
                <span className="font-medium">Objekt:</span> {t.object}
              </div>
              <div className="text-muted-foreground">Dataset: {t.dataset}</div>
            </div>
          ))}
        </div>
      )}
    </aside>
  );
}
