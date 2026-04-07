# Feature 35: Admin UI — Design

**Status:** Approved (2026-04-07)
**Scope:** Frontend-only (Option A from brainstorming)
**Source:** `docs/features/35-admin-ui.md` (with documented divergences)

## Problem

GraphMesh has no central administration surface. Operators currently use GraphiQL,
config files, or direct service calls to manage collections, edit configuration, or
inspect the extraction pipeline.

## Goal

Add an `/admin` area to the existing Next.js frontend that bundles four operator
workflows behind a single sidebar:

1. **Dashboard** — at-a-glance view of collections and their pipeline backlog
2. **Collection Manager** — full CRUD over collections
3. **Config Editor** — read/write `ConfigEntry` items grouped by `ConfigType`
4. **Pipeline Status** — list of `PROCESSING` and `FAILED` documents per collection

## Scope decisions and divergences from `docs/features/35-admin-ui.md`

The original feature doc assumes GraphQL fields that **do not exist** in the backend.
Verified by reading `src/main/resources/graphql/*.graphqls` on 2026-04-07.

| Doc claim                                       | Reality                                                        | Decision                                  |
|-------------------------------------------------|----------------------------------------------------------------|-------------------------------------------|
| `systemHealth` query (services/Kafka/Cassandra) | Does not exist; no Spring Boot Actuator dependency             | **Out of scope**, no health page          |
| `pipelineStatus` query (aggregate stats)        | Does not exist                                                 | **Out of scope**, computed inline instead |
| `pipelineDocuments` query                       | Does not exist                                                 | Use existing `documents(filter:{state})`  |
| `Collection.documentCount` field                | Not in schema                                                  | Compute via `documents` query `totalCount`|
| `configItems` / `updateConfigItem`              | Real names: `configKeys` / `setConfig`                         | Use real names                            |
| `ConfigEntry.updatedAt`                         | Not exposed (only `version`)                                   | Show only `version`                       |
| Inline editing in collection table              | Doesn't fit existing dialog-based UI patterns                  | Use shadcn `Dialog` for create + edit     |
| `confirm()` for delete                          | Inconsistent with the rest of the UI                           | Use shadcn `Dialog` for confirmation      |

This is consistent with the project's "feature doc divergence — always check the actual
codebase first" rule.

## Architecture

### Routes

```
/admin                   → AdminDashboard
/admin/collections       → CollectionManager
/admin/config            → ConfigEditor
/admin/pipeline          → PipelinePanel
```

`/admin/layout.tsx` renders an `AdminSidebar` (4 vertical links with active-state
based on `usePathname`) and a `container mx-auto p-6` content area to the right.

The global `SiteNav` (built earlier today in `frontend/src/components/SiteNav.tsx`)
gains a fourth entry **Admin** pointing at `/admin`.

No new auth: the project has no auth layer yet, so `/admin` is treated like every
other route. Adding auth is a separate, explicit decision that is out of scope here.

### Data flow

All admin GraphQL operations live in **one** new file
`frontend/src/graphql/admin.ts` and are written as `gql` template strings,
matching the existing style in `frontend/src/graphql/queries.ts` (no codegen).

#### Dashboard (`/admin`)

- `ADMIN_DASHBOARD_COLLECTIONS` — `collections(tags: [])` returning
  `id`, `name`, `tags`, `createdAt`.
- For **each** returned collection the dashboard fires two parallel
  `documents(collectionId, filter: { state }, page: 0, pageSize: 1)` queries
  with `state: PROCESSING` and `state: FAILED` and uses `DocumentPage.totalCount`.
  This avoids transferring document items just to count them.
- Apollo `pollInterval: 30000` on the collections query refreshes the dashboard
  every 30 seconds.
- Layout: four `MetricCard`s on top
  (Collections total, Total Processing, Total Failed, Collections with backlog),
  followed by a table of `CollectionStatsRow`s with columns
  *Name | Tags | Processing | Failed | Aktionen* (link to `/admin/pipeline?collectionId=…`).

#### Collection Manager (`/admin/collections`)

- `ADMIN_COLLECTIONS_QUERY` — `collections(tags: [])` with full fields
  (`id`, `name`, `description`, `tags`, `metadata { key value }`, `createdAt`,
  `updatedAt`).
- Mutations `createCollection`, `updateCollection`, `deleteCollection` mirrored
  as gql strings in `admin.ts`.
- After every mutation: `refetchQueries: [ADMIN_COLLECTIONS_QUERY]`. No manual
  cache updates — simpler and matches the YAGNI memory rule.
- Create + Edit share a single `CollectionFormDialog` (shadcn `Dialog`) reused
  with different initial values.
- Delete uses a confirmation `Dialog` instead of `window.confirm`.
- Toast feedback via `sonner` (already wired in the root layout).

#### Config Editor (`/admin/config`)

- `CONFIG_KEYS_QUERY($type: String)` — `configKeys(type: $type)` returning
  `ConfigEntry { id, type, key, value, version }`.
- `SET_CONFIG_MUTATION($key, $value, $type)` — `setConfig(...)`.
- Filter buttons for the seven real `ConfigType` values
  (`ONTOLOGY`, `FLOW`, `TOOL`, `PARAMETER`, `COLLECTION_SETTINGS`,
  `LLM_SETTINGS`, `SCHEMA`). Selecting a filter re-runs the query
  with the new `type` arg.
- One `ConfigItemCard` per entry with an "Bearbeiten"-button that swaps the
  `<pre>` with a plain `<textarea>` (monospace) and Save/Cancel buttons.
  Plain textarea, no syntax highlighting (YAGNI).
- After save: refetch and toast.

#### Pipeline Panel (`/admin/pipeline`)

- Optional `?collectionId=…` URL param. If absent, render the existing
  `CollectionSelector` (from `frontend/src/components/documents/`).
- Two parallel queries:
  - `documents(collectionId, filter: { state: PROCESSING }, page: 0, pageSize: 50)`
  - `documents(collectionId, filter: { state: FAILED }, page: 0, pageSize: 50)`
- Two `PipelineDocumentList` tables stacked vertically
  (columns: *Titel | Erstellt | Aktionen* with a link to `/documents/[id]`).
- Apollo polling: `pollInterval: 10000` on the PROCESSING query, gated by
  `skipPollAttempt: () => processingTotal === 0` so polling stops automatically
  once the backlog is empty.

## Component layout

One component per file. Containers know Apollo, presentational components don't.

```
frontend/src/app/admin/
  layout.tsx                        # Sidebar + container shell
  page.tsx                          # renders <AdminDashboard/>
  collections/page.tsx              # renders <CollectionManager/>
  config/page.tsx                   # renders <ConfigEditor/>
  pipeline/page.tsx                 # renders <PipelinePanel/>

frontend/src/components/admin/
  AdminSidebar.tsx                  # 4 vertical links + active state
  AdminDashboard.tsx                # Apollo + polling, renders MetricCards + table
  MetricCard.tsx                    # presentational (shadcn Card)
  CollectionStatsRow.tsx            # one row in dashboard table
  CollectionManager.tsx             # CRUD container
  CollectionFormDialog.tsx          # create + edit form (shadcn Dialog)
  ConfigEditor.tsx                  # filter buttons + list
  ConfigItemCard.tsx                # single entry, inline edit toggle
  PipelinePanel.tsx                 # collection picker + two lists
  PipelineDocumentList.tsx          # one stateful list (presentational)

frontend/src/graphql/
  admin.ts                          # all admin queries/mutations as gql strings

frontend/src/types/
  admin.ts                          # ConfigType union, view-model types

frontend/src/components/SiteNav.tsx # extended: add /admin link
```

### Type model (`frontend/src/types/admin.ts`)

```typescript
export type ConfigTypeValue =
  | "ONTOLOGY"
  | "FLOW"
  | "TOOL"
  | "PARAMETER"
  | "COLLECTION_SETTINGS"
  | "LLM_SETTINGS"
  | "SCHEMA";

export interface ConfigEntry {
  id: string;
  type: ConfigTypeValue;
  key: string;
  value: string;
  version: number;
}

export interface CollectionStats {
  collection: { id: string; name: string; tags: string[] };
  processingCount: number;
  failedCount: number;
}
```

No types for ServiceStatus / KafkaLagInfo / CassandraStatus — they would be dead
code under Option A.

### Loading and error handling

- **Loading:** shadcn `Skeleton` (already in `components/ui/skeleton.tsx`),
  matching the documents list.
- **Error:** shadcn `Alert` with a short message and a Retry button calling Apollo
  `refetch()`.
- **Mutation feedback:** `sonner` toast — short German strings:
  `"Collection erstellt"`, `"Collection aktualisiert"`, `"Collection gelöscht"`,
  `"Konfiguration gespeichert"`, `"Fehler: <message>"`.
- **Empty states:** explicit messages, e.g. `"Noch keine Collections"` plus a CTA
  button that opens the create dialog.
- **Pipeline without selection:** hint `"Bitte Collection auswählen"`.

## Tests

Vitest + `@apollo/client/testing` `MockedProvider`, mirroring the existing tests
in `frontend/src/__tests__/`. Existing admin-area tests are written in the same
style and accept the same pre-existing `MockedProvider` typing errors that already
affect the rest of the test suite (tracked separately, not part of this feature).

| File                                                                | Coverage                                                                  |
|---------------------------------------------------------------------|---------------------------------------------------------------------------|
| `__tests__/components/admin/AdminDashboard.test.tsx`                | Renders metric cards and rows from mocked collection + count queries      |
| `__tests__/components/admin/CollectionManager.test.tsx`             | Lists collections, opens create dialog, submits create, refetches         |
| `__tests__/components/admin/ConfigEditor.test.tsx`                  | Type filter switches query, save calls `setConfig` and refetches          |
| `__tests__/components/admin/PipelinePanel.test.tsx`                 | Renders PROCESSING + FAILED tables, shows empty state, hides on no select |

Each test focuses on observable behaviour, not implementation details.

## Acceptance criteria

- [ ] `SiteNav` shows an "Admin" link
- [ ] `/admin/layout.tsx` provides a sidebar with four links and an active state
- [ ] `/admin` shows MetricCards (collections total, total processing, total failed,
      collections with backlog) and a per-collection stats table
- [ ] `/admin` polls every 30s
- [ ] `/admin/collections` lists all collections with name, description, tags,
      createdAt, updatedAt
- [ ] Create dialog produces a new collection via `createCollection`
- [ ] Edit dialog updates a collection via `updateCollection`
- [ ] Delete shows a confirmation dialog and calls `deleteCollection`
- [ ] All mutations refetch the list and show a toast
- [ ] `/admin/config` shows seven type filter buttons matching real `ConfigType`
- [ ] Selecting a filter re-runs `configKeys` with the chosen type
- [ ] Editing a value and saving calls `setConfig` and shows the new version
- [ ] `/admin/pipeline` shows PROCESSING and FAILED document lists for a chosen
      collection
- [ ] Polling on the pipeline page stops automatically when the PROCESSING list
      is empty
- [ ] Existing pages (`/documents`, `/graph`, `/query`) keep working unchanged
- [ ] `pnpm test` passes for new admin tests (existing pre-existing TS errors
      in unrelated test files are not blocking)
- [ ] `pnpm build` succeeds

## Out of scope (deliberate)

- System / service health page
- Kafka consumer lag table
- Cassandra connectivity overview
- Spring Boot Actuator dependency
- Any backend Kotlin changes
- Authentication / authorization
- Code editor with syntax highlighting in the config editor
- `documentCount` as a backend GraphQL field
- `updatedAt` on `ConfigEntry`

If any of these become important, they should be tracked as a separate follow-up
feature ("Feature 35b: Admin Health & Observability").
