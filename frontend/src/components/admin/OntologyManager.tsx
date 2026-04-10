"use client";

import { useRef, useState } from "react";
import { useMutation, useQuery } from "@apollo/client/react";
import { toast } from "sonner";
import {
  DELETE_ONTOLOGY_MUTATION,
  IMPORT_ONTOLOGY_MUTATION,
  LIST_ONTOLOGIES_QUERY,
} from "@/graphql/admin";
import { OntologyInfo } from "@/types/admin";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { DeleteConfirmDialog } from "./DeleteConfirmDialog";

interface ListOntologiesData {
  listOntologies: OntologyInfo[];
}

export function OntologyManager() {
  const { data, loading, error } = useQuery<ListOntologiesData>(
    LIST_ONTOLOGIES_QUERY,
  );

  const [importOntology, { loading: importing }] = useMutation(
    IMPORT_ONTOLOGY_MUTATION,
    { refetchQueries: [{ query: LIST_ONTOLOGIES_QUERY }] },
  );
  const [deleteOntology] = useMutation(DELETE_ONTOLOGY_MUTATION, {
    refetchQueries: [{ query: LIST_ONTOLOGIES_QUERY }],
  });

  const fileInputRef = useRef<HTMLInputElement>(null);
  const [deleting, setDeleting] = useState<OntologyInfo | null>(null);

  async function handleFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const text = await file.text();
      const content = btoa(unescape(encodeURIComponent(text)));
      const format = file.name.endsWith(".rdf") ? "RDFXML" : "TURTLE";
      const key = file.name.replace(/\.[^.]+$/, "");
      await importOntology({
        variables: {
          input: {
            key,
            content,
            format,
            name: file.name,
            namespace: "http://example.org/ontology/",
          },
        },
      });
      toast.success(`Ontologie "${key}" importiert`);
    } catch (err) {
      toast.error(`Import fehlgeschlagen: ${(err as Error).message}`);
    } finally {
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  }

  async function handleDelete() {
    if (!deleting) return;
    try {
      await deleteOntology({ variables: { key: deleting.key } });
      toast.success(`Ontologie "${deleting.key}" gelöscht`);
    } catch (err) {
      toast.error(`Fehler: ${(err as Error).message}`);
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">
          {data ? `${data.listOntologies.length} Ontologie(n)` : ""}
        </p>
        <Button
          onClick={() => fileInputRef.current?.click()}
          disabled={importing}
        >
          {importing ? "Importiere…" : "Ontologie importieren"}
        </Button>
        <input
          ref={fileInputRef}
          type="file"
          accept=".ttl,.rdf"
          className="hidden"
          onChange={handleFile}
        />
      </div>

      {loading && !data && (
        <div className="space-y-2">
          <Skeleton className="h-9 w-full" />
          <Skeleton className="h-9 w-full" />
        </div>
      )}

      {error && (
        <Alert variant="destructive">
          <AlertTitle>Fehler</AlertTitle>
          <AlertDescription>{error.message}</AlertDescription>
        </Alert>
      )}

      {data && data.listOntologies.length === 0 && (
        <p className="text-muted-foreground">
          Noch keine Ontologien importiert.
        </p>
      )}

      {data && data.listOntologies.length > 0 && (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Key</TableHead>
              <TableHead>Name</TableHead>
              <TableHead>Namespace</TableHead>
              <TableHead>Version</TableHead>
              <TableHead>Klassen</TableHead>
              <TableHead>Obj. Properties</TableHead>
              <TableHead>Datatype Properties</TableHead>
              <TableHead>Aktionen</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.listOntologies.map((o) => (
              <TableRow key={o.key}>
                <TableCell className="font-mono text-sm">{o.key}</TableCell>
                <TableCell>{o.name}</TableCell>
                <TableCell className="font-mono text-xs text-muted-foreground">
                  {o.namespace}
                </TableCell>
                <TableCell>{o.version}</TableCell>
                <TableCell>{o.classCount}</TableCell>
                <TableCell>{o.objectPropertyCount}</TableCell>
                <TableCell>{o.datatypePropertyCount}</TableCell>
                <TableCell>
                  <Button
                    size="sm"
                    variant="destructive"
                    onClick={() => setDeleting(o)}
                  >
                    Löschen
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      <DeleteConfirmDialog
        open={!!deleting}
        onOpenChange={(o) => !o && setDeleting(null)}
        title="Ontologie löschen?"
        description={`Ontologie "${deleting?.key ?? ""}" wird unwiderruflich gelöscht.`}
        onConfirm={handleDelete}
      />
    </div>
  );
}
