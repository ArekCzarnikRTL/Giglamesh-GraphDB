"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { clearHistory, getHistory } from "@/lib/query-history";
import { HistoryEntry } from "@/types/query";

interface Props {
  onSelect: (entry: HistoryEntry) => void;
}

export function QueryHistory({ onSelect }: Props) {
  const [entries, setEntries] = useState<HistoryEntry[]>([]);

  useEffect(() => {
    setEntries(getHistory());
  }, []);

  const handleClear = () => {
    clearHistory();
    setEntries([]);
  };

  return (
    <aside className="w-72 shrink-0 border-r bg-muted/30 p-4">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold">Verlauf</h2>
        {entries.length > 0 && (
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={handleClear}
          >
            Leeren
          </Button>
        )}
      </div>
      {entries.length === 0 ? (
        <p className="text-xs text-muted-foreground">Kein Verlauf.</p>
      ) : (
        <ul className="space-y-1">
          {entries.map((e) => (
            <li key={e.id}>
              <button
                type="button"
                onClick={() => onSelect(e)}
                className="w-full rounded px-2 py-1 text-left text-sm hover:bg-muted"
              >
                <span className="block truncate">{e.query}</span>
                <span className="block text-xs text-muted-foreground">
                  {e.mode}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </aside>
  );
}
