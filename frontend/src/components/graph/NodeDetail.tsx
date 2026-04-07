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
    <aside className="w-96 border-l bg-white p-4 overflow-y-auto">
      <div className="flex justify-between items-center mb-4">
        <h3 className="font-semibold truncate">{node.label}</h3>
        <button
          onClick={onClose}
          className="text-gray-500 hover:text-gray-700"
          aria-label="Schließen"
        >
          ×
        </button>
      </div>

      <div className="mb-4">
        <span className="text-xs uppercase text-gray-500">{node.type}</span>
        <p className="text-sm text-gray-700 break-all">{node.id}</p>
      </div>

      {!node.expanded && (
        <button
          onClick={() => onExpand(node.id)}
          className="mb-4 px-3 py-1 text-sm bg-blue-600 text-white rounded hover:bg-blue-700"
        >
          Nachbarn laden
        </button>
      )}

      <h4 className="font-medium text-sm mb-2">
        Triples ({data?.triples?.length ?? 0})
      </h4>
      {loading ? (
        <p className="text-sm text-gray-500">Laden...</p>
      ) : (
        <div className="space-y-2">
          {data?.triples?.map((t, i) => (
            <div key={i} className="text-xs border rounded p-2 bg-gray-50">
              <div>
                <span className="font-medium">Prädikat:</span> {t.predicate}
              </div>
              <div>
                <span className="font-medium">Objekt:</span> {t.object}
              </div>
              <div className="text-gray-400">Dataset: {t.dataset}</div>
            </div>
          ))}
        </div>
      )}
    </aside>
  );
}
