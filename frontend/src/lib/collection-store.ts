"use client";

import { useEffect, useState, useCallback } from "react";

const STORAGE_KEY = "graphmesh.activeCollectionId";
const CHANGE_EVENT = "graphmesh:collection-changed";

export function useActiveCollection() {
  const [collectionId, setCollectionIdState] = useState<string | null>(null);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (stored) setCollectionIdState(stored);
    setHydrated(true);

    const onChanged = (e: Event) => {
      const id = (e as CustomEvent<string | null>).detail;
      setCollectionIdState(id);
    };
    window.addEventListener(CHANGE_EVENT, onChanged);
    return () => window.removeEventListener(CHANGE_EVENT, onChanged);
  }, []);

  const setCollectionId = useCallback((id: string | null) => {
    setCollectionIdState(id);
    if (id) {
      window.localStorage.setItem(STORAGE_KEY, id);
    } else {
      window.localStorage.removeItem(STORAGE_KEY);
    }
    window.dispatchEvent(new CustomEvent(CHANGE_EVENT, { detail: id }));
  }, []);

  return { collectionId, setCollectionId, hydrated };
}
