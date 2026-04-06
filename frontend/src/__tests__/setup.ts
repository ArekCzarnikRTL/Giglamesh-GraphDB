import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// jsdom env under vitest 4 exposes `window.localStorage` as an empty plain
// object (no Storage prototype), so we polyfill a minimal in-memory
// implementation on both `window` and `globalThis`.
function createMemoryStorage(): Storage {
  let store: Record<string, string> = {};
  return {
    get length() {
      return Object.keys(store).length;
    },
    clear() {
      store = {};
    },
    getItem(key: string) {
      return Object.prototype.hasOwnProperty.call(store, key) ? store[key] : null;
    },
    key(index: number) {
      return Object.keys(store)[index] ?? null;
    },
    removeItem(key: string) {
      delete store[key];
    },
    setItem(key: string, value: string) {
      store[key] = String(value);
    },
  };
}

function ensureStorage(target: any, prop: "localStorage" | "sessionStorage") {
  const existing = target[prop];
  if (!existing || typeof existing.setItem !== "function") {
    Object.defineProperty(target, prop, {
      configurable: true,
      writable: true,
      value: createMemoryStorage(),
    });
  }
}

ensureStorage(globalThis, "localStorage");
ensureStorage(globalThis, "sessionStorage");
if (typeof window !== "undefined") {
  ensureStorage(window, "localStorage");
  ensureStorage(window, "sessionStorage");
}

afterEach(() => {
  cleanup();
  if (typeof window !== "undefined" && window.localStorage?.clear) {
    window.localStorage.clear();
  }
});
