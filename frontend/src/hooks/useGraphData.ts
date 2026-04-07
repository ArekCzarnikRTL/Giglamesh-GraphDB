"use client";

import { useState, useCallback } from "react";
import { useApolloClient } from "@apollo/client/react";
import {
  GRAPH_TRIPLES_QUERY,
  NODE_NEIGHBORS_QUERY,
} from "@/graphql/graph";
import { GraphData, GraphFilter, QuadDto } from "@/types/graph";
import { mergeGraphData, quadsToGraphData } from "@/lib/graph/transforms";

const INITIAL_LIMIT = 500;
const NEIGHBOR_LIMIT = 50;
const RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

export function useGraphData(collectionId: string) {
  const client = useApolloClient();
  const [graphData, setGraphData] = useState<GraphData>({ nodes: [], links: [] });

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
      let next = quadsToGraphData(data?.triples ?? []);
      if (filter.entityTypes.length > 0) {
        next = filterByEntityTypes(next, data?.triples ?? [], filter.entityTypes);
      }
      setGraphData(next);
    },
    [client, collectionId]
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
      const all = [...(data?.asSubject ?? []), ...(data?.asObject ?? [])];
      const incoming = quadsToGraphData(all);
      setGraphData((prev) => mergeGraphData(prev, incoming, entityUri));
    },
    [client, collectionId]
  );

  const clear = useCallback(() => {
    setGraphData({ nodes: [], links: [] });
  }, []);

  return { graphData, loadInitial, expandNode, clear };
}

function filterByEntityTypes(
  data: GraphData,
  quads: QuadDto[],
  entityTypes: string[]
): GraphData {
  const types = new Set(entityTypes);
  const allowedSubjects = new Set(
    quads.filter((q) => q.predicate === RDF_TYPE && types.has(q.object)).map((q) => q.subject)
  );
  if (allowedSubjects.size === 0) return data;

  const resolveId = (ref: string | { id: string }): string =>
    typeof ref === "string" ? ref : ref.id;
  const links = data.links.filter((l) =>
    allowedSubjects.has(resolveId(l.source as string | { id: string }))
  );
  const liveNodeIds = new Set<string>();
  links.forEach((l) => {
    liveNodeIds.add(resolveId(l.source as string | { id: string }));
    liveNodeIds.add(resolveId(l.target as string | { id: string }));
  });
  // also keep allowed subjects even if they have no surviving edges (so the user sees them)
  allowedSubjects.forEach((s) => liveNodeIds.add(s));
  const nodes = data.nodes.filter((n) => liveNodeIds.has(n.id));
  return { nodes, links };
}
