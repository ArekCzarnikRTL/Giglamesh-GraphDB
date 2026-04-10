"use client";

import { useState } from "react";
import { useMutation } from "@apollo/client/react";
import { toast } from "sonner";
import { PURGE_ALL_DATA_MUTATION, ADMIN_COLLECTIONS_QUERY } from "@/graphql/admin";
import { PurgeResult } from "@/types/admin";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";

interface PurgeData {
  purgeAllData: PurgeResult;
}

export function PurgePanel() {
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [confirmText, setConfirmText] = useState("");
  const [lastResult, setLastResult] = useState<PurgeResult | null>(null);

  const [purge, { loading }] = useMutation<PurgeData>(
    PURGE_ALL_DATA_MUTATION,
    {
      refetchQueries: [{ query: ADMIN_COLLECTIONS_QUERY }],
    },
  );

  async function handlePurge() {
    try {
      const { data } = await purge();
      if (data) {
        setLastResult(data.purgeAllData);
        const r = data.purgeAllData;
        toast.success(
          `Purge abgeschlossen: ${r.collectionsDeleted} Collections, ${r.documentsDeleted} Dokumente, ${r.ontologiesDeleted} Ontologien, ${r.kafkaTopicsDeleted} Kafka-Topics gelöscht (${r.durationMs}ms)`,
        );
      }
    } catch (err) {
      toast.error(`Purge fehlgeschlagen: ${(err as Error).message}`);
    } finally {
      setConfirmOpen(false);
      setConfirmText("");
    }
  }

  return (
    <div className="space-y-4 rounded-lg border border-destructive/30 bg-destructive/5 p-4">
      <div>
        <h3 className="text-lg font-semibold text-destructive">
          Daten-Purge
        </h3>
        <p className="text-sm text-muted-foreground">
          Löscht alle inhaltlichen Daten: Collections (inkl. Quads, Vektoren,
          Blobs), Dokumente, Ontologien und Kafka-Topics. Konfiguration bleibt
          erhalten.
        </p>
      </div>

      <Button
        variant="destructive"
        onClick={() => setConfirmOpen(true)}
        disabled={loading}
      >
        {loading ? "Purge läuft…" : "Alle Daten löschen"}
      </Button>

      {lastResult && (
        <Alert>
          <AlertTitle>Letzter Purge</AlertTitle>
          <AlertDescription>
            {lastResult.collectionsDeleted} Collections,{" "}
            {lastResult.documentsDeleted} Dokumente,{" "}
            {lastResult.ontologiesDeleted} Ontologien,{" "}
            {lastResult.kafkaTopicsDeleted} Kafka-Topics gelöscht
            ({lastResult.durationMs}ms)
          </AlertDescription>
        </Alert>
      )}

      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Alle Daten unwiderruflich löschen?</DialogTitle>
            <DialogDescription>
              Diese Aktion löscht alle Collections, Dokumente, RDF-Triples,
              Vektoren, Blobs, Ontologien und Kafka-Topics. Dieser Vorgang kann
              nicht rückgängig gemacht werden.
            </DialogDescription>
          </DialogHeader>
          <div>
            <p className="mb-2 text-sm text-muted-foreground">
              Tippe <strong>PURGE</strong> zur Bestätigung:
            </p>
            <Input
              value={confirmText}
              onChange={(e) => setConfirmText(e.target.value)}
              placeholder="PURGE"
            />
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => {
                setConfirmOpen(false);
                setConfirmText("");
              }}
            >
              Abbrechen
            </Button>
            <Button
              variant="destructive"
              disabled={confirmText !== "PURGE" || loading}
              onClick={handlePurge}
            >
              {loading ? "Lösche…" : "Endgültig löschen"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
