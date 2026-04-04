# Feature 33: Query UI

## Problem

GraphMesh stellt ueber Feature 15 (Graph RAG) und Feature 16 (Document RAG) leistungsfaehige Abfragepipelines bereit,
jedoch fehlt eine benutzerfreundliche Oberflaeche, um diese zu nutzen. Benutzer muessen GraphQL-Queries manuell
formulieren, Streaming-Responses selbst verarbeiten und haben keine Moeglichkeit, Quellenverweise interaktiv zu
erkunden. Ohne eine Chat-artige Query-UI bleibt das volle Potenzial der RAG-Pipelines fuer Nicht-Entwickler
unzugaenglich.

## Ziel

Implementierung einer Chat-basierten Query-Oberflaeche in Next.js, die Graph RAG, Document RAG und NLP-Abfragen ueber
eine einheitliche Schnittstelle bereitstellt und Streaming-Antworten mit Quellennachweis anzeigt.

1. **Chat-Interface** -- Chat-artiges UI fuer natuerlichsprachige Abfragen mit Antwort-Streaming
2. **Mode Selector** -- Umschaltung zwischen Graph RAG, Document RAG und Auto/NLP-Modus
3. **Streaming Response** -- Token-fuer-Token-Anzeige der LLM-Antwort via GraphQL-Subscription
4. **Source Attribution** -- Quellenverweise mit klickbaren Links zu Dokumenten und Chunks
5. **Query History** -- Lokale Speicherung der Abfragehistorie im Browser (LocalStorage)
6. **Collection Scope** -- Einschraenkung der Abfrage auf eine bestimmte Collection

## Voraussetzungen

| Abhaengigkeit                                            | Status     | Blocker? |
|----------------------------------------------------------|------------|----------|
| Feature 14: GraphQL API (Schema, Subscriptions)          | Geplant    | Ja       |
| Feature 15: Graph RAG (GraphRagService, Streaming)       | Geplant    | Ja       |
| Feature 16: Document RAG (DocumentRagService, Streaming) | Geplant    | Ja       |
| Feature 18: NLP Query Service (NlpQueryService)          | Geplant    | Ja       |
| Next.js 14+ (App Router)                                 | Verfuegbar | Nein     |
| Apollo Client                                            | Verfuegbar | Nein     |

## Architektur

### GraphQL Queries und Subscriptions

```graphql
# Graph RAG Abfrage
query GraphRag($input: GraphRagInput!) {
    graphRag(input: $input) {
        answer
        sources {
            subject
            predicate
            object
            graph
            relevanceScore
            reason
        }
        explainabilityChain {
            step
            description
            data
        }
    }
}

# Document RAG Abfrage
query DocumentRag($input: DocumentRagInput!) {
    documentRag(input: $input) {
        answer
        sources {
            chunkId
            documentId
            documentTitle
            text
            score
        }
    }
}

# NLP Query (Auto-Modus)
query NlpQuery($input: NlpQueryInput!) {
    nlpQuery(input: $input) {
        answer
        mode
        sources {
            type
            reference
            score
        }
    }
}

# Streaming-Antwort abonnieren
subscription StreamingResponse($queryId: ID!) {
    streamingResponse(queryId: $queryId) {
        queryId
        token
        done
        sources {
            type
            reference
            score
        }
    }
}
```

### TypeScript-Typen

```typescript
// frontend/src/types/query.ts

export type QueryMode = "graph-rag" | "document-rag" | "auto";

export interface QueryMessage {
    id: string;
    role: "user" | "assistant";
    content: string;
    mode: QueryMode;
    sources: QuerySource[];
    timestamp: number;
}

export interface QuerySource {
    type: "triple" | "chunk" | "document";
    reference: string;
    documentId?: string;
    documentTitle?: string;
    chunkText?: string;
    score: number;
    reason?: string;
}

export interface StreamingToken {
    queryId: string;
    token: string;
    done: boolean;
    sources?: QuerySource[];
}

export interface QueryHistoryEntry {
    id: string;
    query: string;
    mode: QueryMode;
    collectionId: string;
    timestamp: number;
}
```

### QueryChat-Komponente

```typescript
// frontend/src/components/query/QueryChat.tsx
"use client";

import { useState, useRef, useEffect } from "react";
import { QueryInput } from "./QueryInput";
import { QueryResult } from "./QueryResult";
import { ModeSelector } from "./ModeSelector";
import { SourceAttribution } from "./SourceAttribution";
import { useQueryExecution } from "@/hooks/useQueryExecution";
import { QueryMessage, QueryMode } from "@/types/query";

interface QueryChatProps {
    collectionId: string;
}

export function QueryChat({ collectionId }: QueryChatProps) {
    const [messages, setMessages] = useState<QueryMessage[]>([]);
    const [mode, setMode] = useState<QueryMode>("auto");
    const [selectedSources, setSelectedSources] = useState<QueryMessage | null>(null);
    const messagesEndRef = useRef<HTMLDivElement>(null);

    const { execute, isStreaming, streamingContent } = useQueryExecution({
        collectionId,
        mode,
        onComplete: (message) => {
            setMessages((prev) => [...prev, message]);
        },
    });

    const handleSubmit = async (query: string) => {
        const userMessage: QueryMessage = {
            id: crypto.randomUUID(),
            role: "user",
            content: query,
            mode,
            sources: [],
            timestamp: Date.now(),
        };
        setMessages((prev) => [...prev, userMessage]);
        await execute(query);
    };

    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
    }, [messages, streamingContent]);

    return (
        <div className="flex h-full">
            {/* Chat-Bereich */}
            <div className="flex-1 flex flex-col">
                <ModeSelector selected={mode} onSelect={setMode} />
                <div className="flex-1 overflow-y-auto p-4 space-y-4">
                    {messages.map((msg) => (
                        <QueryResult
                            key={msg.id}
                            message={msg}
                            onSourceClick={() => setSelectedSources(msg)}
                        />
                    ))}
                    {isStreaming && <StreamingResponse content={streamingContent} />}
                    <div ref={messagesEndRef} />
                </div>
                <QueryInput onSubmit={handleSubmit} isLoading={isStreaming} />
            </div>

            {/* Quellen-Panel */}
            {selectedSources && (
                <SourceAttribution
                    sources={selectedSources.sources}
                    onClose={() => setSelectedSources(null)}
                />
            )}
        </div>
    );
}
```

### useQueryExecution Hook

```typescript
// frontend/src/hooks/useQueryExecution.ts
"use client";

import { useState, useCallback } from "react";
import { useLazyQuery, useSubscription } from "@apollo/client";
import { GRAPH_RAG_QUERY, DOCUMENT_RAG_QUERY, NLP_QUERY } from "@/graphql/queries/query";
import { STREAMING_RESPONSE } from "@/graphql/subscriptions/query";
import { QueryMode, QueryMessage, QuerySource } from "@/types/query";
import { saveToHistory } from "@/lib/queryHistory";

interface UseQueryExecutionOptions {
    collectionId: string;
    mode: QueryMode;
    onComplete: (message: QueryMessage) => void;
}

export function useQueryExecution({ collectionId, mode, onComplete }: UseQueryExecutionOptions) {
    const [isStreaming, setIsStreaming] = useState(false);
    const [streamingContent, setStreamingContent] = useState("");
    const [queryId, setQueryId] = useState<string | null>(null);

    // Subscription fuer Streaming-Tokens
    useSubscription(STREAMING_RESPONSE, {
        variables: { queryId },
        skip: !queryId,
        onData: ({ data }) => {
            const token = data.data?.streamingResponse;
            if (token) {
                setStreamingContent((prev) => prev + token.token);
                if (token.done) {
                    setIsStreaming(false);
                    onComplete({
                        id: token.queryId,
                        role: "assistant",
                        content: streamingContent + token.token,
                        mode,
                        sources: token.sources ?? [],
                        timestamp: Date.now(),
                    });
                    setStreamingContent("");
                    setQueryId(null);
                }
            }
        },
    });

    const execute = useCallback(async (query: string) => {
        setIsStreaming(true);
        setStreamingContent("");
        saveToHistory({ query, mode, collectionId });

        // Query-ID vom Server erhalten und Streaming starten
        const newQueryId = crypto.randomUUID();
        setQueryId(newQueryId);
    }, [collectionId, mode, onComplete]);

    return { execute, isStreaming, streamingContent };
}
```

### ModeSelector-Komponente

```typescript
// frontend/src/components/query/ModeSelector.tsx
"use client";

import { QueryMode } from "@/types/query";

interface ModeSelectorProps {
    selected: QueryMode;
    onSelect: (mode: QueryMode) => void;
}

const modes: { value: QueryMode; label: string; description: string }[] = [
    { value: "auto", label: "Auto / NLP", description: "Automatische Moduswahl" },
    { value: "graph-rag", label: "Graph RAG", description: "Antworten aus dem Knowledge Graph" },
    { value: "document-rag", label: "Document RAG", description: "Antworten aus Dokumenten-Chunks" },
];

export function ModeSelector({ selected, onSelect }: ModeSelectorProps) {
    return (
        <div className="flex gap-2 p-4 border-b">
            {modes.map((mode) => (
                <button
                    key={mode.value}
                    onClick={() => onSelect(mode.value)}
                    className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
                        selected === mode.value
                            ? "bg-blue-600 text-white"
                            : "bg-gray-100 text-gray-700 hover:bg-gray-200"
                    }`}
                    title={mode.description}
                >
                    {mode.label}
                </button>
            ))}
        </div>
    );
}
```

### QueryHistory (LocalStorage)

```typescript
// frontend/src/lib/queryHistory.ts

const HISTORY_KEY = "graphmesh-query-history";
const MAX_ENTRIES = 100;

export interface HistoryEntry {
    id: string;
    query: string;
    mode: string;
    collectionId: string;
    timestamp: number;
}

export function saveToHistory(entry: Omit<HistoryEntry, "id" | "timestamp">): void {
    const history = getHistory();
    const newEntry: HistoryEntry = {
        ...entry,
        id: crypto.randomUUID(),
        timestamp: Date.now(),
    };
    const updated = [newEntry, ...history].slice(0, MAX_ENTRIES);
    localStorage.setItem(HISTORY_KEY, JSON.stringify(updated));
}

export function getHistory(): HistoryEntry[] {
    if (typeof window === "undefined") return [];
    const stored = localStorage.getItem(HISTORY_KEY);
    return stored ? JSON.parse(stored) : [];
}

export function clearHistory(): void {
    localStorage.removeItem(HISTORY_KEY);
}
```

### SourceAttribution-Komponente

```typescript
// frontend/src/components/query/SourceAttribution.tsx
"use client";

import Link from "next/link";
import { QuerySource } from "@/types/query";

interface SourceAttributionProps {
    sources: QuerySource[];
    onClose: () => void;
}

export function SourceAttribution({ sources, onClose }: SourceAttributionProps) {
    return (
        <aside className="w-80 border-l bg-gray-50 p-4 overflow-y-auto">
            <div className="flex justify-between items-center mb-4">
                <h3 className="font-semibold">Quellen</h3>
                <button onClick={onClose} className="text-gray-500 hover:text-gray-700">
                    &times;
                </button>
            </div>
            <div className="space-y-3">
                {sources.map((source, index) => (
                    <div key={index} className="rounded-lg border bg-white p-3">
                        <div className="flex justify-between text-xs text-gray-500 mb-1">
                            <span className="uppercase">{source.type}</span>
                            <span>Score: {(source.score * 100).toFixed(0)}%</span>
                        </div>
                        {source.documentId && (
                            <Link
                                href={`/documents/${source.documentId}`}
                                className="text-blue-600 hover:underline text-sm"
                            >
                                {source.documentTitle ?? source.documentId}
                            </Link>
                        )}
                        {source.chunkText && (
                            <p className="text-sm text-gray-700 mt-1 line-clamp-3">
                                {source.chunkText}
                            </p>
                        )}
                        {source.reason && (
                            <p className="text-xs text-gray-500 mt-1 italic">{source.reason}</p>
                        )}
                    </div>
                ))}
            </div>
        </aside>
    );
}
```

### Seitenstruktur

```typescript
// frontend/src/app/query/page.tsx
"use client";

import { useState } from "react";
import { QueryChat } from "@/components/query/QueryChat";
import { QueryHistory } from "@/components/query/QueryHistory";
import { CollectionSelector } from "@/components/documents/CollectionSelector";

export default function QueryPage() {
    const [collectionId, setCollectionId] = useState<string | null>(null);
    const [showHistory, setShowHistory] = useState(false);

    return (
        <main className="h-screen flex flex-col">
            <header className="flex items-center gap-4 p-4 border-b">
                <h1 className="text-xl font-bold">Knowledge Base abfragen</h1>
                <CollectionSelector selectedId={collectionId} onSelect={setCollectionId} />
                <button
                    onClick={() => setShowHistory(!showHistory)}
                    className="ml-auto text-sm text-gray-600 hover:text-gray-900"
                >
                    Verlauf
                </button>
            </header>
            <div className="flex-1 flex overflow-hidden">
                {showHistory && <QueryHistory onSelect={() => setShowHistory(false)} />}
                {collectionId ? (
                    <QueryChat collectionId={collectionId} />
                ) : (
                    <div className="flex-1 flex items-center justify-center text-gray-500">
                        Bitte eine Collection auswaehlen
                    </div>
                )}
            </div>
        </main>
    );
}
```

## Betroffene Dateien

### Backend

Nicht betroffen (die Query-UI nutzt ausschliesslich die bestehende GraphQL-API aus Feature 14 sowie die RAG-Endpoints
aus Feature 15/16).

### Frontend

| Datei                                                 | Aenderung                                          |
|-------------------------------------------------------|----------------------------------------------------|
| `frontend/src/app/query/page.tsx`                     | NEU - Query-Seite mit Chat-Interface               |
| `frontend/src/app/query/layout.tsx`                   | NEU - Layout fuer Query-Bereich                    |
| `frontend/src/components/query/QueryChat.tsx`         | NEU - Chat-Container mit Nachrichtenverlauf        |
| `frontend/src/components/query/QueryInput.tsx`        | NEU - Eingabefeld mit Submit-Button                |
| `frontend/src/components/query/QueryResult.tsx`       | NEU - Einzelne Nachrichtenanzeige (User/Assistant) |
| `frontend/src/components/query/StreamingResponse.tsx` | NEU - Token-fuer-Token-Anzeige waehrend Streaming  |
| `frontend/src/components/query/SourceAttribution.tsx` | NEU - Quellen-Panel mit klickbaren Links           |
| `frontend/src/components/query/QueryHistory.tsx`      | NEU - Sidebar mit Abfragehistorie                  |
| `frontend/src/components/query/ModeSelector.tsx`      | NEU - Umschalter Graph RAG / Document RAG / Auto   |
| `frontend/src/hooks/useQueryExecution.ts`             | NEU - Hook fuer Query-Ausfuehrung und Streaming    |
| `frontend/src/lib/queryHistory.ts`                    | NEU - LocalStorage-basierte Abfragehistorie        |
| `frontend/src/graphql/queries/query.ts`               | NEU - GraphQL-Query-Definitionen fuer RAG          |
| `frontend/src/graphql/subscriptions/query.ts`         | NEU - Subscription fuer Streaming-Responses        |
| `frontend/src/types/query.ts`                         | NEU - TypeScript-Typen fuer Query-Bereich          |

### Tests

| Datei                                                                | Aenderung                              |
|----------------------------------------------------------------------|----------------------------------------|
| `frontend/src/__tests__/components/query/QueryChat.test.tsx`         | NEU - Chat-Integrationstests           |
| `frontend/src/__tests__/components/query/ModeSelector.test.tsx`      | NEU - Moduswechsel-Tests               |
| `frontend/src/__tests__/components/query/SourceAttribution.test.tsx` | NEU - Quellenanzeige-Tests             |
| `frontend/src/__tests__/components/query/StreamingResponse.test.tsx` | NEU - Streaming-Darstellungstests      |
| `frontend/src/__tests__/hooks/useQueryExecution.test.ts`             | NEU - Hook-Tests mit Mock-Subscription |
| `frontend/src/__tests__/lib/queryHistory.test.ts`                    | NEU - LocalStorage-Tests               |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                                           |
|-------------------|-------------|-----------------------------------------------------------------|
| Spring Boot (JVM) | Ja          | Frontend kommuniziert ueber GraphQL-API mit Spring Boot Backend |
| KMP Library       | Nein        | Reines Frontend-Feature, kein Backend-Code betroffen            |
| Ktor/Wasm         | Nein        | Reines Frontend-Feature, kein Backend-Code betroffen            |

## Akzeptanzkriterien

- [ ] Seite `/query` zeigt ein Chat-Interface mit Eingabefeld und Nachrichtenverlauf
- [ ] ModeSelector erlaubt Umschaltung zwischen Graph RAG, Document RAG und Auto/NLP
- [ ] Collection-Selektor schraenkt die Abfrage auf eine bestimmte Collection ein
- [ ] Streaming-Antworten werden Token fuer Token in Echtzeit angezeigt
- [ ] Abgeschlossene Antworten zeigen Quellenverweise mit Score an
- [ ] Klick auf eine Quelle oeffnet das Quellen-Panel mit Details
- [ ] Klick auf einen Dokumentenverweis navigiert zur Dokument-Detailseite
- [ ] Abfragehistorie wird im LocalStorage gespeichert und ist ueber die Sidebar zugaenglich
- [ ] Maximal 100 Eintraege werden in der Historie gespeichert
- [ ] Leerer Zustand zeigt Hinweis zur Collection-Auswahl
- [ ] Fehler bei der Abfrage werden dem Benutzer verstaendlich angezeigt
- [ ] Chat scrollt automatisch zur neuesten Nachricht
- [ ] Bestehende Funktionalitaet bleibt unberuehrt
