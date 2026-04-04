# Feature 32: Document UI

## Problem

GraphMesh bietet ueber die GraphQL-API (Feature 14) umfangreiche Funktionen zum Verwalten und Abfragen von Dokumenten,
jedoch existiert keine grafische Oberflaeche fuer Endbenutzer. Ohne eine dedizierte Document-UI muessen Benutzer
Dokumente ueber GraphiQL oder externe Tools hochladen, filtern und inspizieren, was fuer Nicht-Entwickler unpraktisch
ist und die Akzeptanz der Plattform einschraenkt.

## Ziel

Implementierung einer Next.js-basierten Document-UI, die den gesamten Dokumentenlebenszyklus von Upload bis
Detailansicht abdeckt und ueber die GraphQL-API mit dem Backend kommuniziert.

1. **Document Upload** -- Drag-and-Drop und File-Picker fuer den Dokumenten-Upload mit Fortschrittsanzeige
2. **Document List** -- Durchsuchbare und filterbare Dokumentenliste (Collection, Typ, Status)
3. **Document Detail** -- Detailansicht mit Metadaten, Parent-Child-Hierarchie, Chunks und extrahierten Triples
4. **Collection Selector** -- Wiederverwendbare Komponente zur Auswahl der aktiven Collection
5. **Upload Progress** -- Echtzeit-Fortschrittsanzeige waehrend des Uploads und der Extraktion

## Voraussetzungen

| Abhaengigkeit                                        | Status     | Blocker? |
|------------------------------------------------------|------------|----------|
| Feature 09: Document Management (LibrarianService)   | Geplant    | Ja       |
| Feature 14: GraphQL API (Schema, Queries, Mutations) | Geplant    | Ja       |
| Next.js 14+ (App Router)                             | Verfuegbar | Nein     |
| Apollo Client                                        | Verfuegbar | Nein     |
| React Dropzone                                       | Verfuegbar | Nein     |

## Architektur

### GraphQL Queries und Mutations

```graphql
# Dokumente einer Collection laden (mit Pagination und Filter)
query Documents($collectionId: ID!, $filter: DocumentFilter, $page: Int, $pageSize: Int) {
    documents(collectionId: $collectionId, filter: $filter, page: $page, pageSize: $pageSize) {
        items {
            id
            title
            type
            state
            mimeType
            createdAt
            metadata { key value }
        }
        totalCount
        hasNextPage
    }
}

# Einzelnes Dokument mit Hierarchie
query Document($id: ID!) {
    document(id: $id) {
        id
        title
        type
        state
        mimeType
        collectionId
        parentId
        metadata { key value }
        children {
            id
            title
            type
            state
        }
        createdAt
    }
}

# Chunks eines Dokuments
query DocumentChunks($documentId: ID!) {
    documentChunks(documentId: $documentId) {
        id
        text
        index
        metadata { key value }
    }
}

# Dokument hochladen
mutation UploadDocument($input: UploadDocumentInput!) {
    uploadDocument(input: $input) {
        id
        title
        state
    }
}

# Extraktionsfortschritt abonnieren
subscription ExtractionProgress($documentId: ID!) {
    extractionProgress(documentId: $documentId) {
        documentId
        stage
        progress
        message
    }
}
```

### TypeScript-Typen

```typescript
// frontend/src/types/document.ts

export interface Document {
    id: string;
    collectionId: string;
    parentId: string | null;
    title: string;
    type: DocumentType;
    state: DocumentState;
    mimeType: string;
    metadata: KeyValue[];
    children: Document[];
    createdAt: string;
}

export enum DocumentType {
    SOURCE = "SOURCE",
    PAGE = "PAGE",
    CHUNK = "CHUNK",
}

export enum DocumentState {
    UPLOADED = "UPLOADED",
    PROCESSING = "PROCESSING",
    EXTRACTED = "EXTRACTED",
    FAILED = "FAILED",
}

export interface DocumentChunk {
    id: string;
    text: string;
    index: number;
    metadata: KeyValue[];
}

export interface KeyValue {
    key: string;
    value: string;
}

export interface DocumentFilter {
    type?: DocumentType;
    state?: DocumentState;
    search?: string;
}
```

### DocumentUpload-Komponente

```typescript
// frontend/src/components/documents/DocumentUpload.tsx
"use client";

import { useCallback, useState } from "react";
import { useDropzone, FileRejection } from "react-dropzone";
import { useMutation, useSubscription } from "@apollo/client";
import { UPLOAD_DOCUMENT } from "@/graphql/mutations/document";
import { EXTRACTION_PROGRESS } from "@/graphql/subscriptions/extraction";

interface DocumentUploadProps {
    collectionId: string;
    onUploadComplete: (documentId: string) => void;
}

interface UploadState {
    file: File | null;
    progress: number;
    stage: string;
    documentId: string | null;
    error: string | null;
}

export function DocumentUpload({ collectionId, onUploadComplete }: DocumentUploadProps) {
    const [uploadState, setUploadState] = useState<UploadState>({
        file: null,
        progress: 0,
        stage: "",
        documentId: null,
        error: null,
    });

    const [uploadDocument] = useMutation(UPLOAD_DOCUMENT);

    // Subscription fuer Extraktionsfortschritt
    useSubscription(EXTRACTION_PROGRESS, {
        variables: { documentId: uploadState.documentId },
        skip: !uploadState.documentId,
        onData: ({ data }) => {
            const progress = data.data?.extractionProgress;
            if (progress) {
                setUploadState((prev) => ({
                    ...prev,
                    progress: progress.progress,
                    stage: progress.stage,
                }));
                if (progress.progress >= 1.0) {
                    onUploadComplete(progress.documentId);
                }
            }
        },
    });

    const onDrop = useCallback(async (acceptedFiles: File[], _rejections: FileRejection[]) => {
        const file = acceptedFiles[0];
        if (!file) return;

        setUploadState({ file, progress: 0, stage: "Upload", documentId: null, error: null });

        try {
            const base64 = await fileToBase64(file);
            const result = await uploadDocument({
                variables: {
                    input: {
                        collectionId,
                        title: file.name,
                        mimeType: file.type,
                        content: base64,
                    },
                },
            });
            setUploadState((prev) => ({
                ...prev,
                documentId: result.data.uploadDocument.id,
                stage: "Extraktion",
            }));
        } catch (err) {
            setUploadState((prev) => ({
                ...prev,
                error: err instanceof Error ? err.message : "Upload fehlgeschlagen",
            }));
        }
    }, [collectionId, uploadDocument]);

    const { getRootProps, getInputProps, isDragActive } = useDropzone({
        onDrop,
        accept: { "application/pdf": [".pdf"] },
        maxFiles: 1,
    });

    // ... Render-Logik
}

function fileToBase64(file: File): Promise<string> {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => {
            const base64 = (reader.result as string).split(",")[1];
            resolve(base64);
        };
        reader.onerror = reject;
        reader.readAsDataURL(file);
    });
}
```

### DocumentList-Komponente

```typescript
// frontend/src/components/documents/DocumentList.tsx
"use client";

import { useQuery } from "@apollo/client";
import { DOCUMENTS_QUERY } from "@/graphql/queries/document";
import { DocumentFilter, DocumentState, DocumentType } from "@/types/document";

interface DocumentListProps {
    collectionId: string;
    filter?: DocumentFilter;
    page?: number;
    pageSize?: number;
}

export function DocumentList({ collectionId, filter, page = 1, pageSize = 20 }: DocumentListProps) {
    const { data, loading, error } = useQuery(DOCUMENTS_QUERY, {
        variables: { collectionId, filter, page, pageSize },
    });

    if (loading) return <DocumentListSkeleton />;
    if (error) return <ErrorMessage message={error.message} />;

    const { items, totalCount, hasNextPage } = data.documents;

    return (
        <div className="space-y-4">
            <DocumentFilterBar filter={filter} />
            <div className="divide-y">
                {items.map((doc: Document) => (
                    <DocumentListItem key={doc.id} document={doc} />
                ))}
            </div>
            <Pagination page={page} totalCount={totalCount} hasNextPage={hasNextPage} />
        </div>
    );
}
```

### DocumentDetail-Komponente

```typescript
// frontend/src/components/documents/DocumentDetail.tsx
"use client";

import { useQuery } from "@apollo/client";
import { DOCUMENT_QUERY, DOCUMENT_CHUNKS_QUERY } from "@/graphql/queries/document";

interface DocumentDetailProps {
    documentId: string;
}

export function DocumentDetail({ documentId }: DocumentDetailProps) {
    const { data: docData, loading: docLoading } = useQuery(DOCUMENT_QUERY, {
        variables: { id: documentId },
    });

    const { data: chunksData, loading: chunksLoading } = useQuery(DOCUMENT_CHUNKS_QUERY, {
        variables: { documentId },
    });

    if (docLoading) return <DetailSkeleton />;

    const document = docData.document;

    return (
        <div className="grid grid-cols-3 gap-6">
            {/* Metadaten-Panel */}
            <section className="col-span-2 space-y-6">
                <DocumentMetadata document={document} />
                <DocumentHierarchy document={document} />
                <DocumentChunks chunks={chunksData?.documentChunks ?? []} loading={chunksLoading} />
            </section>

            {/* Sidebar: Extrahierte Triples */}
            <aside>
                <ExtractedTriples documentId={documentId} />
            </aside>
        </div>
    );
}
```

### CollectionSelector-Komponente

```typescript
// frontend/src/components/documents/CollectionSelector.tsx
"use client";

import { useQuery } from "@apollo/client";
import { COLLECTIONS_QUERY } from "@/graphql/queries/collection";

interface CollectionSelectorProps {
    selectedId: string | null;
    onSelect: (collectionId: string) => void;
}

export function CollectionSelector({ selectedId, onSelect }: CollectionSelectorProps) {
    const { data, loading } = useQuery(COLLECTIONS_QUERY);

    if (loading) return <SelectSkeleton />;

    return (
        <select
            value={selectedId ?? ""}
            onChange={(e) => onSelect(e.target.value)}
            className="rounded-md border px-3 py-2"
        >
            <option value="" disabled>Collection auswaehlen...</option>
            {data.collections.map((col: { id: string; name: string }) => (
                <option key={col.id} value={col.id}>{col.name}</option>
            ))}
        </select>
    );
}
```

### Seitenstruktur (App Router)

```typescript
// frontend/src/app/documents/page.tsx
import { DocumentList } from "@/components/documents/DocumentList";
import { CollectionSelector } from "@/components/documents/CollectionSelector";

export default function DocumentsPage() {
    // Server Component mit Client-Subkomponenten
    return (
        <main className="container mx-auto p-6">
            <h1 className="text-2xl font-bold mb-6">Dokumente</h1>
            <CollectionSelector selectedId={null} onSelect={() => {}} />
            <DocumentList collectionId="" />
        </main>
    );
}
```

```typescript
// frontend/src/app/documents/[id]/page.tsx
import { DocumentDetail } from "@/components/documents/DocumentDetail";

interface Props {
    params: { id: string };
}

export default function DocumentDetailPage({ params }: Props) {
    return (
        <main className="container mx-auto p-6">
            <DocumentDetail documentId={params.id} />
        </main>
    );
}
```

```typescript
// frontend/src/app/documents/upload/page.tsx
import { DocumentUpload } from "@/components/documents/DocumentUpload";

export default function UploadPage() {
    return (
        <main className="container mx-auto p-6">
            <h1 className="text-2xl font-bold mb-6">Dokument hochladen</h1>
            <DocumentUpload collectionId="" onUploadComplete={() => {}} />
        </main>
    );
}
```

## Betroffene Dateien

### Backend

Nicht betroffen (die Document-UI nutzt ausschliesslich die bestehende GraphQL-API aus Feature 14).

### Frontend

| Datei                                                      | Aenderung                                            |
|------------------------------------------------------------|------------------------------------------------------|
| `frontend/src/app/documents/page.tsx`                      | NEU - Dokumentenliste-Seite                          |
| `frontend/src/app/documents/[id]/page.tsx`                 | NEU - Dokument-Detailseite                           |
| `frontend/src/app/documents/upload/page.tsx`               | NEU - Upload-Seite                                   |
| `frontend/src/app/documents/layout.tsx`                    | NEU - Layout mit Navigation fuer Documents-Bereich   |
| `frontend/src/components/documents/DocumentUpload.tsx`     | NEU - Drag-and-Drop Upload-Komponente                |
| `frontend/src/components/documents/DocumentList.tsx`       | NEU - Filterbare Dokumentenliste                     |
| `frontend/src/components/documents/DocumentListItem.tsx`   | NEU - Einzelnes Listenelement                        |
| `frontend/src/components/documents/DocumentDetail.tsx`     | NEU - Detailansicht mit Metadaten und Hierarchie     |
| `frontend/src/components/documents/DocumentChunks.tsx`     | NEU - Chunk-Anzeige fuer ein Dokument                |
| `frontend/src/components/documents/DocumentMetadata.tsx`   | NEU - Metadaten-Panel                                |
| `frontend/src/components/documents/DocumentHierarchy.tsx`  | NEU - Parent-Child-Baumansicht                       |
| `frontend/src/components/documents/CollectionSelector.tsx` | NEU - Collection-Dropdown                            |
| `frontend/src/components/documents/DocumentFilterBar.tsx`  | NEU - Filter nach Typ, Status, Suchtext              |
| `frontend/src/components/documents/ExtractedTriples.tsx`   | NEU - Anzeige extrahierter Triples fuer ein Dokument |
| `frontend/src/graphql/queries/document.ts`                 | NEU - GraphQL-Query-Definitionen                     |
| `frontend/src/graphql/mutations/document.ts`               | NEU - GraphQL-Mutation-Definitionen                  |
| `frontend/src/graphql/subscriptions/extraction.ts`         | NEU - Subscription fuer Extraktionsfortschritt       |
| `frontend/src/types/document.ts`                           | NEU - TypeScript-Typen fuer Dokumente                |

### Tests

| Datei                                                                     | Aenderung                                            |
|---------------------------------------------------------------------------|------------------------------------------------------|
| `frontend/src/__tests__/components/documents/DocumentUpload.test.tsx`     | NEU - Upload-Tests (Drag-and-Drop, Fehlerbehandlung) |
| `frontend/src/__tests__/components/documents/DocumentList.test.tsx`       | NEU - Listendarstellung, Filterung, Pagination       |
| `frontend/src/__tests__/components/documents/DocumentDetail.test.tsx`     | NEU - Detailansicht, Chunk-Anzeige                   |
| `frontend/src/__tests__/components/documents/CollectionSelector.test.tsx` | NEU - Auswahlverhalten, Ladeindikator                |
| `frontend/src/__tests__/pages/documents.test.tsx`                         | NEU - Seitenintegrationstests                        |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                                           |
|-------------------|-------------|-----------------------------------------------------------------|
| Spring Boot (JVM) | Ja          | Frontend kommuniziert ueber GraphQL-API mit Spring Boot Backend |
| KMP Library       | Nein        | Reines Frontend-Feature, kein Backend-Code betroffen            |
| Ktor/Wasm         | Nein        | Reines Frontend-Feature, kein Backend-Code betroffen            |

## Akzeptanzkriterien

- [ ] Seite `/documents` zeigt eine filterbare Liste aller Dokumente der ausgewaehlten Collection
- [ ] Collection-Selektor ermoeglicht Wechsel zwischen Collections
- [ ] Filter nach Dokumenttyp (SOURCE, PAGE, CHUNK) und Status (UPLOADED, PROCESSING, EXTRACTED, FAILED) funktioniert
- [ ] Volltextsuche in der Dokumentenliste filtert nach Titel
- [ ] Pagination funktioniert korrekt mit Seitenanzeige und Navigation
- [ ] Seite `/documents/upload` bietet Drag-and-Drop und File-Picker fuer den Upload
- [ ] Upload-Fortschrittsanzeige zeigt den aktuellen Stand (Upload, Extraktion)
- [ ] Extraktionsfortschritt wird ueber GraphQL-Subscription in Echtzeit aktualisiert
- [ ] Seite `/documents/[id]` zeigt Metadaten, Parent-Child-Hierarchie und Chunks
- [ ] Extrahierte Triples werden in der Detailansicht angezeigt
- [ ] Fehlerbehandlung: Upload-Fehler und Extraktionsfehler werden dem Benutzer angezeigt
- [ ] Alle Komponenten sind responsiv und auf mobilen Geraeten nutzbar
- [ ] Bestehende Funktionalitaet bleibt unberuehrt
