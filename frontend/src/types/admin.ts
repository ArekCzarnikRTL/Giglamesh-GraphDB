// frontend/src/types/admin.ts

export type ConfigTypeValue =
  | "ONTOLOGY"
  | "FLOW"
  | "TOOL"
  | "PARAMETER"
  | "COLLECTION_SETTINGS"
  | "LLM_SETTINGS"
  | "SCHEMA";

export const CONFIG_TYPES: ConfigTypeValue[] = [
  "ONTOLOGY",
  "FLOW",
  "TOOL",
  "PARAMETER",
  "COLLECTION_SETTINGS",
  "LLM_SETTINGS",
  "SCHEMA",
];

export interface ConfigEntry {
  id: string;
  type: ConfigTypeValue;
  key: string;
  value: string;
  version: number;
}

export interface AdminCollection {
  id: string;
  name: string;
  description: string | null;
  tags: string[];
  metadata: { key: string; value: string }[];
  createdAt: string;
  updatedAt: string;
}

export interface CollectionStats {
  collectionId: string;
  processingCount: number;
  failedCount: number;
}

export interface PipelineDocument {
  id: string;
  collectionId: string;
  title: string;
  state: "UPLOADED" | "PROCESSING" | "EXTRACTED" | "FAILED";
  createdAt: string;
}
