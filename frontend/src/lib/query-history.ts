import { HistoryEntry, QueryMode } from "@/types/query";

const STORAGE_KEY = "graphmesh.query-history";
export const MAX_HISTORY_ENTRIES = 100;

export function saveToHistory(entry: {
  query: string;
  mode: QueryMode;
  collectionId: string;
}): void {
  if (typeof window === "undefined") return;
  const history = getHistory();
  const newEntry: HistoryEntry = {
    ...entry,
    id:
      typeof crypto !== "undefined" && "randomUUID" in crypto
        ? crypto.randomUUID()
        : `${Date.now()}-${Math.random().toString(36).slice(2)}`,
    timestamp: Date.now(),
  };
  const updated = [newEntry, ...history].slice(0, MAX_HISTORY_ENTRIES);
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(updated));
}

export function getHistory(): HistoryEntry[] {
  if (typeof window === "undefined") return [];
  const stored = window.localStorage.getItem(STORAGE_KEY);
  if (!stored) return [];
  try {
    const parsed = JSON.parse(stored);
    return Array.isArray(parsed) ? (parsed as HistoryEntry[]) : [];
  } catch {
    return [];
  }
}

export function clearHistory(): void {
  if (typeof window === "undefined") return;
  window.localStorage.removeItem(STORAGE_KEY);
}
