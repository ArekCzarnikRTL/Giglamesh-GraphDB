"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@apollo/client/react";
import { GraphCanvas, GraphCanvasHandle } from "@/components/graph/GraphCanvas";
import { GraphControls, DEFAULT_LAYOUT } from "@/components/graph/GraphControls";
import { GraphFilter } from "@/components/graph/GraphFilter";
import { NodeDetail } from "@/components/graph/NodeDetail";
import { EntitySearch } from "@/components/graph/EntitySearch";
import { CollectionSelector } from "@/components/documents/CollectionSelector";
import { useGraphData } from "@/hooks/useGraphData";
import { useActiveCollection } from "@/lib/collection-store";
import { GRAPH_METADATA_QUERY } from "@/graphql/graph";
import { GraphFilter as FilterType, GraphNode, LayoutConfig } from "@/types/graph";

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

  const [selectedNode, setSelectedNode] = useState<GraphNode | null>(null);
  const [layoutConfig, setLayoutConfig] = useState<LayoutConfig>(DEFAULT_LAYOUT);
  const [filter, setFilter] = useState<FilterType>({
    datasets: [],
    predicates: [],
    entityTypes: [],
  });

  const canvasRef = useRef<GraphCanvasHandle | null>(null);
  const { graphData, loadInitial, expandNode } = useGraphData(collectionId ?? "");

  const { data: metaData } = useQuery<MetaData>(GRAPH_METADATA_QUERY, {
    variables: { collectionId },
    skip: !collectionId,
  });

  useEffect(() => {
    if (!collectionId) return;
    let cancelled = false;
    void (async () => {
      await loadInitial(filter);
      if (cancelled) return;
      if (initialEntity) {
        await expandNode(initialEntity);
        if (cancelled) return;
        canvasRef.current?.centerOnNode(initialEntity);
      }
    })();
    return () => { cancelled = true; };
    // initialEntity is intentionally read fresh per filter change; this effect runs whenever filter changes too.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [collectionId, filter, loadInitial, expandNode, initialEntity]);

  return (
    <main className="h-screen flex flex-col">
      <header className="flex items-center gap-4 p-4 border-b">
        <h1 className="text-xl font-bold">Graph Explorer</h1>
        <CollectionSelector />
        {collectionId && (
          <EntitySearch
            collectionId={collectionId}
            onSelect={(uri) => {
              void expandNode(uri).then(() => canvasRef.current?.centerOnNode(uri));
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
      <GraphControls
        config={layoutConfig}
        onChange={setLayoutConfig}
        onResetView={() => canvasRef.current?.zoomToFit(400)}
      />
      <div className="flex-1 flex overflow-hidden">
        <div className="flex-1 relative">
          <GraphCanvas
            ref={canvasRef}
            data={graphData}
            layoutConfig={layoutConfig}
            selectedNodeId={selectedNode?.id ?? null}
            onNodeClick={setSelectedNode}
            onNodeRightClick={(node) => void expandNode(node.id)}
          />
        </div>
        {selectedNode && collectionId && (
          <NodeDetail
            node={selectedNode}
            collectionId={collectionId}
            onExpand={(uri) => void expandNode(uri)}
            onClose={() => setSelectedNode(null)}
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
