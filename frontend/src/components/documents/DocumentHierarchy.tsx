"use client";

import Link from "next/link";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DocumentSummary } from "@/types/document";

interface Props {
  parentId: string | null;
  childDocuments: DocumentSummary[];
}

export function DocumentHierarchy({ parentId, childDocuments }: Props) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Hierarchie</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2 text-sm">
        {parentId && (
          <div>
            Eltern:{" "}
            <Link
              href={`/documents/${parentId}`}
              className="font-mono hover:underline"
            >
              {parentId}
            </Link>
          </div>
        )}
        {childDocuments.length === 0 ? (
          <p className="text-muted-foreground">Keine Kinder.</p>
        ) : (
          <ul className="space-y-1">
            {childDocuments.map((c) => (
              <li key={c.id}>
                <Link
                  href={`/documents/${c.id}`}
                  className="hover:underline"
                >
                  {c.type} — {c.title}
                </Link>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
