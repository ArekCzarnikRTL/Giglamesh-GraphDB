export type DocumentType = "SOURCE" | "PAGE" | "CHUNK";
export type DocumentState = "UPLOADED" | "PROCESSING" | "EXTRACTED" | "FAILED";

export interface KeyValue {
  key: string;
  value: string;
}

export interface DocumentSummary {
  id: string;
  collectionId: string;
  parentId: string | null;
  title: string;
  type: DocumentType;
  state: DocumentState;
  mimeType: string;
  createdAt: string;
}

export interface DocumentDetail extends DocumentSummary {
  metadata: KeyValue[];
  children: DocumentSummary[];
}

export interface DocumentChunk {
  id: string;
  title: string;
  type: DocumentType;
  metadata: KeyValue[];
}

export interface DocumentPage {
  items: DocumentSummary[];
  totalCount: number;
  hasNextPage: boolean;
}

export interface DocumentFilter {
  type?: DocumentType;
  state?: DocumentState;
  search?: string;
}

export interface Collection {
  id: string;
  name: string;
  description: string | null;
}

export interface Quad {
  subject: string;
  predicate: string;
  object: string;
  objectType: string;
}
