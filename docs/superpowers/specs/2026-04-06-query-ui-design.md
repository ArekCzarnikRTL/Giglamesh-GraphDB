# Feature 33: Query UI — Design

**Datum:** 2026-04-06
**Feature-Doc:** `docs/features/33-query-ui.md`
**Status:** Approved by user, ready for implementation plan

## Kontext

GraphMesh stellt drei Query-APIs bereit (Graph RAG, Document RAG, NLP) und eine
Streaming-Subscription (Agent Stream / Feature 27). Das Frontend hat aktuell
keine Oberflaeche, um diese Endpunkte zu nutzen — Nutzer muessten GraphQL-Queries
manuell formulieren. Dieses Feature liefert eine Chat-basierte Query-UI auf
`/query`.

## Abweichungen vom Feature-Dokument

Das Original-Feature-Dokument geht von einem Schema aus, das so nicht existiert.
Die folgenden Anpassungen wurden mit dem Nutzer abgestimmt:

1. **Streaming nur fuer Agent-Modus.** `graphRag`/`documentRag`/`nlpQuery` sind
   GraphQL-Queries (kein Token-Stream). Echtes Token-Streaming gibt es nur ueber
   die `agentStream`-Subscription aus Feature 27. Daher wird ein vierter Modus
   "Agent (Streaming)" eingefuehrt; die anderen drei Modi laufen non-streaming
   mit Loading-Spinner.
2. **Source-Felder folgen dem tatsaechlichen Schema** (`snippet`/`pageNumber`
   statt `chunkText`, `selectedEdges` mit `objectValue`/`dataset`/`reasoning`).
3. **NLP-Modus** zeigt zusaetzlich `detectedIntent`, `effectiveQuestion`,
   `wasReformulated`. Sources sind hier nur Strings.
4. **Kein `explainabilityChain`** in der UI (Feature 30 hat dafuer ein separates
   Schema).
5. **CollectionSelector** ist props-los und liest aus dem globalen
   `useActiveCollection`-Store (etablierte Konvention aus Feature 32).
6. **Apollo-Wrapper** wird um WebSocket-Link erweitert (`graphql-ws` als neue
   Dependency).

## Modi

| Modus | Backend | Streaming | Quellen-Rendering |
|---|---|---|---|
| Auto / NLP | `nlpQuery` Query | nein | `NlpQueryMeta` (Intent + Strings) |
| Graph RAG | `graphRag` Query | nein | `GraphRagSources` (Triples) |
| Document RAG | `documentRag` Query | nein | `DocumentRagSources` (Chunks + Doc-Links) |
| Agent (Streaming) | `agentStream` Subscription | ja, Token-fuer-Token | `AgentStreamTimeline` (Phasen) |

## Architektur

Drei Hauptbereiche auf der Seite `/query`:

```
+-------------------------------------------------------+
| Header: CollectionSelector  ModeSelector  History    |
+--------------------------------+----------------------+
|                                |                      |
|  Chat-Bereich                  |  SourcePanel        |
|  (User- und Assistant-Bubbles, |  (modus-spezifisch, |
|   ErrorBubble, Auto-Scroll)    |   schliessbar)      |
|                                |                      |
+--------------------------------+----------------------+
| QueryInput (Textarea + Submit)                       |
+-------------------------------------------------------+
```

Jeder Modus hat einen dedizierten Hook, der Apollo-Queries oder -Subscription
aufruft und ein einheitliches `AssistantMessage`-Objekt zurueckliefert. Source-
Rendering erfolgt pro Modus durch eine eigene Komponente — kein generischer
Adapter.

## Komponenten und Dateien

### Neue Dateien

```
frontend/src/
├── app/query/
│   ├── layout.tsx
│   └── page.tsx
├── components/query/
│   ├── QueryChat.tsx
│   ├── QueryInput.tsx
│   ├── QueryMessage.tsx
│   ├── ModeSelector.tsx
│   ├── ErrorBubble.tsx
│   ├── SourcePanel.tsx
│   ├── QueryHistory.tsx
│   └── sources/
│       ├── GraphRagSources.tsx
│       ├── DocumentRagSources.tsx
│       ├── NlpQueryMeta.tsx
│       └── AgentStreamTimeline.tsx
├── hooks/
│   ├── useGraphRag.ts
│   ├── useDocumentRag.ts
│   ├── useNlpQuery.ts
│   └── useAgentStream.ts
├── lib/
│   └── query-history.ts
├── graphql/
│   └── query.ts
└── types/
    └── query.ts
```

### Geaenderte Dateien

- `frontend/src/lib/apollo-wrapper.tsx` — `GraphQLWsLink` + `ApolloLink.split`
- `frontend/package.json` — Dependency `graphql-ws` hinzu

## Datenmodelle

```typescript
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
```

## Datenfluss

### Non-Streaming-Modi (Graph RAG / Document RAG / Auto-NLP)

1. `QueryChat.handleSubmit(question)` pusht `UserMessage` in `messages`
2. `query-history.saveToHistory({query, mode, collectionId})`
3. Modus-Hook `execute(question)` ruft Apollo `useLazyQuery` mit
   `{question, collectionId}`
4. Pending: `AssistantMessage` mit `status: "pending"` wird ergaenzt
5. Erfolg: `status: "done"`, mode-spezifisches Payload-Feld wird gesetzt
6. Fehler: `status: "error"`, `error: {message, originalQuery}` — `ErrorBubble`
   mit Retry-Button wird gerendert

### Agent-Stream

1. `useAgentStream.execute(question)` startet `client.subscribe({query, vars})`
2. Eingehende `StreamToken` werden in `tokens[]`-State gepusht; UI rendert
   live `<AgentStreamTimeline tokens={tokens}/>` in einer "streaming" Bubble
3. Bei `endOfStream: true` wird die Message in `messages[]` finalisiert
   (`status: "done"`, `finalAnswer` aus ANSWER-Tokens akkumuliert)
4. Bei Disconnect/Error: `status: "error"` mit Retry-Button

## Apollo-Wrapper Erweiterung

```typescript
// frontend/src/lib/apollo-wrapper.tsx
"use client";

import { HttpLink, ApolloLink } from "@apollo/client";
import { GraphQLWsLink } from "@apollo/client/link/subscriptions";
import { createClient } from "graphql-ws";
import { OperationTypeNode } from "graphql";
import {
  ApolloNextAppProvider,
  ApolloClient,
  InMemoryCache,
} from "@apollo/client-integration-nextjs";

function makeClient() {
  const httpUri =
    process.env.NEXT_PUBLIC_GRAPHQL_URL ?? "http://localhost:8080/graphql";
  const wsUri =
    process.env.NEXT_PUBLIC_GRAPHQL_WS_URL ??
    httpUri.replace(/^http/, "ws");

  const httpLink = new HttpLink({ uri: httpUri });

  const link =
    typeof window === "undefined"
      ? httpLink
      : ApolloLink.split(
          ({ operationType }) => operationType === OperationTypeNode.SUBSCRIPTION,
          new GraphQLWsLink(createClient({ url: wsUri })),
          httpLink,
        );

  return new ApolloClient({ cache: new InMemoryCache(), link });
}

export function ApolloWrapper({ children }: { children: React.ReactNode }) {
  return (
    <ApolloNextAppProvider makeClient={makeClient}>
      {children}
    </ApolloNextAppProvider>
  );
}
```

- Neue Env-Var `NEXT_PUBLIC_GRAPHQL_WS_URL` (optional, default: HTTP-URL nach
  `ws://`/`wss://` umgeschrieben)
- SSR-sicher: `GraphQLWsLink` nur im Browser instanziiert

## Verhaltensentscheidungen

- **Chat-Verlauf:** Nur In-Memory (`useState`), kein Persist nach Reload.
- **Collection-Wechsel:** Bestaetigungsdialog ("Collection wechseln leert den
  Chat. Fortfahren?"). Bei Bestaetigung wird `messages[]` geleert.
- **Fehler:** Inline-`ErrorBubble` mit Retry-Button im Chat (kein Toast).
- **History:** LocalStorage, max 100 Eintraege. Speichert nur Eingaben (nicht
  Antworten). Klick im History-Sidebar fuehrt die Frage erneut aus.
- **Empty-State:** Wenn keine Collection gewaehlt ist, zeigt die Seite einen
  Hinweis zur Collection-Auswahl.

## Tests

Vitest + @testing-library/react, dem Muster der bestehenden
`frontend/src/__tests__/`-Tests folgend. Mock-Apollo via `MockedProvider`.

| Testdatei | Inhalt |
|---|---|
| `__tests__/components/query/QueryChat.test.tsx` | Submit fuegt Bubbles hinzu, Erfolgs-Render je Modus, Auto-Scroll, Empty-State |
| `__tests__/components/query/ModeSelector.test.tsx` | Vier Buttons, Klick-Callback, aktiver Modus markiert |
| `__tests__/components/query/QueryHistory.test.tsx` | LocalStorage-Lesen, Klick-Callback, Clear |
| `__tests__/components/query/sources/GraphRagSources.test.tsx` | Triples mit Score und Reasoning |
| `__tests__/components/query/sources/DocumentRagSources.test.tsx` | Chunks mit Page-Badge und `/documents/{id}` Link |
| `__tests__/components/query/sources/NlpQueryMeta.test.tsx` | Intent, Reformulation, Strings |
| `__tests__/components/query/sources/AgentStreamTimeline.test.tsx` | Tokens nach Typ farblich |
| `__tests__/components/query/ErrorBubble.test.tsx` | Retry-Button-Klick |
| `__tests__/hooks/useGraphRag.test.ts` | Success + Error |
| `__tests__/hooks/useDocumentRag.test.ts` | Success + Error |
| `__tests__/hooks/useNlpQuery.test.ts` | Success + Error |
| `__tests__/hooks/useAgentStream.test.ts` | Subscription-Mock, Tokens, Disconnect |
| `__tests__/lib/query-history.test.ts` | save/get/clear, Limit 100, SSR-Guard |
| `__tests__/app/query/page.test.tsx` | Empty-State, Collection-Wechsel-Dialog |

Keine Backend-Tests (rein Frontend-Feature).

## Akzeptanzkriterien-Mapping

| # | Original-AC | Status |
|---|---|---|
| 1 | `/query` zeigt Chat-Interface | ✓ |
| 2 | ModeSelector: Graph/Doc/Auto | ✓ +Agent-Stream |
| 3 | Collection-Selektor | ✓ Header-Store |
| 4 | Streaming Token-fuer-Token | ✓ nur im Agent-Stream-Modus |
| 5 | Quellenverweise mit Score | ✓ |
| 6 | Klick auf Quelle oeffnet Panel | ✓ |
| 7 | Klick auf Dokument navigiert | ✓ |
| 8 | LocalStorage History | ✓ |
| 9 | Max 100 Eintraege | ✓ |
| 10 | Empty-State ohne Collection | ✓ |
| 11 | Fehler verstaendlich angezeigt | ✓ ErrorBubble + Retry |
| 12 | Auto-Scroll | ✓ |
| 13 | Bestehende Funktionalitaet unberuehrt | ✓ Apollo-Wrapper rueckwaertskompatibel |

## Out of Scope

- Persistenter Chat-Verlauf ueber Reload hinaus
- Streaming-Token-Anzeige fuer Non-Agent-Modi (Backend liefert das nicht)
- Server-seitige History (nur LocalStorage)
- Multi-Chat-Tabs / parallele Sessions
- Export oder Teilen von Antworten
