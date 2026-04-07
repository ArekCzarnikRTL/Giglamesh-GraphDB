"use client";

import Link from "next/link";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { PipelineDocument } from "@/types/admin";

interface Props {
  title: string;
  documents: PipelineDocument[];
  emptyMessage: string;
}

export function PipelineDocumentList({ title, documents, emptyMessage }: Props) {
  return (
    <section className="space-y-2">
      <h2 className="text-lg font-semibold">
        {title} ({documents.length})
      </h2>
      {documents.length === 0 ? (
        <p className="text-sm text-muted-foreground">{emptyMessage}</p>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Titel</TableHead>
              <TableHead>Erstellt</TableHead>
              <TableHead>Aktionen</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {documents.map((doc) => (
              <TableRow key={doc.id}>
                <TableCell className="font-medium">{doc.title}</TableCell>
                <TableCell className="text-sm text-muted-foreground">
                  {doc.createdAt}
                </TableCell>
                <TableCell>
                  <Link
                    href={`/documents/${doc.id}`}
                    className="text-sm text-blue-600 hover:underline"
                  >
                    Öffnen
                  </Link>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </section>
  );
}
