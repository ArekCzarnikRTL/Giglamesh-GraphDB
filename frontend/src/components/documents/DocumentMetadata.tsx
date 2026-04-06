"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DocumentDetail as DocDetail } from "@/types/document";

export function DocumentMetadata({ document }: { document: DocDetail }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Metadaten</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2 text-sm">
        <Row label="ID" value={document.id} />
        <Row label="Titel" value={document.title} />
        <Row label="Typ" value={document.type} />
        <Row label="Status" value={document.state} />
        <Row label="MIME" value={document.mimeType} />
        <Row
          label="Erstellt"
          value={new Date(document.createdAt).toLocaleString("de-DE")}
        />
        {document.metadata.map((m) => (
          <Row key={m.key} label={m.key} value={m.value} />
        ))}
      </CardContent>
    </Card>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="grid grid-cols-3 gap-2">
      <span className="text-muted-foreground">{label}</span>
      <span className="col-span-2 font-mono">{value}</span>
    </div>
  );
}
