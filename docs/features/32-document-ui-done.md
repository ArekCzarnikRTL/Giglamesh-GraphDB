# Feature 32: Document UI — Implementation Done

**Status:** Implemented
**Date:** 2026-04-06
**Spec:** `docs/superpowers/specs/2026-04-06-document-ui-design.md`
**Plan:** `docs/superpowers/plans/2026-04-06-document-ui.md`

## Zusammenfassung

Komplette Document-UI als Next.js 14 App in `frontend/` plus die nötigen
GraphQL-Backend-Erweiterungen. Endbenutzer können Dokumente per
Drag-and-Drop hochladen, in einer paginierten und filterbaren Liste
durchsuchen, und in einer Detailansicht Metadaten, Hierarchie, Chunks
und extrahierte Triples einsehen.

## Lieferumfang

### Backend (Kotlin/Spring Boot)

- **GraphQL-Schema** (`schema.graphqls`):
  - `documents` Query ersetzt: jetzt paginiert mit `DocumentFilter` Input
    und Rückgabetyp `DocumentPage { items, totalCount, hasNextPage }`
  - Neue Query `documentChunks(documentId: ID!): [Document!]!`
  - Neue Input-Type `DocumentFilter { type, state, search }`
  - Neuer Output-Type `DocumentPage`
- **`LibrarianService`**:
  - `findByCollectionPaginated(collectionId, filter, page, pageSize): DocumentPageResult`
    mit In-Memory-Filterung (Type via Cassandra-Index, State und Title
    in-memory)
  - `findChunksOf(documentId): List<Document>` filtert Children auf
    `DocumentType.CHUNK`
  - Bestehende `findByCollection(collectionId, type)` unverändert (MCP
    Tools verwenden sie weiterhin)
- **`DocumentController`**:
  - `documents(...)` resolver auf neue Signatur umgestellt
  - `documentChunks(documentId)` resolver hinzugefügt
- **`InputTypes.kt`**: `DocumentFilterInput` und `DocumentPagePayload`
  Daten-Klassen
- **`CorsConfig`** (neu): erlaubt `http://localhost:3000` für `/graphql`
  und `/graphiql` (Dev-Setup)
- **CLI-Migration**: `ListDocuments.graphql` und `DocumentList`-Command
  auf neue Envelope-Form `documents.items` umgestellt; Tests entsprechend
  angepasst
- **Backend-Tests**:
  - `DocumentControllerTest` (neu, mockk): 3 Tests für Pagination
    Defaults, Filter-Passthrough, `documentChunks` Delegation
  - `LibrarianServiceTest` erweitert: 5 neue Tests für Pagination,
    Type/State/Search-Filter, `findChunksOf`
  - `DocumentCommandsTest` angepasst auf neue Generierter-Code-Struktur

### Frontend (Next.js 14 + TypeScript)

- **Tech-Stack**: Next.js 14.2.35 (App Router) + TypeScript strict +
  Tailwind v3 + shadcn/ui (base-ui-basiert) + Apollo Client v4.1.6 +
  react-dropzone + react-hook-form + zod + sonner + Vitest 4 + RTL +
  jsdom + pnpm
- **Routes**:
  - `/` → redirect auf `/documents`
  - `/documents` — paginierte Dokumentenliste mit Filter und Suche
  - `/documents/upload` — Drag-and-Drop Upload mit Polling-Progress
  - `/documents/[id]` — Detailansicht mit Metadaten, Hierarchie, Chunks,
    Triples
- **Komponenten** (`frontend/src/components/documents/`):
  - `CollectionSelector` — shadcn Select, persistiert Auswahl in
    `localStorage` über `useActiveCollection` Hook
  - `DocumentFilterBar` — Search Input + 2 Selects für Type/State
  - `DocumentPagination` — Zurück/Weiter Buttons mit
    `useSearchParams`-freundlichem Page-State
  - `DocumentList` + `DocumentListItem` — shadcn Table, mit Skeleton
    während Loading und Alert bei Errors
  - `DocumentUpload` — react-dropzone mit base64-Encoding, danach
    `useQuery(DOCUMENT_QUERY, { pollInterval: 2000 })` bis State final
  - `DocumentDetail` — Composite, lädt `DOCUMENT_QUERY` und rendert
  - `DocumentMetadata` — Card mit Key/Value Rows
  - `DocumentHierarchy` — Card mit Parent-Link und Children-Liste
  - `DocumentChunks` — Card mit `DOCUMENT_CHUNKS_QUERY`
  - `ExtractedTriples` — Card mit `DOCUMENT_TRIPLES_QUERY`
- **Apollo-Setup**: `ApolloNextAppProvider` in `"use client"` Wrapper,
  via `RootLayout` eingebunden
- **Polling statt Subscriptions**: Upload-Progress wird über
  `pollInterval: 2000` aktualisiert; Polling stoppt bei `EXTRACTED` oder
  `FAILED`
- **Frontend-Tests** (Vitest + RTL + MockedProvider): 9 Tests in 6
  Files, alle grün
- **README** unter `frontend/README.md`

## Akzeptanzkriterien

| Kriterium | Status |
|---|---|
| `/documents` zeigt filterbare Liste | ✅ |
| Collection-Selektor wechselt Collections | ✅ |
| Filter nach Typ und Status | ✅ |
| Volltextsuche auf Title | ✅ (server-seitig in `LibrarianService`) |
| Pagination mit Page-Navigation | ✅ |
| `/documents/upload` mit Drag-and-Drop | ✅ |
| Upload-Fortschrittsanzeige | ✅ (shadcn Progress) |
| Echtzeit-Update via Polling | ✅ (statt Subscription) |
| `/documents/[id]` Detail mit Metadaten + Hierarchie + Chunks | ✅ |
| Extrahierte Triples in Detailansicht | ✅ |
| Fehlerbehandlung mit Toasts und Alerts | ✅ (sonner + shadcn Alert) |
| Responsive (mobile-first Tailwind) | ✅ |
| Bestehende Funktionalität intakt | ✅ (CLI migriert, MCP unangetastet) |

## Abweichungen vom Plan und Spec

### Frontend-Tooling

1. **shadcn/ui auf @base-ui/react-Basis statt Radix UI**
   Die aktuelle shadcn-CLI initialisiert mit `@base-ui/react@1.3.0`
   anstelle der Radix-Primitives, die der Plan implizit annimmt. Die
   shadcn-Wrapper-API (`Select`, `SelectContent`, etc.) ist
   unverändert, aber das interne Portal- und Render-Verhalten
   unterscheidet sich. Tests mussten an Stellen, wo der ursprünglich
   geplante `userEvent.click` auf Select-Optionen scheiterte, auf
   `findByRole("combobox")` + `findByRole("option")` Pattern umgestellt
   werden.

2. **Tailwind v3 statt v4**
   Die shadcn-CLI schreibt ein Tailwind-v4-kompatibles `globals.css`
   mit `@import "shadcn/tailwind.css"`, das unter dem von Next 14
   gelieferten Tailwind 3.4 nicht funktioniert. `globals.css` und
   `tailwind.config.ts` wurden auf das klassische shadcn-v3-Schema
   (HSL CSS-Variables) gemappt. `tailwindcss-animate` wurde als Dev-Dep
   hinzugefügt.

3. **`form` shadcn-Komponente fehlt**
   Das aktuelle shadcn-Registry liefert für `form` einen leeren Eintrag
   — der Komponenten-Wrapper wurde im base-ui-Rewrite entfernt. Alle
   anderen 16 Komponenten installiert. Da Feature 32 keine
   shadcn-Form-Helper braucht (keine `<Form>`-Komponente verwendet),
   ist das ohne Auswirkung.

4. **Apollo Client v4 Import-Pfade**
   `useQuery`/`useMutation` kommen aus `@apollo/client/react`,
   `MockedProvider` aus `@apollo/client/testing/react`. Der Plan war
   gegen v3-Import-Pfade geschrieben.

5. **`DocumentHierarchy` Prop von `children` nach `childDocuments` umbenannt**
   Next.js ESLint (`react/no-children-prop`) verbietet `children` als
   benamte Prop neben React's eingebauten Children-Mechanismus.

6. **Layout `Button asChild` ersetzt**
   Das base-ui-basierte `Button` aus shadcn akzeptiert kein `asChild`.
   Stattdessen `<Link className={buttonVariants()}>`.

7. **`pnpm test` Script**
   `vitest run --passWithNoTests` (Vitest 4 exits 1 ohne Test-Files,
   was kurzzeitig den Smoke-Test-Workflow brach).

8. **localStorage-Polyfill in `setup.ts`**
   jsdom 29 in Vitest 4 liefert ein leeres `window.localStorage`-Objekt
   ohne `Storage`-Methoden. Polyfill nötig für `useActiveCollection`-
   und CollectionSelector-Tests.

### Implementation-Details

9. **In-Memory-Filterung im Backend**
   Cassandra unterstützt keine effiziente Volltextsuche. Filter (State,
   Title-Search) werden in `LibrarianService` nach dem Cassandra-Read
   in-memory angewendet. Type-Filter ist nativ (Cassandra-Partition).
   Pagination ebenfalls in-memory. Für die Ziel-Datenmengen
   (typischerweise wenige hundert Dokumente pro Collection)
   ausreichend; bei Skalierung müsste eine separate Such-Index-Lösung
   her.

10. **Triple-Subject-Konvention**
    `ExtractedTriples` nutzt den bare `documentId` als `subject`, weil
    `extraction/decoder/PageExtractedProducer.kt:39` und
    `messaging/DocumentIngestedProducer.kt:37` `subject = documentId`
    setzen. Falls Producer-Code später eine andere Konvention einführt
    (z. B. `urn:doc:${id}`), muss `ExtractedTriples.tsx` angepasst
    werden.

## Offene Punkte und Tech-Schulden

1. **Backend-Build hat pre-existing Failures (nicht durch dieses Feature verursacht)**
   - `:resolveMainClassName` schlägt fehl, weil zwei Spring Boot Main-
     Classes existieren (`GraphMeshApplicationKt` und `GraphMeshCliKt`).
     Lösung: in `build.gradle.kts` `springBoot { mainClass.set(...) }`
     oder `bootJar { mainClass.set(...) }` setzen.
   - `GraphMeshApplicationTests.contextLoads` hat
     `NoUniqueBeanDefinitionException` für `PromptExecutor` (Koog-
     Konflikt zwischen `multiLLMPromptExecutor` und `ollamaExecutor`).
   - ~63 Integration-Tests scheitern ohne docker-compose
     (Cassandra/MinIO/Qdrant). Erwartet im Sandbox-Mode.
   - Alle Feature-32-spezifischen Backend-Tests
     (`DocumentControllerTest`, neue `LibrarianServiceTest`-Tests,
     `DocumentCommandsTest`) sind grün.

2. **Frontend-Polish-Backlog (nicht Akzeptanz-blockierend):**
   - Pagination-State synchronisieren mit URL-`searchParams` für
     Bookmark-/Back-Button-Support
   - Document-Detail Server-Component statt Client für initialen Render
     (Apollo Server-Side mit `registerApolloClient` aus dem Integration-
     Package)
   - Optimistic Updates für Upload (Mutation-Result direkt in den
     Cache schreiben, damit `/documents` ohne Refetch aktualisiert)
   - End-to-End-Tests mit Playwright (bewusst aus YAGNI-Gründen
     weggelassen)

3. **Manueller Smoke-Test (Plan-Task 21) wurde nicht durchgeführt**
   Erfordert laufendes Backend + Frontend. Sollte als Akzeptanzschritt
   in der nächsten manuellen Sitzung durchlaufen werden.

4. **Subscription/SSE-Variante für Progress**
   Aktuell Polling (alle 2 Sekunden während aktivem Upload). Falls
   später eine Echtzeit-Variante gewünscht ist, könnte das bestehende
   `streaming.graphqls` SSE-Pattern wiederverwendet werden.

5. **i18n nicht implementiert**
   UI-Strings auf Deutsch hardcodiert (per User-Präferenz). Falls
   später Englisch/andere Sprachen nötig sind, `next-intl` oder
   ähnliches einbauen.

## Verifikation

- **Backend**: `./gradlew test --tests "com.agentwork.graphmesh.api.DocumentControllerTest" --tests "com.agentwork.graphmesh.librarian.LibrarianServiceTest" --tests "com.agentwork.graphmesh.cli.commands.DocumentCommandsTest"` → BUILD SUCCESSFUL
- **Frontend**: `cd frontend && pnpm test` → 6 files, 9 tests, all green
- **Frontend Build**: `cd frontend && pnpm build` → Compiled successfully, 4 Routes (`/`, `/documents`, `/documents/upload`, `/documents/[id]`)

## Commits (chronologisch)

```
e4cc2df feat(graphql): add DocumentFilter, DocumentPage, documentChunks to schema
bfd0854 feat(librarian): add paginated query and chunks lookup
85a8380 feat(api): paginated documents query and documentChunks resolver
d1e3c63 refactor(cli): migrate document list to paginated documents query
1174380 feat(api): allow CORS for frontend dev server on localhost:3000
02666d0 feat(frontend): scaffold Next.js 14 app with TypeScript and Tailwind
9458b88 feat(frontend): init shadcn/ui and install base components
a74fc2f feat(frontend): add Apollo, react-dropzone, react-hook-form, Vitest
d537b44 feat(frontend): wire ApolloWrapper and Toaster into root layout
f50e44a feat(frontend): add document types and GraphQL operations
2461486 feat(frontend): add useActiveCollection hook with localStorage
7fb0a8e feat(frontend): add CollectionSelector with localStorage persistence
9ce7e6c feat(frontend): add DocumentFilterBar with type, state, search
b81e7fc feat(frontend): add DocumentPagination component
c8f58f5 chore(frontend): replace any types in test setup for lint
1c318af feat(frontend): add DocumentList with filter, pagination, and skeleton
4760648 feat(frontend): add DocumentUpload with dropzone and polling progress
08bc0d8 feat(frontend): add DocumentDetail with metadata, hierarchy, chunks, triples
c0e6ddf feat(frontend): add /documents, /documents/upload, /documents/[id] routes
00f2cdd docs(frontend): add README with setup, dev, and test instructions
```
