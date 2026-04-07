"use client";

import { useState } from "react";
import { useMutation, useQuery } from "@apollo/client/react";
import { toast } from "sonner";
import {
  ADMIN_COLLECTIONS_QUERY,
  CREATE_COLLECTION_MUTATION,
  UPDATE_COLLECTION_MUTATION,
  DELETE_COLLECTION_MUTATION,
} from "@/graphql/admin";
import { AdminCollection } from "@/types/admin";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
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
import {
  CollectionFormDialog,
  CollectionFormValues,
} from "./CollectionFormDialog";
import { DeleteConfirmDialog } from "./DeleteConfirmDialog";

interface CollectionsData {
  collections: AdminCollection[];
}

export function CollectionManager() {
  const { data, loading, error } = useQuery<CollectionsData>(
    ADMIN_COLLECTIONS_QUERY,
  );

  const [createCollection] = useMutation(CREATE_COLLECTION_MUTATION, {
    refetchQueries: [{ query: ADMIN_COLLECTIONS_QUERY }],
  });
  const [updateCollection] = useMutation(UPDATE_COLLECTION_MUTATION, {
    refetchQueries: [{ query: ADMIN_COLLECTIONS_QUERY }],
  });
  const [deleteCollection] = useMutation(DELETE_COLLECTION_MUTATION, {
    refetchQueries: [{ query: ADMIN_COLLECTIONS_QUERY }],
  });

  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<AdminCollection | null>(null);
  const [deleting, setDeleting] = useState<AdminCollection | null>(null);

  const handleCreate = async (values: CollectionFormValues) => {
    try {
      await createCollection({ variables: { input: values } });
      toast.success("Collection erstellt");
    } catch (err) {
      toast.error(`Fehler: ${(err as Error).message}`);
    }
  };

  const handleUpdate = async (values: CollectionFormValues) => {
    if (!editing) return;
    try {
      await updateCollection({
        variables: { id: editing.id, input: values },
      });
      toast.success("Collection aktualisiert");
    } catch (err) {
      toast.error(`Fehler: ${(err as Error).message}`);
    }
  };

  const handleDelete = async () => {
    if (!deleting) return;
    try {
      await deleteCollection({ variables: { id: deleting.id } });
      toast.success("Collection gelöscht");
    } catch (err) {
      toast.error(`Fehler: ${(err as Error).message}`);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">
          {data ? `${data.collections.length} Collections` : ""}
        </p>
        <Button onClick={() => setCreateOpen(true)}>Neue Collection</Button>
      </div>

      {loading && !data && (
        <div className="space-y-2">
          <Skeleton className="h-9 w-full" />
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

      {data && data.collections.length === 0 && (
        <p className="text-muted-foreground">Noch keine Collections.</p>
      )}

      {data && data.collections.length > 0 && (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead>Beschreibung</TableHead>
              <TableHead>Tags</TableHead>
              <TableHead>Erstellt</TableHead>
              <TableHead>Aktionen</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.collections.map((c) => (
              <TableRow key={c.id}>
                <TableCell className="font-medium">{c.name}</TableCell>
                <TableCell className="text-sm text-muted-foreground">
                  {c.description ?? "—"}
                </TableCell>
                <TableCell>
                  <div className="flex flex-wrap gap-1">
                    {c.tags.map((t) => (
                      <Badge key={t} variant="secondary">
                        {t}
                      </Badge>
                    ))}
                  </div>
                </TableCell>
                <TableCell className="text-sm text-muted-foreground">
                  {c.createdAt}
                </TableCell>
                <TableCell>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => setEditing(c)}
                    >
                      Bearbeiten
                    </Button>
                    <Button
                      size="sm"
                      variant="destructive"
                      onClick={() => setDeleting(c)}
                    >
                      Löschen
                    </Button>
                  </div>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      <CollectionFormDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onSubmit={handleCreate}
      />

      <CollectionFormDialog
        open={!!editing}
        onOpenChange={(o) => !o && setEditing(null)}
        initial={editing}
        onSubmit={handleUpdate}
      />

      <DeleteConfirmDialog
        open={!!deleting}
        onOpenChange={(o) => !o && setDeleting(null)}
        title="Collection löschen?"
        description={`Die Collection "${deleting?.name ?? ""}" und alle zugehörigen Dokumente werden gelöscht.`}
        onConfirm={handleDelete}
      />
    </div>
  );
}
