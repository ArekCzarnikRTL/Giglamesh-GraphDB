"use client";

import { useCallback, useMemo, useRef, useState } from "react";
import { useApolloClient } from "@apollo/client/react";
import Graph from "graphology";

import { GRAPH_TRIPLES_QUERY, NODE_NEIGHBORS_QUERY } from "@/graphql/graph";
import {
  applyEntityTypeFilter,
  createEmptyGraph,
  quadsToGraphologyGraph,
} from "@/lib/graph/transforms";
import {
  EdgeAttributes,
  GraphFilter,
  NodeAttributes,
  QuadDto,
} from "@/types/graph";

const INITIAL_LIMIT = 500;
const NEIGHBOR_LIMIT = 50;

export interface UseGraphDataResult {
  graph: Graph<NodeAttributes, EdgeAttributes>;
  version: number;
  loadInitial: (filter: GraphFilter) => Promise<void>;
  expandNode: (entityUri: string) => Promise<void>;
  clear: () => void;
}

export function useGraphData(collectionId: string): UseGraphDataResult {
  const client = useApolloClient();
  const graphRef = useRef<Graph<NodeAttributes, EdgeAttributes>>();
  if (!graphRef.current) graphRef.current = createEmptyGraph();
  const [version, setVersion] = useState(0);

  const bump = useCallback(() => setVersion((v) => v + 1), []);

  const loadInitial = useCallback(
    async (filter: GraphFilter): Promise<void> => {
      if (!collectionId) return;
      const { data } = await client.query<{ triples: QuadDto[] }>({
        query: GRAPH_TRIPLES_QUERY,
        variables: {
          collectionId,
          subject: null,
          predicate: filter.predicates.length === 1 ? filter.predicates[0] : null,
          object: null,
          dataset: filter.datasets.length === 1 ? filter.datasets[0] : null,
          limit: INITIAL_LIMIT,
        },
        fetchPolicy: "network-only",
      });
      const graph = graphRef.current!;
      graph.clear();
      quadsToGraphologyGraph(data?.triples ?? [], graph);
      applyEntityTypeFilter(graph, filter.entityTypes);
      bump();
    },
    [client, collectionId, bump],
  );

  const expandNode = useCallback(
    async (entityUri: string): Promise<void> => {
      if (!collectionId) return;
      const { data } = await client.query<{
        asSubject: QuadDto[];
        asObject: QuadDto[];
      }>({
        query: NODE_NEIGHBORS_QUERY,
        variables: { collectionId, entityUri, limit: NEIGHBOR_LIMIT },
        fetchPolicy: "network-only",
      });
      const graph = graphRef.current!;
      const all = [...(data?.asSubject ?? []), ...(data?.asObject ?? [])];
      quadsToGraphologyGraph(all, graph);
      if (graph.hasNode(entityUri)) {
        graph.setNodeAttribute(entityUri, "expanded", true);
      }
      bump();
    },
    [client, collectionId, bump],
  );

  const clear = useCallback(() => {
    graphRef.current?.clear();
    bump();
  }, [bump]);

  return useMemo(
    () => ({
      graph: graphRef.current!,
      version,
      loadInitial,
      expandNode,
      clear,
    }),
    [version, loadInitial, expandNode, clear],
  );
}
