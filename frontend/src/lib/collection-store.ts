"use client";

import { useEffect, useState, useCallback } from "react";

const STORAGE_KEY = "graphmesh.activeCollectionId";

export function useActiveCollection() {
  const [collectionId, setCollectionIdState] = useState<string | null>(null);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (stored) setCollectionIdState(stored);
    setHydrated(true);
  }, []);

  const setCollectionId = useCallback((id: string | null) => {
    setCollectionIdState(id);
    if (id) {
      window.localStorage.setItem(STORAGE_KEY, id);
    } else {
      window.localStorage.removeItem(STORAGE_KEY);
    }
  }, []);

  return { collectionId, setCollectionId, hydrated };
}
