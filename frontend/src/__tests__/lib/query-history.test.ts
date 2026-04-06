import { describe, it, expect, beforeEach } from "vitest";
import {
  saveToHistory,
  getHistory,
  clearHistory,
  MAX_HISTORY_ENTRIES,
} from "@/lib/query-history";

describe("query-history", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("returns an empty array when nothing is stored", () => {
    expect(getHistory()).toEqual([]);
  });

  it("saves and retrieves an entry", () => {
    saveToHistory({
      query: "What is photosynthesis?",
      mode: "graph-rag",
      collectionId: "col-1",
    });
    const history = getHistory();
    expect(history).toHaveLength(1);
    expect(history[0].query).toBe("What is photosynthesis?");
    expect(history[0].mode).toBe("graph-rag");
    expect(history[0].collectionId).toBe("col-1");
    expect(history[0].id).toBeTypeOf("string");
    expect(history[0].timestamp).toBeTypeOf("number");
  });

  it("orders entries newest first", () => {
    saveToHistory({ query: "first", mode: "auto", collectionId: "c" });
    saveToHistory({ query: "second", mode: "auto", collectionId: "c" });
    const history = getHistory();
    expect(history[0].query).toBe("second");
    expect(history[1].query).toBe("first");
  });

  it("limits history to MAX_HISTORY_ENTRIES", () => {
    for (let i = 0; i < MAX_HISTORY_ENTRIES + 10; i++) {
      saveToHistory({ query: `q${i}`, mode: "auto", collectionId: "c" });
    }
    expect(getHistory()).toHaveLength(MAX_HISTORY_ENTRIES);
  });

  it("clears all entries", () => {
    saveToHistory({ query: "x", mode: "auto", collectionId: "c" });
    clearHistory();
    expect(getHistory()).toEqual([]);
  });
});
