// frontend/src/types/query.ts

export type QueryMode = "auto" | "graph-rag" | "document-rag" | "agent-stream";

export type MessageStatus = "pending" | "streaming" | "done" | "error";

export interface UserMessage {
  id: string;
  role: "user";
  content: string;
  mode: QueryMode;
  collectionId: string;
  timestamp: number;
}

export interface AssistantMessage {
  id: string;
  role: "assistant";
  mode: QueryMode;
  collectionId: string;
  status: MessageStatus;
  timestamp: number;
  graphRag?: GraphRagPayload;
  documentRag?: DocumentRagPayload;
  nlpQuery?: NlpQueryPayload;
  agentStream?: AgentStreamPayload;
  error?: { message: string; originalQuery: string };
}

export type QueryMessage = UserMessage | AssistantMessage;

export interface GraphRagPayload {
  sessionId: string;
  answer: string;
  selectedEdges: SelectedEdge[];
  retrievedEdgeCount: number;
  durationMs: number;
}

export interface SelectedEdge {
  subject: string;
  predicate: string;
  objectValue: string;
  dataset: string;
  reasoning: string;
  relevanceScore: number;
}

export interface DocumentRagPayload {
  sessionId: string;
  answer: string;
  sources: DocumentRagSource[];
  retrievedChunkCount: number;
  durationMs: number;
}

export interface DocumentRagSource {
  chunkId: string;
  documentId: string;
  documentTitle: string;
  pageNumber: number | null;
  score: number;
  snippet: string;
}

export interface NlpQueryPayload {
  answer: string;
  detectedIntent: { intent: string; confidence: number; reasoning: string };
  wasReformulated: boolean;
  effectiveQuestion: string;
  durationMs: number;
  sources: string[];
}

export interface AgentStreamPayload {
  tokens: AgentStreamToken[];
  finalAnswer: string;
}

export interface AgentStreamToken {
  content: string;
  type: "TEXT" | "THOUGHT" | "ACTION" | "OBSERVATION" | "ANSWER" | "ERROR";
  endOfMessage: boolean;
  endOfStream: boolean;
}

export interface HistoryEntry {
  id: string;
  query: string;
  mode: QueryMode;
  collectionId: string;
  timestamp: number;
}
