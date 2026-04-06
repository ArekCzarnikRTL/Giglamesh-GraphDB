# Feature 32: Document UI — Design Spec

**Status:** Approved
**Date:** 2026-04-06
**Feature Doc:** `docs/features/32-document-ui.md`

## Problem

GraphMesh hat keine grafische Oberfläche für Endbenutzer. Dokumente
müssen aktuell über GraphiQL oder das CLI hochgeladen, gefiltert und
inspiziert werden. Das schließt Nicht-Entwickler aus.

## Ziel

Eine vollständige Next.js-Document-UI in `frontend/`, die den
Dokumentenlebenszyklus von Upload bis Detailansicht abdeckt, plus
die minimal notwendigen Backend-Erweiterungen, um die UI sauber zu
unterstützen.

## Scope: Zwei Blöcke

### Block A — Backend-Erweiterungen (Spring Boot, Kotlin)

Rein additive bzw. controlled-replacement Schema-Änderungen:

1. **`documents`-Query ersetzen** durch paginierte Variante.
2. **`documentChunks(documentId)`-Query** neu hinzufügen.
3. **`DocumentFilter` Input** und **`DocumentPage` Type** neu hinzufügen.
4. **Kein Subscription-/WebSocket-Setup.** Frontend pollt für Progress.

#### Schema-Patch (`src/main/resources/graphql/schema.graphqls`)

```graphql
type Query {
    collections(tags: [String]): [Collection!]!
    collection(id: ID!): Collection

    # ERSETZT alte documents-Query (signature change)
    documents(
        collectionId: ID!
        filter: DocumentFilter
        page: Int = 0
        pageSize: Int = 20
    ): DocumentPage!

    document(id: ID!): Document
    documentChunks(documentId: ID!): [Document!]!

    triples(
        collectionId: ID!
        subject: String
        predicate: String
        object: String
        dataset: String
    ): [Quad!]!

    vectorSearch(
        collectionId: ID!
        query: String!
        limit: Int = 10
    ): [SearchResult!]!
}

input DocumentFilter {
    type: DocumentType
    state: DocumentState
    search: String
}

type DocumentPage {
    items: [Document!]!
    totalCount: Int!
    hasNextPage: Boolean!
}
```

#### Controller-Änderungen

- `DocumentController.documents(...)` Signatur und Rückgabetyp anpassen.
  Repository-Aufruf via existierendem `LibrarianService` /
  Cassandra-Repository erweitern um `findByCollectionIdPaginated(filter, page, pageSize)`.
  Title-Suche server-seitig (case-insensitive `contains`).
- `DocumentController.documentChunks(documentId)`: liefert
  `findByParentIdAndType(documentId, DocumentType.CHUNK)`.
- Alle bestehenden Aufrufer der alten `documents`-Query im Repo (CLI,
  Tests, MCP-Tools, andere Controller) auf neue Signatur migrieren.

#### Backend-Tests

`DocumentControllerTest` erweitern:
- Pagination (page/pageSize, totalCount, hasNextPage)
- Filter (type, state, search)
- documentChunks liefert nur CHUNK-Children

### Block B — Frontend (`frontend/`, Next.js 14)

#### Tech-Stack

| Schicht | Wahl | Begründung |
|---|---|---|
| Framework | Next.js 14 (App Router) | Feature-Doc-Vorgabe |
| Sprache | TypeScript (strict) | Standard |
| Styling | Tailwind CSS v4 | shadcn/ui benötigt es |
| Komponenten | shadcn/ui | Feature-Doc-Vorgabe |
| GraphQL-Client | `@apollo/client` + `@apollo/client-integration-nextjs` | App-Router-tauglich |
| Forms | react-hook-form + zod + `@hookform/resolvers/zod` | shadcn-Standard |
| Drag&Drop | react-dropzone | Feature-Doc-Vorgabe |
| Notifications | sonner | shadcn-Standard |
| Tests | Vitest + React Testing Library + jsdom | schneller, moderner als Jest |
| Package-Manager | pnpm | schneller, deterministisches Lockfile |

#### Verzeichnisstruktur

```
frontend/
├── package.json
├── pnpm-lock.yaml
├── tsconfig.json
├── next.config.mjs
├── tailwind.config.ts
├── postcss.config.mjs
├── components.json              # shadcn config
├── vitest.config.ts
├── .env.local.example           # NEXT_PUBLIC_GRAPHQL_URL
├── src/
│   ├── app/
│   │   ├── layout.tsx           # ApolloWrapper + Toaster
│   │   ├── globals.css
│   │   ├── page.tsx             # redirect → /documents
│   │   └── documents/
│   │       ├── layout.tsx       # CollectionSelector im Header
│   │       ├── page.tsx         # Liste
│   │       ├── upload/page.tsx
│   │       └── [id]/page.tsx
│   ├── components/
│   │   ├── ui/                  # shadcn-Komponenten (button, table, ...)
│   │   └── documents/
│   │       ├── DocumentList.tsx
│   │       ├── DocumentListItem.tsx
│   │       ├── DocumentFilterBar.tsx
│   │       ├── DocumentPagination.tsx
│   │       ├── DocumentUpload.tsx
│   │       ├── DocumentDetail.tsx
│   │       ├── DocumentMetadata.tsx
│   │       ├── DocumentHierarchy.tsx
│   │       ├── DocumentChunks.tsx
│   │       ├── ExtractedTriples.tsx
│   │       └── CollectionSelector.tsx
│   ├── lib/
│   │   ├── apollo-client.ts     # makeClient()
│   │   ├── apollo-wrapper.tsx   # ApolloNextAppProvider, "use client"
│   │   └── collection-store.ts  # localStorage-helpers für aktive Collection
│   ├── graphql/
│   │   ├── queries.ts           # gql tags
│   │   └── mutations.ts
│   └── types/
│       └── document.ts          # handgepflegte TS-Typen
└── src/__tests__/
    └── components/documents/    # Vitest-Suiten
```

#### shadcn-Komponenten (initial via `npx shadcn add`)

`button`, `card`, `table`, `input`, `select`, `dialog`, `sonner`,
`badge`, `skeleton`, `alert`, `separator`, `tabs`, `tooltip`, `label`,
`form`, `dropdown-menu`, `progress`.

#### Routen

| Route | Zweck | Komponenten |
|---|---|---|
| `/` | redirect → `/documents` | — |
| `/documents` | Liste mit Filter, Suche, Pagination | `CollectionSelector`, `DocumentFilterBar`, `DocumentList`, `DocumentPagination` |
| `/documents/upload` | Drag&Drop Upload mit Polling-Progress | `DocumentUpload` |
| `/documents/[id]` | Detail: Metadaten, Hierarchie, Chunks, Triples | `DocumentDetail` (Composite) |

#### Apollo-Setup

`src/lib/apollo-wrapper.tsx`:
```tsx
"use client";
import { HttpLink } from "@apollo/client";
import {
  ApolloNextAppProvider,
  ApolloClient,
  InMemoryCache,
} from "@apollo/client-integration-nextjs";

function makeClient() {
  return new ApolloClient({
    cache: new InMemoryCache(),
    link: new HttpLink({
      uri: process.env.NEXT_PUBLIC_GRAPHQL_URL ?? "http://localhost:8080/graphql",
    }),
  });
}

export function ApolloWrapper({ children }: { children: React.ReactNode }) {
  return <ApolloNextAppProvider makeClient={makeClient}>{children}</ApolloNextAppProvider>;
}
```

In `app/layout.tsx`:
```tsx
<ApolloWrapper>
  {children}
  <Toaster />
</ApolloWrapper>
```

#### Progress-Mechanismus (Polling, kein WebSocket)

```tsx
const { data, stopPolling } = useQuery(DOCUMENT_QUERY, {
  variables: { id: documentId },
  pollInterval: 2000,
});

useEffect(() => {
  const state = data?.document?.state;
  if (state === "EXTRACTED" || state === "FAILED") stopPolling();
}, [data, stopPolling]);
```

`DocumentUpload` startet nach erfolgreichem `uploadDocument` ein
Polling-Subkomponente, die einen `<Progress>`-Balken anhand des
`state`-Übergangs (UPLOADED → PROCESSING → EXTRACTED/FAILED) anzeigt.

#### Collection-Selector

- Lädt `collections` einmalig beim Mount
- Persistiert ausgewählte Collection in `localStorage` unter
  `graphmesh.activeCollectionId`
- Hooks: `useActiveCollection()` liefert `{ id, set }` aus dem Store
- Wenn keine Collection gewählt: zeige Hinweis statt Liste

#### Triples in Detail-Page

Bestehende `triples`-Query nutzen:
```graphql
query DocumentTriples($collectionId: ID!, $subject: String!) {
  triples(collectionId: $collectionId, subject: $subject) {
    subject predicate object objectType
  }
}
```
Subject-Konvention: das verwendete Schema im Backend (z. B. `doc:${id}`)
muss in der Implementierungsphase verifiziert werden — wenn die
Konvention abweicht, wird die Subject-Generierung im Frontend
entsprechend angepasst.

## Datenfluss

```
Browser
  │
  ├── /documents (Liste)
  │      └── useQuery(DOCUMENTS, { collectionId, filter, page, pageSize })
  │            └── HTTP POST /graphql → DocumentController.documents()
  │
  ├── /documents/upload
  │      ├── react-dropzone → file → fileToBase64
  │      ├── useMutation(UPLOAD_DOCUMENT)
  │      └── nach Erfolg: Polling von document(id) bis state final
  │
  └── /documents/[id]
         ├── useQuery(DOCUMENT, { id })
         ├── useQuery(DOCUMENT_CHUNKS, { documentId: id })
         └── useQuery(DOCUMENT_TRIPLES, { collectionId, subject })
```

## Akzeptanzkriterien-Mapping

| Kriterium aus Feature 32 | Lösung |
|---|---|
| `/documents` filterbare Liste | `DocumentList` + `DocumentFilterBar` mit shadcn |
| Collection-Selector | `CollectionSelector` im `documents/layout.tsx` Header |
| Filter Typ + Status | `DocumentFilter.type`, `.state` server-seitig |
| Volltext-Suche | `DocumentFilter.search` server-seitig auf Title |
| Pagination | `page`/`pageSize` + `DocumentPagination`-Komponente, URL-state via `useSearchParams` |
| `/documents/upload` Drag&Drop | `react-dropzone` + base64-Encoding |
| Upload-Fortschritt | `<Progress>` von shadcn, 4 Stufen (Upload → UPLOADED → PROCESSING → EXTRACTED) |
| Echtzeit-Update | Polling alle 2s bis Final-State |
| `/documents/[id]` Metadaten + Hierarchie + Chunks | `DocumentDetail` mit 3 Sektionen |
| Extrahierte Triples | `ExtractedTriples` via `triples`-Query |
| Fehlerbehandlung | `sonner` Toasts + Inline `<Alert>` |
| Responsive | Tailwind mobile-first, Tabelle ab `md:` |
| Bestehendes intakt | nur additive Backend-Changes außer `documents`-Signatur |

## Tests

### Backend
`src/test/kotlin/.../api/DocumentControllerTest.kt` erweitern:
- `documents` mit Pagination liefert korrekte `totalCount` und `hasNextPage`
- `documents` mit `filter.type` filtert
- `documents` mit `filter.state` filtert
- `documents` mit `filter.search` filtert auf Title (case-insensitive)
- `documentChunks` liefert nur CHUNK-typed Children

### Frontend (Vitest + RTL + Apollo MockedProvider)
- `DocumentList.test.tsx` — rendert items, zeigt Loading/Error
- `DocumentFilterBar.test.tsx` — Filter-Änderungen lösen onChange aus
- `DocumentPagination.test.tsx` — Page-Navigation
- `DocumentUpload.test.tsx` — Drag&Drop-Akzeptanz, base64-Encoding, Mutation-Aufruf
- `DocumentDetail.test.tsx` — rendert Metadaten + Chunks
- `CollectionSelector.test.tsx` — localStorage-Persistenz

## Bewusst NICHT enthalten (YAGNI)

- GraphQL Subscriptions / WebSockets
- graphql-codegen (Types handgepflegt)
- Storybook
- Playwright/E2E
- Auth/Login (Backend hat aktuell auch keinen)
- i18n (UI-Strings auf Deutsch)
- Server Components für Document-Daten (alles Client Components, da
  Apollo-State und Polling clientseitig nötig)

## Risiken und Mitigationen

| Risiko | Mitigation |
|---|---|
| `documents`-Signature-Change bricht andere Aufrufer | Vorab im Repo grep, alle Aufrufer in derselben Branch migrieren, Build/Test grün |
| Triples-Subject-Konvention unbekannt | In Implementierungsphase aus Feature 07/12 Code lesen, ggf. Frontend-seitig anpassen |
| CORS Spring Boot ↔ Next.js dev | `WebMvcConfigurer` mit `addMapping("/graphql").allowedOrigins("http://localhost:3000")` |
| Polling belastet Backend | nur aktiv solange state nicht-final, max. ~10 Sekunden pro Upload |
| Tailwind v4 Breaking Changes | shadcn-CLI nutzt aktuelle Defaults, sollte sauber bootstrappen |

## Implementierungs-Reihenfolge (für Plan)

1. Backend-Schema erweitern + Controller + Tests
2. Alle bestehenden Aufrufer der alten `documents`-Query migrieren
3. CORS für `/graphql` öffnen (dev)
4. `frontend/` initialisieren (`pnpm create next-app`, shadcn init, Apollo)
5. Apollo-Wrapper + `app/layout.tsx`
6. GraphQL-Queries/Mutations + TS-Typen
7. `CollectionSelector` + `useActiveCollection`-Store
8. `DocumentList` + `DocumentFilterBar` + `DocumentPagination` + Route `/documents`
9. `DocumentUpload` + Polling + Route `/documents/upload`
10. `DocumentDetail` + Subkomponenten + Route `/documents/[id]`
11. Vitest-Suiten
12. README für `frontend/` mit Run-Anleitung
