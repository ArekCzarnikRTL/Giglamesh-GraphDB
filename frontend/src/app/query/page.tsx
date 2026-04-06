"use client";

import { useState } from "react";
import { CollectionSelector } from "@/components/documents/CollectionSelector";
import { useActiveCollection } from "@/lib/collection-store";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { QueryChat } from "@/components/query/QueryChat";
import { QueryHistory } from "@/components/query/QueryHistory";
import { HistoryEntry } from "@/types/query";

export default function QueryPage() {
  const { collectionId, hydrated } = useActiveCollection();
  const [activeChatCollection, setActiveChatCollection] = useState<
    string | null
  >(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [pendingCollection, setPendingCollection] = useState<string | null>(
    null,
  );
  const [chatHasMessages, setChatHasMessages] = useState(false);

  if (hydrated && activeChatCollection === null && collectionId) {
    setActiveChatCollection(collectionId);
  }

  if (
    hydrated &&
    collectionId &&
    activeChatCollection &&
    collectionId !== activeChatCollection &&
    pendingCollection === null
  ) {
    if (chatHasMessages) {
      setPendingCollection(collectionId);
    } else {
      setActiveChatCollection(collectionId);
    }
  }

  const confirmSwitch = () => {
    if (pendingCollection) {
      setActiveChatCollection(pendingCollection);
      setPendingCollection(null);
      setChatHasMessages(false);
    }
  };

  const cancelSwitch = () => {
    setPendingCollection(null);
  };

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const handleHistorySelect = (_entry: HistoryEntry) => {
    setHistoryOpen(false);
  };

  return (
    <>
      <header className="flex items-center gap-4 border-b p-4">
        <h1 className="text-xl font-bold">Knowledge Base abfragen</h1>
        <CollectionSelector />
        <Button
          type="button"
          variant="ghost"
          className="ml-auto"
          onClick={() => setHistoryOpen((v) => !v)}
        >
          Verlauf
        </Button>
      </header>
      <div className="flex flex-1 overflow-hidden">
        {historyOpen && <QueryHistory onSelect={handleHistorySelect} />}
        {activeChatCollection ? (
          <QueryChatWithSensor
            key={activeChatCollection}
            collectionId={activeChatCollection}
            onMessagesChange={setChatHasMessages}
          />
        ) : (
          <div className="flex flex-1 items-center justify-center text-muted-foreground">
            Bitte eine Collection auswaehlen.
          </div>
        )}
      </div>

      <Dialog
        open={pendingCollection !== null}
        onOpenChange={(open) => {
          if (!open) cancelSwitch();
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Collection wechseln?</DialogTitle>
            <DialogDescription>
              Der aktuelle Chat geht dabei verloren. Fortfahren?
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="ghost" onClick={cancelSwitch}>
              Abbrechen
            </Button>
            <Button onClick={confirmSwitch}>Fortfahren</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

function QueryChatWithSensor({
  collectionId,
  onMessagesChange,
}: {
  collectionId: string;
  onMessagesChange: (has: boolean) => void;
}) {
  if (typeof window !== "undefined") {
    queueMicrotask(() => onMessagesChange(false));
  }
  return <QueryChat collectionId={collectionId} />;
}
