"use client";

import Link from "next/link";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { DocumentRagSource } from "@/types/query";

interface Props {
  sources: DocumentRagSource[];
}

export function DocumentRagSources({ sources }: Props) {
  if (sources.length === 0) {
    return <p className="text-sm text-muted-foreground">Keine Quellen.</p>;
  }
  return (
    <div className="space-y-3">
      {sources.map((s) => (
        <Card key={s.chunkId}>
          <CardContent className="space-y-2 p-3">
            <div className="flex items-center justify-between text-xs text-muted-foreground">
              <Link
                href={`/documents/${s.documentId}`}
                className="text-sm font-medium text-primary hover:underline"
              >
                {s.documentTitle}
              </Link>
              <span>{Math.round(s.score * 100)}%</span>
            </div>
            {s.pageNumber !== null && (
              <Badge variant="secondary">Seite {s.pageNumber}</Badge>
            )}
            <p className="text-sm text-foreground/80 line-clamp-3">{s.snippet}</p>
          </CardContent>
        </Card>
      ))}
    </div>
  );
}
