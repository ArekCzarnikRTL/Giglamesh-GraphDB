"use client";

import { useState } from "react";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { ConfigEntry } from "@/types/admin";

interface Props {
  item: ConfigEntry;
  onSave: (value: string) => Promise<void>;
}

export function ConfigItemCard({ item, onSave }: Props) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(item.value);
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    setSaving(true);
    try {
      await onSave(draft);
      setEditing(false);
    } finally {
      setSaving(false);
    }
  };

  const handleCancel = () => {
    setDraft(item.value);
    setEditing(false);
  };

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between space-y-0 pb-2">
        <div>
          <div className="flex items-center gap-2">
            <h4 className="font-medium">{item.key}</h4>
            <Badge variant="outline">{item.type}</Badge>
          </div>
          <p className="text-xs text-muted-foreground">Version {item.version}</p>
        </div>
        {!editing && (
          <Button size="sm" variant="outline" onClick={() => setEditing(true)}>
            Bearbeiten
          </Button>
        )}
      </CardHeader>
      <CardContent>
        {editing ? (
          <div className="space-y-2">
            <textarea
              className="h-48 w-full rounded-md border bg-background p-2 font-mono text-sm"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
            />
            <div className="flex gap-2">
              <Button size="sm" onClick={handleSave} disabled={saving}>
                Speichern
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={handleCancel}
                disabled={saving}
              >
                Abbrechen
              </Button>
            </div>
          </div>
        ) : (
          <pre className="overflow-x-auto rounded-md bg-muted p-2 text-sm">
            {item.value}
          </pre>
        )}
      </CardContent>
    </Card>
  );
}
