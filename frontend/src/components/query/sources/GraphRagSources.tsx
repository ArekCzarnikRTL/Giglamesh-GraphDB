"use client";

import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { SelectedEdge } from "@/types/query";

interface Props {
  edges: SelectedEdge[];
}

export function GraphRagSources({ edges }: Props) {
  if (edges.length === 0) {
    return <p className="text-sm text-muted-foreground">Keine Quellen.</p>;
  }
  return (
    <div className="space-y-3">
      {edges.map((edge, i) => (
        <Card key={`${edge.subject}-${edge.predicate}-${i}`}>
          <CardContent className="space-y-2 p-3">
            <div className="flex items-center justify-between text-xs text-muted-foreground">
              <Badge variant="secondary">{edge.dataset}</Badge>
              <span>{Math.round(edge.relevanceScore * 100)}%</span>
            </div>
            <div className="text-sm font-medium">
              <span>{edge.subject}</span>{" "}
              <span className="text-muted-foreground">{edge.predicate}</span>{" "}
              <span>{edge.objectValue}</span>
            </div>
            {edge.reasoning && (
              <p className="text-xs italic text-muted-foreground">
                {edge.reasoning}
              </p>
            )}
          </CardContent>
        </Card>
      ))}
    </div>
  );
}
