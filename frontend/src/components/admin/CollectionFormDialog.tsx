"use client";

import { useEffect, useState } from "react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { AdminCollection } from "@/types/admin";

export interface CollectionFormValues {
  name: string;
  description: string;
  tags: string[];
}

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  initial?: AdminCollection | null;
  onSubmit: (values: CollectionFormValues) => Promise<void> | void;
}

export function CollectionFormDialog({
  open,
  onOpenChange,
  initial,
  onSubmit,
}: Props) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [tagsText, setTagsText] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (open) {
      setName(initial?.name ?? "");
      setDescription(initial?.description ?? "");
      setTagsText((initial?.tags ?? []).join(", "));
    }
  }, [open, initial]);

  const handleSubmit = async () => {
    setSubmitting(true);
    try {
      const tags = tagsText
        .split(",")
        .map((t) => t.trim())
        .filter((t) => t.length > 0);
      await onSubmit({ name, description, tags });
      onOpenChange(false);
    } finally {
      setSubmitting(false);
    }
  };

  const isEdit = !!initial;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            {isEdit ? "Collection bearbeiten" : "Neue Collection"}
          </DialogTitle>
          <DialogDescription>
            {isEdit
              ? "Aktualisiere Name, Beschreibung oder Tags."
              : "Lege eine neue Collection an."}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="collection-name">Name</Label>
            <Input
              id="collection-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="collection-description">Beschreibung</Label>
            <Input
              id="collection-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="collection-tags">Tags (kommagetrennt)</Label>
            <Input
              id="collection-tags"
              value={tagsText}
              onChange={(e) => setTagsText(e.target.value)}
            />
          </div>
        </div>
        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={submitting}
          >
            Abbrechen
          </Button>
          <Button onClick={handleSubmit} disabled={submitting || !name.trim()}>
            {isEdit ? "Speichern" : "Erstellen"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
