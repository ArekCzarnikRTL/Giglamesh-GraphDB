"use client";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { NlpQueryPayload } from "@/types/query";

interface Props {
  payload: NlpQueryPayload;
}

export function NlpQueryMeta({ payload }: Props) {
  const { detectedIntent, wasReformulated, effectiveQuestion, sources } =
    payload;
  return (
    <div className="space-y-3">
      <Card>
        <CardContent className="space-y-2 p-3 text-sm">
          <div className="flex items-center justify-between">
            <Badge variant="secondary">{detectedIntent.intent}</Badge>
            <span className="text-xs text-muted-foreground">
              {Math.round(detectedIntent.confidence * 100)}%
            </span>
          </div>
          <p className="text-xs italic text-muted-foreground">
            {detectedIntent.reasoning}
          </p>
        </CardContent>
      </Card>

      {wasReformulated && (
        <Card>
          <CardContent className="space-y-1 p-3 text-sm">
            <p className="text-xs text-muted-foreground">Reformuliert als:</p>
            <p>{effectiveQuestion}</p>
          </CardContent>
        </Card>
      )}

      {sources.length > 0 && (
        <Card>
          <CardContent className="space-y-1 p-3 text-sm">
            <p className="text-xs text-muted-foreground">Quellen</p>
            <ul className="list-disc space-y-1 pl-4">
              {sources.map((s, i) => (
                <li key={`${s}-${i}`}>{s}</li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
