"use client";

import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { TableCell, TableRow } from "@/components/ui/table";
import { DocumentState, DocumentSummary } from "@/types/document";

interface Props {
  document: DocumentSummary;
}

const STATE_VARIANT: Record<DocumentState, "default" | "secondary" | "destructive" | "outline"> = {
  UPLOADED: "secondary",
  PROCESSING: "secondary",
  EXTRACTED: "default",
  FAILED: "destructive",
};

export function DocumentListItem({ document }: Props) {
  return (
    <TableRow>
      <TableCell className="font-medium">
        <Link href={`/documents/${document.id}`} className="hover:underline">
          {document.title}
        </Link>
      </TableCell>
      <TableCell>{document.type}</TableCell>
      <TableCell>
        <Badge variant={STATE_VARIANT[document.state] ?? "secondary"}>
          {document.state}
        </Badge>
      </TableCell>
      <TableCell className="text-muted-foreground">{document.mimeType}</TableCell>
      <TableCell className="text-muted-foreground">
        {new Date(document.createdAt).toLocaleString("de-DE")}
      </TableCell>
    </TableRow>
  );
}
