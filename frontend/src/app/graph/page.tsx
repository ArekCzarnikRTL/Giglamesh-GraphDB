"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@apollo/client/react";

import { GraphCanvas } from "@/components/graph/GraphCanvas";
import { GraphFilter } from "@/components/graph/GraphFilter";
import { NodeDetail } from "@/components/graph/NodeDetail";
import { EntitySearch } from "@/components/graph/EntitySearch";
import { CollectionSelector } from "@/components/documents/CollectionSelector";
import { useGraphData } from "@/hooks/useGraphData";
import { useActiveCollection } from "@/lib/collection-store";
import { GRAPH_METADATA_QUERY } from "@/graphql/graph";
import { GraphFilter as FilterType, GraphNode, RdfTermType } from "@/types/graph";

interface MetaData {
  graphMetadata: {
    datasets: string[];
    predicates: string[];
    entityTypes: string[];
  };
}

function GraphPageInner() {
  const { collectionId } = useActiveCollection();
  const searchParams = useSearchParams();
  const initialEntity = searchParams.get("entity");

  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [focus, setFocus] = useState<{ id: string; nonce: number } | null>(null);
  const [filter, setFilter] = useState<FilterType>({
    datasets: [],
    predicates: [],
    entityTypes: [],
  });

  const { graph, version, loadInitial, expandNode } = useGraphData(collectionId ?? "");

  const { data: metaData } = useQuery<MetaData>(GRAPH_METADATA_QUERY, {
    variables: { collectionId },
    skip: !collectionId,
  });

  const focusOn = (id: string) => setFocus({ id, nonce: Date.now() });

  useEffect(() => {
    if (!collectionId) return;
    let cancelled = false;
    void (async () => {
      await loadInitial(filter);
      if (cancelled) return;
      if (initialEntity) {
        await expandNode(initialEntity);
        if (cancelled) return;
        focusOn(initialEntity);
      }
    })();
    return () => {
      cancelled = true;
    };
    // initialEntity is read fresh per filter change; effect runs on filter change too.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [collectionId, filter, loadInitial, expandNode, initialEntity]);

  const selectedNode: GraphNode | null =
    selectedNodeId && graph.hasNode(selectedNodeId)
      ? {
          id: selectedNodeId,
          label: graph.getNodeAttribute(selectedNodeId, "label"),
          type: graph.getNodeAttribute(selectedNodeId, "termType") as RdfTermType,
          isSubject: graph.getNodeAttribute(selectedNodeId, "isSubject"),
          expanded: graph.getNodeAttribute(selectedNodeId, "expanded"),
          size: graph.getNodeAttribute(selectedNodeId, "size"),
        }
      : null;

  return (
    <main className="h-full flex flex-col">
      <header className="flex items-center gap-4 p-4 border-b">
        <h1 className="text-xl font-bold">Graph Explorer</h1>
        <CollectionSelector />
        {collectionId && (
          <EntitySearch
            collectionId={collectionId}
            onSelect={(uri) => {
              void expandNode(uri).then(() => focusOn(uri));
            }}
          />
        )}
      </header>
      <GraphFilter
        filter={filter}
        availableDatasets={metaData?.graphMetadata?.datasets ?? []}
        availablePredicates={metaData?.graphMetadata?.predicates ?? []}
        availableTypes={metaData?.graphMetadata?.entityTypes ?? []}
        onChange={setFilter}
      />
      <div className="flex-1 flex overflow-hidden">
        <div className="flex-1 relative">
          <GraphCanvas
            graph={graph}
            version={version}
            selectedNodeId={selectedNodeId}
            focus={focus}
            onNodeClick={(id) => setSelectedNodeId(id)}
            onNodeRightClick={(id) => {
              void expandNode(id);
            }}
          />
        </div>
        {selectedNode && collectionId && (
          <NodeDetail
            node={selectedNode}
            collectionId={collectionId}
            onExpand={(uri) => void expandNode(uri)}
            onClose={() => setSelectedNodeId(null)}
          />
        )}
      </div>
    </main>
  );
}

export default function GraphPage() {
  return (
    <Suspense fallback={null}>
      <GraphPageInner />
    </Suspense>
  );
}
