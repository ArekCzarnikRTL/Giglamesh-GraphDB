# Feature 35: Admin UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a frontend-only `/admin` area with four sub-pages (Dashboard, Collections, Config, Pipeline) backed by the existing GraphQL API. No backend changes.

**Architecture:** New routes under `frontend/src/app/admin/`, a sidebar layout, and four small container components (`AdminDashboard`, `CollectionManager`, `ConfigEditor`, `PipelinePanel`) that consume the existing `collections`, `documents`, `configKeys` queries and `createCollection`/`updateCollection`/`deleteCollection`/`setConfig` mutations. Counts on the dashboard come from cheap `documents(...pageSize:1)` queries reading `totalCount`. Polling via Apollo `pollInterval`.

**Tech Stack:** Next.js 14 (App Router) + Apollo Client v4 + shadcn/ui (base-ui) + Tailwind v3 + Vitest + sonner + lucide-react. All deps already installed.

**Spec:** `docs/superpowers/specs/2026-04-07-feature-35-admin-ui-design.md`

**Conventions:**
- Direct-to-main commits, no PRs.
- Frontend tests: `cd frontend && pnpm test`. Build: `pnpm build`.
- Pre-existing Apollo v4 `MockedProvider` TS errors in `frontend/src/__tests__/` are tracked separately and **must not be touched** by this plan.
- Commit messages: `feat(admin-ui): ...`, `test(admin-ui): ...`. Co-author with Claude.
- All UI strings are German.

---

## File Structure

| Path | Status | Purpose |
|---|---|---|
| `frontend/src/types/admin.ts` | NEW | `ConfigTypeValue`, `ConfigEntry`, `CollectionStats`, view-model types. |
| `frontend/src/graphql/admin.ts` | NEW | All admin GraphQL operations as `gql` strings. |
| `frontend/src/app/admin/layout.tsx` | NEW | Sidebar shell. |
| `frontend/src/app/admin/page.tsx` | NEW | Renders `<AdminDashboard/>`. |
| `frontend/src/app/admin/collections/page.tsx` | NEW | Renders `<CollectionManager/>`. |
| `frontend/src/app/admin/config/page.tsx` | NEW | Renders `<ConfigEditor/>`. |
| `frontend/src/app/admin/pipeline/page.tsx` | NEW | Renders `<PipelinePanel/>`. |
| `frontend/src/components/SiteNav.tsx` | MODIFY | Add `Admin` link. |
| `frontend/src/components/admin/AdminSidebar.tsx` | NEW | Vertical 4-link nav with active state. |
| `frontend/src/components/admin/AdminDashboard.tsx` | NEW | Apollo container, polls every 30s. |
| `frontend/src/components/admin/MetricCard.tsx` | NEW | Presentational metric tile. |
| `frontend/src/components/admin/CollectionStatsRow.tsx` | NEW | One row in dashboard table; fires its own count queries. |
| `frontend/src/components/admin/CollectionManager.tsx` | NEW | CRUD list container. |
| `frontend/src/components/admin/CollectionFormDialog.tsx` | NEW | Create + edit dialog. |
| `frontend/src/components/admin/DeleteConfirmDialog.tsx` | NEW | Reusable delete confirmation dialog. |
| `frontend/src/components/admin/ConfigEditor.tsx` | NEW | Type filter + list container. |
| `frontend/src/components/admin/ConfigItemCard.tsx` | NEW | Single entry with inline edit toggle. |
| `frontend/src/components/admin/PipelinePanel.tsx` | NEW | Picker + two state lists. |
| `frontend/src/components/admin/PipelineDocumentList.tsx` | NEW | Presentational table for one document state. |
| `frontend/src/__tests__/components/admin/AdminDashboard.test.tsx` | NEW | Renders metrics + rows from mocks. |
| `frontend/src/__tests__/components/admin/CollectionManager.test.tsx` | NEW | List + create flow. |
| `frontend/src/__tests__/components/admin/ConfigEditor.test.tsx` | NEW | Filter + save flow. |
| `frontend/src/__tests__/components/admin/PipelinePanel.test.tsx` | NEW | Two lists + empty state. |

---

## Parallelization Note

After **Phase A** (Tasks 1–4) lands on main, the four implementation tracks in **Phase B** (Tasks 5–8) are fully independent and can be dispatched as **four parallel subagents**. Each track owns its own components/page/test files. None of them touch each other's files.

Phase C (Task 9) verifies the merged result.

---

# Phase A — Foundation (sequential)

## Task 1: Admin types and GraphQL operations

**Files:**
- Create: `frontend/src/types/admin.ts`
- Create: `frontend/src/graphql/admin.ts`

- [ ] **Step 1: Create the types file**

```typescript
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
```

- [ ] **Step 2: Create the GraphQL operations file**

```typescript
// frontend/src/graphql/admin.ts

import { gql } from "@apollo/client";

export const ADMIN_COLLECTIONS_QUERY = gql`
  query AdminCollections {
    collections {
      id
      name
      description
      tags
      metadata {
        key
        value
      }
      createdAt
      updatedAt
    }
  }
`;

export const ADMIN_COLLECTION_COUNTS_QUERY = gql`
  query AdminCollectionCounts(
    $collectionId: ID!
    $processingFilter: DocumentFilter
    $failedFilter: DocumentFilter
  ) {
    processing: documents(
      collectionId: $collectionId
      filter: $processingFilter
      page: 0
      pageSize: 1
    ) {
      totalCount
    }
    failed: documents(
      collectionId: $collectionId
      filter: $failedFilter
      page: 0
      pageSize: 1
    ) {
      totalCount
    }
  }
`;

export const CREATE_COLLECTION_MUTATION = gql`
  mutation CreateCollection($input: CreateCollectionInput!) {
    createCollection(input: $input) {
      id
      name
      description
      tags
    }
  }
`;

export const UPDATE_COLLECTION_MUTATION = gql`
  mutation UpdateCollection($id: ID!, $input: UpdateCollectionInput!) {
    updateCollection(id: $id, input: $input) {
      id
      name
      description
      tags
    }
  }
`;

export const DELETE_COLLECTION_MUTATION = gql`
  mutation DeleteCollection($id: ID!) {
    deleteCollection(id: $id)
  }
`;

export const CONFIG_KEYS_QUERY = gql`
  query AdminConfigKeys($type: String) {
    configKeys(type: $type) {
      id
      type
      key
      value
      version
    }
  }
`;

export const SET_CONFIG_MUTATION = gql`
  mutation AdminSetConfig($key: String!, $value: String!, $type: String!) {
    setConfig(key: $key, value: $value, type: $type) {
      id
      type
      key
      value
      version
    }
  }
`;

export const PIPELINE_DOCUMENTS_QUERY = gql`
  query PipelineDocuments(
    $collectionId: ID!
    $processingFilter: DocumentFilter
    $failedFilter: DocumentFilter
  ) {
    processing: documents(
      collectionId: $collectionId
      filter: $processingFilter
      page: 0
      pageSize: 50
    ) {
      items {
        id
        collectionId
        title
        state
        createdAt
      }
      totalCount
    }
    failed: documents(
      collectionId: $collectionId
      filter: $failedFilter
      page: 0
      pageSize: 50
    ) {
      items {
        id
        collectionId
        title
        state
        createdAt
      }
      totalCount
    }
  }
`;
```

- [ ] **Step 3: Verify TypeScript still compiles**

Run: `cd frontend && pnpm exec tsc --noEmit 2>&1 | grep "src/types/admin\|src/graphql/admin"`
Expected: no output (no new errors in these files).

- [ ] **Step 4: Commit**

```bash
cd /Users/czarnik/IdeaProjects/GraphMesh
git add frontend/src/types/admin.ts frontend/src/graphql/admin.ts
git commit -m "$(cat <<'EOF'
feat(admin-ui): add admin types and GraphQL operations

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Admin sidebar component

**Files:**
- Create: `frontend/src/components/admin/AdminSidebar.tsx`

- [ ] **Step 1: Create the sidebar**

```typescript
// frontend/src/components/admin/AdminSidebar.tsx
"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";

const ADMIN_NAV = [
  { href: "/admin", label: "Dashboard", exact: true },
  { href: "/admin/collections", label: "Collections", exact: false },
  { href: "/admin/config", label: "Konfiguration", exact: false },
  { href: "/admin/pipeline", label: "Pipeline", exact: false },
];

export function AdminSidebar() {
  const pathname = usePathname();

  return (
    <nav className="w-56 shrink-0 border-r bg-muted/20 p-4">
      <ul className="space-y-1">
        {ADMIN_NAV.map((item) => {
          const active = item.exact
            ? pathname === item.href
            : pathname === item.href || pathname.startsWith(`${item.href}/`);
          return (
            <li key={item.href}>
              <Link
                href={item.href}
                className={cn(
                  "block rounded-md px-3 py-2 text-sm font-medium transition-colors",
                  active
                    ? "bg-secondary text-secondary-foreground"
                    : "text-muted-foreground hover:bg-secondary/50 hover:text-foreground",
                )}
              >
                {item.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
```

- [ ] **Step 2: Commit**

```bash
cd /Users/czarnik/IdeaProjects/GraphMesh
git add frontend/src/components/admin/AdminSidebar.tsx
git commit -m "$(cat <<'EOF'
feat(admin-ui): add admin sidebar component

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Admin layout and placeholder pages

**Files:**
- Create: `frontend/src/app/admin/layout.tsx`
- Create: `frontend/src/app/admin/page.tsx`
- Create: `frontend/src/app/admin/collections/page.tsx`
- Create: `frontend/src/app/admin/config/page.tsx`
- Create: `frontend/src/app/admin/pipeline/page.tsx`

These pages start as minimal stubs so the routing tree is complete and the four parallel Phase B tracks can each replace the body of one stub independently.

- [ ] **Step 1: Create the layout**

```typescript
// frontend/src/app/admin/layout.tsx
import { ReactNode } from "react";
import { AdminSidebar } from "@/components/admin/AdminSidebar";

export default function AdminLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex h-full">
      <AdminSidebar />
      <div className="flex-1 overflow-auto">
        <div className="container mx-auto p-6">{children}</div>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Create the dashboard page stub**

```typescript
// frontend/src/app/admin/page.tsx
import { AdminDashboard } from "@/components/admin/AdminDashboard";

export default function AdminPage() {
  return (
    <>
      <h1 className="mb-6 text-2xl font-bold">Administration</h1>
      <AdminDashboard />
    </>
  );
}
```

- [ ] **Step 3: Create the collections page stub**

```typescript
// frontend/src/app/admin/collections/page.tsx
import { CollectionManager } from "@/components/admin/CollectionManager";

export default function AdminCollectionsPage() {
  return (
    <>
      <h1 className="mb-6 text-2xl font-bold">Collection-Verwaltung</h1>
      <CollectionManager />
    </>
  );
}
```

- [ ] **Step 4: Create the config page stub**

```typescript
// frontend/src/app/admin/config/page.tsx
import { ConfigEditor } from "@/components/admin/ConfigEditor";

export default function AdminConfigPage() {
  return (
    <>
      <h1 className="mb-6 text-2xl font-bold">Konfiguration</h1>
      <ConfigEditor />
    </>
  );
}
```

- [ ] **Step 5: Create the pipeline page stub**

```typescript
// frontend/src/app/admin/pipeline/page.tsx
import { PipelinePanel } from "@/components/admin/PipelinePanel";

export default function AdminPipelinePage() {
  return (
    <>
      <h1 className="mb-6 text-2xl font-bold">Pipeline</h1>
      <PipelinePanel />
    </>
  );
}
```

- [ ] **Step 6: Create temporary stub components so the imports resolve**

Phase B will replace each of these with the real implementation. They exist now so `pnpm build` succeeds after Phase A.

```typescript
// frontend/src/components/admin/AdminDashboard.tsx
"use client";
export function AdminDashboard() {
  return <p className="text-muted-foreground">Dashboard wird geladen…</p>;
}
```

```typescript
// frontend/src/components/admin/CollectionManager.tsx
"use client";
export function CollectionManager() {
  return <p className="text-muted-foreground">Collection-Verwaltung wird geladen…</p>;
}
```

```typescript
// frontend/src/components/admin/ConfigEditor.tsx
"use client";
export function ConfigEditor() {
  return <p className="text-muted-foreground">Konfiguration wird geladen…</p>;
}
```

```typescript
// frontend/src/components/admin/PipelinePanel.tsx
"use client";
export function PipelinePanel() {
  return <p className="text-muted-foreground">Pipeline wird geladen…</p>;
}
```

- [ ] **Step 7: Verify the build still passes**

Run: `cd frontend && pnpm build 2>&1 | tail -20`
Expected: build succeeds, includes `/admin`, `/admin/collections`, `/admin/config`, `/admin/pipeline` in the route list.

- [ ] **Step 8: Commit**

```bash
cd /Users/czarnik/IdeaProjects/GraphMesh
git add frontend/src/app/admin frontend/src/components/admin/AdminDashboard.tsx frontend/src/components/admin/CollectionManager.tsx frontend/src/components/admin/ConfigEditor.tsx frontend/src/components/admin/PipelinePanel.tsx
git commit -m "$(cat <<'EOF'
feat(admin-ui): scaffold /admin routes with sidebar layout

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Extend SiteNav with Admin link

**Files:**
- Modify: `frontend/src/components/SiteNav.tsx`

- [ ] **Step 1: Add the Admin entry**

Edit the `NAV_ITEMS` array. Find:

```typescript
const NAV_ITEMS = [
  { href: "/documents", label: "Dokumente" },
  { href: "/graph", label: "Graph" },
  { href: "/query", label: "Query" },
];
```

Replace with:

```typescript
const NAV_ITEMS = [
  { href: "/documents", label: "Dokumente" },
  { href: "/graph", label: "Graph" },
  { href: "/query", label: "Query" },
  { href: "/admin", label: "Admin" },
];
```

- [ ] **Step 2: Manually verify by running dev server (optional sanity check)**

Skip if you trust the change.

- [ ] **Step 3: Commit**

```bash
cd /Users/czarnik/IdeaProjects/GraphMesh
git add frontend/src/components/SiteNav.tsx
git commit -m "$(cat <<'EOF'
feat(admin-ui): add Admin link to global SiteNav

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase B — Feature tracks (parallelizable)

After Phase A is on `main`, Tasks 5, 6, 7, 8 are fully independent and can be assigned to **four parallel subagents**. Each owns disjoint files.

---

## Task 5 (Track B1): Dashboard

**Files:**
- Create: `frontend/src/components/admin/MetricCard.tsx`
- Create: `frontend/src/components/admin/CollectionStatsRow.tsx`
- Replace: `frontend/src/components/admin/AdminDashboard.tsx` (currently a stub)
- Create: `frontend/src/__tests__/components/admin/AdminDashboard.test.tsx`

- [ ] **Step 1: Write the failing test**

```typescript
// frontend/src/__tests__/components/admin/AdminDashboard.test.tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import {
  ADMIN_COLLECTIONS_QUERY,
  ADMIN_COLLECTION_COUNTS_QUERY,
} from "@/graphql/admin";
import { AdminDashboard } from "@/components/admin/AdminDashboard";

const mocks = [
  {
    request: { query: ADMIN_COLLECTIONS_QUERY },
    result: {
      data: {
        collections: [
          {
            id: "col-1",
            name: "Annual Reports",
            description: null,
            tags: ["finance"],
            metadata: [],
            createdAt: "2026-01-01T00:00:00Z",
            updatedAt: "2026-01-01T00:00:00Z",
          },
        ],
      },
    },
  },
  {
    request: {
      query: ADMIN_COLLECTION_COUNTS_QUERY,
      variables: {
        collectionId: "col-1",
        processingFilter: { state: "PROCESSING" },
        failedFilter: { state: "FAILED" },
      },
    },
    result: {
      data: {
        processing: { totalCount: 3 },
        failed: { totalCount: 1 },
      },
    },
  },
];

describe("AdminDashboard", () => {
  it("renders collection stats", async () => {
    render(
      <MockedProvider mocks={mocks} addTypename={false}>
        <AdminDashboard />
      </MockedProvider>,
    );

    // Collection row appears
    expect(await screen.findByText("Annual Reports")).toBeInTheDocument();
    // The metric tile labels are present
    expect(screen.getByText("In Verarbeitung")).toBeInTheDocument();
    expect(screen.getByText("Fehlgeschlagen")).toBeInTheDocument();
    expect(screen.getByText("Mit Backlog")).toBeInTheDocument();
    // Tag from the collection renders (proves the row, not just the metric, mounted)
    expect(screen.getByText("finance")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && pnpm test -- AdminDashboard 2>&1 | tail -20`
Expected: FAIL — the stub `AdminDashboard` only renders "Dashboard wird geladen…".

- [ ] **Step 3: Implement `MetricCard`**

```typescript
// frontend/src/components/admin/MetricCard.tsx
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

interface Props {
  label: string;
  value: string | number;
}

export function MetricCard({ label, value }: Props) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">
          {label}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <p className="text-3xl font-bold">{value}</p>
      </CardContent>
    </Card>
  );
}
```

- [ ] **Step 4: Implement `CollectionStatsRow`**

```typescript
// frontend/src/components/admin/CollectionStatsRow.tsx
"use client";

import Link from "next/link";
import { useQuery } from "@apollo/client/react";
import { ADMIN_COLLECTION_COUNTS_QUERY } from "@/graphql/admin";
import { TableCell, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { AdminCollection } from "@/types/admin";

interface Props {
  collection: AdminCollection;
  onCounts?: (processing: number, failed: number) => void;
}

interface CountsData {
  processing: { totalCount: number };
  failed: { totalCount: number };
}

export function CollectionStatsRow({ collection, onCounts }: Props) {
  const { data, loading } = useQuery<CountsData>(ADMIN_COLLECTION_COUNTS_QUERY, {
    variables: {
      collectionId: collection.id,
      processingFilter: { state: "PROCESSING" },
      failedFilter: { state: "FAILED" },
    },
    fetchPolicy: "cache-and-network",
    pollInterval: 30000,
    onCompleted: (d) => {
      if (onCounts) {
        onCounts(d.processing.totalCount, d.failed.totalCount);
      }
    },
  });

  return (
    <TableRow>
      <TableCell className="font-medium">{collection.name}</TableCell>
      <TableCell>
        <div className="flex flex-wrap gap-1">
          {collection.tags.map((t) => (
            <Badge key={t} variant="secondary">
              {t}
            </Badge>
          ))}
        </div>
      </TableCell>
      <TableCell>
        {loading && !data ? (
          <Skeleton className="h-4 w-8" />
        ) : (
          <span>{data?.processing.totalCount ?? 0}</span>
        )}
      </TableCell>
      <TableCell>
        {loading && !data ? (
          <Skeleton className="h-4 w-8" />
        ) : (
          <span
            className={
              (data?.failed.totalCount ?? 0) > 0
                ? "font-bold text-red-600"
                : undefined
            }
          >
            {data?.failed.totalCount ?? 0}
          </span>
        )}
      </TableCell>
      <TableCell>
        <Link
          href={`/admin/pipeline?collectionId=${collection.id}`}
          className="text-sm text-blue-600 hover:underline"
        >
          Pipeline öffnen
        </Link>
      </TableCell>
    </TableRow>
  );
}
```

- [ ] **Step 5: Replace the stub `AdminDashboard`**

```typescript
// frontend/src/components/admin/AdminDashboard.tsx
"use client";

import { useState } from "react";
import { useQuery } from "@apollo/client/react";
import { ADMIN_COLLECTIONS_QUERY } from "@/graphql/admin";
import { AdminCollection } from "@/types/admin";
import { MetricCard } from "./MetricCard";
import { CollectionStatsRow } from "./CollectionStatsRow";
import {
  Table,
  TableBody,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

interface CollectionsData {
  collections: AdminCollection[];
}

export function AdminDashboard() {
  const { data, loading, error } = useQuery<CollectionsData>(
    ADMIN_COLLECTIONS_QUERY,
    {
      fetchPolicy: "cache-and-network",
      pollInterval: 30000,
    },
  );

  const [counts, setCounts] = useState<
    Record<string, { processing: number; failed: number }>
  >({});

  const handleCounts = (id: string) => (processing: number, failed: number) => {
    setCounts((prev) => ({ ...prev, [id]: { processing, failed } }));
  };

  const totalCollections = data?.collections.length ?? 0;
  const totalProcessing = Object.values(counts).reduce(
    (sum, c) => sum + c.processing,
    0,
  );
  const totalFailed = Object.values(counts).reduce(
    (sum, c) => sum + c.failed,
    0,
  );
  const collectionsWithBacklog = Object.values(counts).filter(
    (c) => c.processing > 0,
  ).length;

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <MetricCard label="Collections" value={totalCollections} />
        <MetricCard label="In Verarbeitung" value={totalProcessing} />
        <MetricCard label="Fehlgeschlagen" value={totalFailed} />
        <MetricCard label="Mit Backlog" value={collectionsWithBacklog} />
      </div>

      {loading && !data && (
        <div className="space-y-2">
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
        </div>
      )}

      {error && (
        <Alert variant="destructive">
          <AlertTitle>Fehler</AlertTitle>
          <AlertDescription>{error.message}</AlertDescription>
        </Alert>
      )}

      {data && data.collections.length === 0 && (
        <p className="text-muted-foreground">Noch keine Collections.</p>
      )}

      {data && data.collections.length > 0 && (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead>Tags</TableHead>
              <TableHead>In Verarbeitung</TableHead>
              <TableHead>Fehlgeschlagen</TableHead>
              <TableHead>Aktionen</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.collections.map((c) => (
              <CollectionStatsRow
                key={c.id}
                collection={c}
                onCounts={handleCounts(c.id)}
              />
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd frontend && pnpm test -- AdminDashboard 2>&1 | tail -20`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
cd /Users/czarnik/IdeaProjects/GraphMesh
git add frontend/src/components/admin/MetricCard.tsx frontend/src/components/admin/CollectionStatsRow.tsx frontend/src/components/admin/AdminDashboard.tsx frontend/src/__tests__/components/admin/AdminDashboard.test.tsx
git commit -m "$(cat <<'EOF'
feat(admin-ui): implement admin dashboard with metrics and collection stats

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6 (Track B2): Collection Manager

**Files:**
- Create: `frontend/src/components/admin/CollectionFormDialog.tsx`
- Create: `frontend/src/components/admin/DeleteConfirmDialog.tsx`
- Replace: `frontend/src/components/admin/CollectionManager.tsx` (currently a stub)
- Create: `frontend/src/__tests__/components/admin/CollectionManager.test.tsx`

- [ ] **Step 1: Write the failing test**

```typescript
// frontend/src/__tests__/components/admin/CollectionManager.test.tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import { ADMIN_COLLECTIONS_QUERY } from "@/graphql/admin";
import { CollectionManager } from "@/components/admin/CollectionManager";

const mocks = [
  {
    request: { query: ADMIN_COLLECTIONS_QUERY },
    result: {
      data: {
        collections: [
          {
            id: "col-1",
            name: "Annual Reports",
            description: "Quarterly reports",
            tags: ["finance"],
            metadata: [],
            createdAt: "2026-01-01T00:00:00Z",
            updatedAt: "2026-01-02T00:00:00Z",
          },
        ],
      },
    },
  },
];

describe("CollectionManager", () => {
  it("renders the collections table", async () => {
    render(
      <MockedProvider mocks={mocks} addTypename={false}>
        <CollectionManager />
      </MockedProvider>,
    );

    expect(await screen.findByText("Annual Reports")).toBeInTheDocument();
    expect(screen.getByText("Quarterly reports")).toBeInTheDocument();
    expect(screen.getByText("finance")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /neue collection/i })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && pnpm test -- CollectionManager 2>&1 | tail -20`
Expected: FAIL — stub returns "Collection-Verwaltung wird geladen…".

- [ ] **Step 3: Implement `CollectionFormDialog`**

```typescript
// frontend/src/components/admin/CollectionFormDialog.tsx
"use client";

import { useEffect, useState } from "react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { AdminCollection } from "@/types/admin";

export interface CollectionFormValues {
  name: string;
  description: string;
  tags: string[];
}

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  initial?: AdminCollection | null;
  onSubmit: (values: CollectionFormValues) => Promise<void> | void;
}

export function CollectionFormDialog({
  open,
  onOpenChange,
  initial,
  onSubmit,
}: Props) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [tagsText, setTagsText] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (open) {
      setName(initial?.name ?? "");
      setDescription(initial?.description ?? "");
      setTagsText((initial?.tags ?? []).join(", "));
    }
  }, [open, initial]);

  const handleSubmit = async () => {
    setSubmitting(true);
    try {
      const tags = tagsText
        .split(",")
        .map((t) => t.trim())
        .filter((t) => t.length > 0);
      await onSubmit({ name, description, tags });
      onOpenChange(false);
    } finally {
      setSubmitting(false);
    }
  };

  const isEdit = !!initial;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            {isEdit ? "Collection bearbeiten" : "Neue Collection"}
          </DialogTitle>
          <DialogDescription>
            {isEdit
              ? "Aktualisiere Name, Beschreibung oder Tags."
              : "Lege eine neue Collection an."}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="collection-name">Name</Label>
            <Input
              id="collection-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="collection-description">Beschreibung</Label>
            <Input
              id="collection-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="collection-tags">Tags (kommagetrennt)</Label>
            <Input
              id="collection-tags"
              value={tagsText}
              onChange={(e) => setTagsText(e.target.value)}
            />
          </div>
        </div>
        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={submitting}
          >
            Abbrechen
          </Button>
          <Button onClick={handleSubmit} disabled={submitting || !name.trim()}>
            {isEdit ? "Speichern" : "Erstellen"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

- [ ] **Step 4: Implement `DeleteConfirmDialog`**

```typescript
// frontend/src/components/admin/DeleteConfirmDialog.tsx
"use client";

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  onConfirm: () => Promise<void> | void;
}

export function DeleteConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  onConfirm,
}: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Abbrechen
          </Button>
          <Button
            variant="destructive"
            onClick={async () => {
              await onConfirm();
              onOpenChange(false);
            }}
          >
            Löschen
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

- [ ] **Step 5: Replace the stub `CollectionManager`**

```typescript
// frontend/src/components/admin/CollectionManager.tsx
"use client";

import { useState } from "react";
import { useMutation, useQuery } from "@apollo/client/react";
import { toast } from "sonner";
import {
  ADMIN_COLLECTIONS_QUERY,
  CREATE_COLLECTION_MUTATION,
  UPDATE_COLLECTION_MUTATION,
  DELETE_COLLECTION_MUTATION,
} from "@/graphql/admin";
import { AdminCollection } from "@/types/admin";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import {
  CollectionFormDialog,
  CollectionFormValues,
} from "./CollectionFormDialog";
import { DeleteConfirmDialog } from "./DeleteConfirmDialog";

interface CollectionsData {
  collections: AdminCollection[];
}

export function CollectionManager() {
  const { data, loading, error } = useQuery<CollectionsData>(
    ADMIN_COLLECTIONS_QUERY,
  );

  const [createCollection] = useMutation(CREATE_COLLECTION_MUTATION, {
    refetchQueries: [{ query: ADMIN_COLLECTIONS_QUERY }],
  });
  const [updateCollection] = useMutation(UPDATE_COLLECTION_MUTATION, {
    refetchQueries: [{ query: ADMIN_COLLECTIONS_QUERY }],
  });
  const [deleteCollection] = useMutation(DELETE_COLLECTION_MUTATION, {
    refetchQueries: [{ query: ADMIN_COLLECTIONS_QUERY }],
  });

  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<AdminCollection | null>(null);
  const [deleting, setDeleting] = useState<AdminCollection | null>(null);

  const handleCreate = async (values: CollectionFormValues) => {
    try {
      await createCollection({ variables: { input: values } });
      toast.success("Collection erstellt");
    } catch (err) {
      toast.error(`Fehler: ${(err as Error).message}`);
    }
  };

  const handleUpdate = async (values: CollectionFormValues) => {
    if (!editing) return;
    try {
      await updateCollection({
        variables: { id: editing.id, input: values },
      });
      toast.success("Collection aktualisiert");
    } catch (err) {
      toast.error(`Fehler: ${(err as Error).message}`);
    }
  };

  const handleDelete = async () => {
    if (!deleting) return;
    try {
      await deleteCollection({ variables: { id: deleting.id } });
      toast.success("Collection gelöscht");
    } catch (err) {
      toast.error(`Fehler: ${(err as Error).message}`);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">
          {data ? `${data.collections.length} Collections` : ""}
        </p>
        <Button onClick={() => setCreateOpen(true)}>Neue Collection</Button>
      </div>

      {loading && !data && (
        <div className="space-y-2">
          <Skeleton className="h-9 w-full" />
          <Skeleton className="h-9 w-full" />
          <Skeleton className="h-9 w-full" />
        </div>
      )}

      {error && (
        <Alert variant="destructive">
          <AlertTitle>Fehler</AlertTitle>
          <AlertDescription>{error.message}</AlertDescription>
        </Alert>
      )}

      {data && data.collections.length === 0 && (
        <p className="text-muted-foreground">Noch keine Collections.</p>
      )}

      {data && data.collections.length > 0 && (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead>Beschreibung</TableHead>
              <TableHead>Tags</TableHead>
              <TableHead>Erstellt</TableHead>
              <TableHead>Aktionen</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.collections.map((c) => (
              <TableRow key={c.id}>
                <TableCell className="font-medium">{c.name}</TableCell>
                <TableCell className="text-sm text-muted-foreground">
                  {c.description ?? "—"}
                </TableCell>
                <TableCell>
                  <div className="flex flex-wrap gap-1">
                    {c.tags.map((t) => (
                      <Badge key={t} variant="secondary">
                        {t}
                      </Badge>
                    ))}
                  </div>
                </TableCell>
                <TableCell className="text-sm text-muted-foreground">
                  {c.createdAt}
                </TableCell>
                <TableCell>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => setEditing(c)}
                    >
                      Bearbeiten
                    </Button>
                    <Button
                      size="sm"
                      variant="destructive"
                      onClick={() => setDeleting(c)}
                    >
                      Löschen
                    </Button>
                  </div>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      <CollectionFormDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onSubmit={handleCreate}
      />

      <CollectionFormDialog
        open={!!editing}
        onOpenChange={(o) => !o && setEditing(null)}
        initial={editing}
        onSubmit={handleUpdate}
      />

      <DeleteConfirmDialog
        open={!!deleting}
        onOpenChange={(o) => !o && setDeleting(null)}
        title="Collection löschen?"
        description={`Die Collection "${deleting?.name ?? ""}" und alle zugehörigen Dokumente werden gelöscht.`}
        onConfirm={handleDelete}
      />
    </div>
  );
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd frontend && pnpm test -- CollectionManager 2>&1 | tail -20`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
cd /Users/czarnik/IdeaProjects/GraphMesh
git add frontend/src/components/admin/CollectionFormDialog.tsx frontend/src/components/admin/DeleteConfirmDialog.tsx frontend/src/components/admin/CollectionManager.tsx frontend/src/__tests__/components/admin/CollectionManager.test.tsx
git commit -m "$(cat <<'EOF'
feat(admin-ui): implement collection manager with create/edit/delete

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7 (Track B3): Config Editor

**Files:**
- Create: `frontend/src/components/admin/ConfigItemCard.tsx`
- Replace: `frontend/src/components/admin/ConfigEditor.tsx` (currently a stub)
- Create: `frontend/src/__tests__/components/admin/ConfigEditor.test.tsx`

- [ ] **Step 1: Write the failing test**

```typescript
// frontend/src/__tests__/components/admin/ConfigEditor.test.tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import { CONFIG_KEYS_QUERY } from "@/graphql/admin";
import { ConfigEditor } from "@/components/admin/ConfigEditor";

const mocks = [
  {
    request: { query: CONFIG_KEYS_QUERY, variables: { type: null } },
    result: {
      data: {
        configKeys: [
          {
            id: "ONTOLOGY:default",
            type: "ONTOLOGY",
            key: "default",
            value: "{\"foo\": \"bar\"}",
            version: 2,
          },
        ],
      },
    },
  },
];

describe("ConfigEditor", () => {
  it("renders config entries", async () => {
    render(
      <MockedProvider mocks={mocks} addTypename={false}>
        <ConfigEditor />
      </MockedProvider>,
    );

    expect(await screen.findByText("default")).toBeInTheDocument();
    expect(screen.getByText(/Version 2/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "ONTOLOGY" })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && pnpm test -- ConfigEditor 2>&1 | tail -20`
Expected: FAIL.

- [ ] **Step 3: Implement `ConfigItemCard`**

```typescript
// frontend/src/components/admin/ConfigItemCard.tsx
"use client";

import { useState } from "react";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { ConfigEntry } from "@/types/admin";

interface Props {
  item: ConfigEntry;
  onSave: (value: string) => Promise<void>;
}

export function ConfigItemCard({ item, onSave }: Props) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(item.value);
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    setSaving(true);
    try {
      await onSave(draft);
      setEditing(false);
    } finally {
      setSaving(false);
    }
  };

  const handleCancel = () => {
    setDraft(item.value);
    setEditing(false);
  };

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between space-y-0 pb-2">
        <div>
          <div className="flex items-center gap-2">
            <h4 className="font-medium">{item.key}</h4>
            <Badge variant="outline">{item.type}</Badge>
          </div>
          <p className="text-xs text-muted-foreground">Version {item.version}</p>
        </div>
        {!editing && (
          <Button size="sm" variant="outline" onClick={() => setEditing(true)}>
            Bearbeiten
          </Button>
        )}
      </CardHeader>
      <CardContent>
        {editing ? (
          <div className="space-y-2">
            <textarea
              className="h-48 w-full rounded-md border bg-background p-2 font-mono text-sm"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
            />
            <div className="flex gap-2">
              <Button size="sm" onClick={handleSave} disabled={saving}>
                Speichern
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={handleCancel}
                disabled={saving}
              >
                Abbrechen
              </Button>
            </div>
          </div>
        ) : (
          <pre className="overflow-x-auto rounded-md bg-muted p-2 text-sm">
            {item.value}
          </pre>
        )}
      </CardContent>
    </Card>
  );
}
```

- [ ] **Step 4: Replace the stub `ConfigEditor`**

```typescript
// frontend/src/components/admin/ConfigEditor.tsx
"use client";

import { useState } from "react";
import { useMutation, useQuery } from "@apollo/client/react";
import { toast } from "sonner";
import {
  CONFIG_KEYS_QUERY,
  SET_CONFIG_MUTATION,
} from "@/graphql/admin";
import {
  CONFIG_TYPES,
  ConfigEntry,
  ConfigTypeValue,
} from "@/types/admin";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { ConfigItemCard } from "./ConfigItemCard";

interface ConfigKeysData {
  configKeys: ConfigEntry[];
}

export function ConfigEditor() {
  const [selectedType, setSelectedType] = useState<ConfigTypeValue | null>(null);

  const { data, loading, error, refetch } = useQuery<ConfigKeysData>(
    CONFIG_KEYS_QUERY,
    { variables: { type: selectedType } },
  );

  const [setConfig] = useMutation(SET_CONFIG_MUTATION);

  const handleSave = (item: ConfigEntry) => async (value: string) => {
    try {
      await setConfig({
        variables: { key: item.key, value, type: item.type },
      });
      toast.success("Konfiguration gespeichert");
      refetch();
    } catch (err) {
      toast.error(`Fehler: ${(err as Error).message}`);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant={selectedType === null ? "default" : "outline"}
          onClick={() => setSelectedType(null)}
        >
          Alle
        </Button>
        {CONFIG_TYPES.map((type) => (
          <Button
            key={type}
            size="sm"
            variant={selectedType === type ? "default" : "outline"}
            onClick={() => setSelectedType(type)}
          >
            {type}
          </Button>
        ))}
      </div>

      {loading && !data && (
        <div className="space-y-2">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      )}

      {error && (
        <Alert variant="destructive">
          <AlertTitle>Fehler</AlertTitle>
          <AlertDescription>{error.message}</AlertDescription>
        </Alert>
      )}

      {data && data.configKeys.length === 0 && (
        <p className="text-muted-foreground">Keine Konfigurationen vorhanden.</p>
      )}

      <div className="space-y-3">
        {data?.configKeys.map((item) => (
          <ConfigItemCard
            key={item.id}
            item={item}
            onSave={handleSave(item)}
          />
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd frontend && pnpm test -- ConfigEditor 2>&1 | tail -20`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/czarnik/IdeaProjects/GraphMesh
git add frontend/src/components/admin/ConfigItemCard.tsx frontend/src/components/admin/ConfigEditor.tsx frontend/src/__tests__/components/admin/ConfigEditor.test.tsx
git commit -m "$(cat <<'EOF'
feat(admin-ui): implement config editor with type filtering and inline edit

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8 (Track B4): Pipeline Panel

**Files:**
- Create: `frontend/src/components/admin/PipelineDocumentList.tsx`
- Replace: `frontend/src/components/admin/PipelinePanel.tsx` (currently a stub)
- Create: `frontend/src/__tests__/components/admin/PipelinePanel.test.tsx`

- [ ] **Step 1: Write the failing test**

```typescript
// frontend/src/__tests__/components/admin/PipelinePanel.test.tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import {
  ADMIN_COLLECTIONS_QUERY,
  PIPELINE_DOCUMENTS_QUERY,
} from "@/graphql/admin";
import { PipelinePanel } from "@/components/admin/PipelinePanel";

const mocks = [
  {
    request: { query: ADMIN_COLLECTIONS_QUERY },
    result: {
      data: {
        collections: [
          {
            id: "col-1",
            name: "Annual Reports",
            description: null,
            tags: [],
            metadata: [],
            createdAt: "2026-01-01T00:00:00Z",
            updatedAt: "2026-01-01T00:00:00Z",
          },
        ],
      },
    },
  },
  {
    request: {
      query: PIPELINE_DOCUMENTS_QUERY,
      variables: {
        collectionId: "col-1",
        processingFilter: { state: "PROCESSING" },
        failedFilter: { state: "FAILED" },
      },
    },
    result: {
      data: {
        processing: {
          items: [
            {
              id: "doc-1",
              collectionId: "col-1",
              title: "Report A",
              state: "PROCESSING",
              createdAt: "2026-04-01T00:00:00Z",
            },
          ],
          totalCount: 1,
        },
        failed: {
          items: [
            {
              id: "doc-2",
              collectionId: "col-1",
              title: "Report B",
              state: "FAILED",
              createdAt: "2026-04-02T00:00:00Z",
            },
          ],
          totalCount: 1,
        },
      },
    },
  },
];

describe("PipelinePanel", () => {
  it("renders processing and failed lists for a given collection", async () => {
    render(
      <MockedProvider mocks={mocks} addTypename={false}>
        <PipelinePanel initialCollectionId="col-1" />
      </MockedProvider>,
    );

    expect(await screen.findByText("Report A")).toBeInTheDocument();
    expect(screen.getByText("Report B")).toBeInTheDocument();
    expect(screen.getByText(/In Verarbeitung/)).toBeInTheDocument();
    expect(screen.getByText(/Fehlgeschlagen/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && pnpm test -- PipelinePanel 2>&1 | tail -20`
Expected: FAIL.

- [ ] **Step 3: Implement `PipelineDocumentList`**

```typescript
// frontend/src/components/admin/PipelineDocumentList.tsx
"use client";

import Link from "next/link";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { PipelineDocument } from "@/types/admin";

interface Props {
  title: string;
  documents: PipelineDocument[];
  emptyMessage: string;
}

export function PipelineDocumentList({ title, documents, emptyMessage }: Props) {
  return (
    <section className="space-y-2">
      <h2 className="text-lg font-semibold">
        {title} ({documents.length})
      </h2>
      {documents.length === 0 ? (
        <p className="text-sm text-muted-foreground">{emptyMessage}</p>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Titel</TableHead>
              <TableHead>Erstellt</TableHead>
              <TableHead>Aktionen</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {documents.map((doc) => (
              <TableRow key={doc.id}>
                <TableCell className="font-medium">{doc.title}</TableCell>
                <TableCell className="text-sm text-muted-foreground">
                  {doc.createdAt}
                </TableCell>
                <TableCell>
                  <Link
                    href={`/documents/${doc.id}`}
                    className="text-sm text-blue-600 hover:underline"
                  >
                    Öffnen
                  </Link>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </section>
  );
}
```

- [ ] **Step 4: Replace the stub `PipelinePanel`**

PipelinePanel manages its own collection selection (a local Select) instead of reusing `CollectionSelector`, because that component uses a per-instance localStorage hook that does not sync between components in the same render. URL param `?collectionId=…` is honored as the initial value, and the prop `initialCollectionId` exists purely for tests.

```typescript
// frontend/src/components/admin/PipelinePanel.tsx
"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@apollo/client/react";
import {
  ADMIN_COLLECTIONS_QUERY,
  PIPELINE_DOCUMENTS_QUERY,
} from "@/graphql/admin";
import { AdminCollection, PipelineDocument } from "@/types/admin";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { PipelineDocumentList } from "./PipelineDocumentList";

interface CollectionsData {
  collections: AdminCollection[];
}

interface PipelineData {
  processing: { items: PipelineDocument[]; totalCount: number };
  failed: { items: PipelineDocument[]; totalCount: number };
}

interface Props {
  initialCollectionId?: string;
}

function PipelinePanelInner({ initialCollectionId }: Props) {
  const search = useSearchParams();
  const urlCollectionId = search?.get("collectionId") ?? null;

  const [collectionId, setCollectionId] = useState<string | null>(
    initialCollectionId ?? urlCollectionId ?? null,
  );

  // Sync if URL param changes after mount (e.g., navigation from dashboard)
  useEffect(() => {
    if (urlCollectionId && urlCollectionId !== collectionId) {
      setCollectionId(urlCollectionId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlCollectionId]);

  const { data: collectionsData } = useQuery<CollectionsData>(
    ADMIN_COLLECTIONS_QUERY,
  );

  const { data, loading, error } = useQuery<PipelineData>(
    PIPELINE_DOCUMENTS_QUERY,
    {
      variables: {
        collectionId,
        processingFilter: { state: "PROCESSING" },
        failedFilter: { state: "FAILED" },
      },
      skip: !collectionId,
      fetchPolicy: "cache-and-network",
      pollInterval: 10000,
      skipPollAttempt: () => (data?.processing.totalCount ?? 0) === 0,
    },
  );

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <span className="text-sm text-muted-foreground">Collection:</span>
        <Select
          value={collectionId ?? undefined}
          onValueChange={(v) => setCollectionId(v)}
        >
          <SelectTrigger className="w-72">
            <SelectValue placeholder="Collection auswählen…" />
          </SelectTrigger>
          <SelectContent>
            {collectionsData?.collections.map((c) => (
              <SelectItem key={c.id} value={c.id}>
                {c.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {!collectionId && (
        <p className="text-muted-foreground">Bitte Collection auswählen.</p>
      )}

      {collectionId && loading && !data && (
        <div className="space-y-2">
          <Skeleton className="h-9 w-full" />
          <Skeleton className="h-9 w-full" />
        </div>
      )}

      {error && (
        <Alert variant="destructive">
          <AlertTitle>Fehler</AlertTitle>
          <AlertDescription>{error.message}</AlertDescription>
        </Alert>
      )}

      {data && (
        <>
          <PipelineDocumentList
            title="In Verarbeitung"
            documents={data.processing.items}
            emptyMessage="Keine Dokumente in Verarbeitung."
          />
          <PipelineDocumentList
            title="Fehlgeschlagen"
            documents={data.failed.items}
            emptyMessage="Keine fehlgeschlagenen Dokumente."
          />
        </>
      )}
    </div>
  );
}

export function PipelinePanel(props: Props) {
  return (
    <Suspense fallback={<Skeleton className="h-9 w-full" />}>
      <PipelinePanelInner {...props} />
    </Suspense>
  );
}
```

> Note: `PipelinePanelInner` is wrapped in `Suspense` because `useSearchParams` requires it under Next.js 14 App Router. The same pattern is used in `frontend/src/app/graph/page.tsx`.

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd frontend && pnpm test -- PipelinePanel 2>&1 | tail -20`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/czarnik/IdeaProjects/GraphMesh
git add frontend/src/components/admin/PipelineDocumentList.tsx frontend/src/components/admin/PipelinePanel.tsx frontend/src/__tests__/components/admin/PipelinePanel.test.tsx
git commit -m "$(cat <<'EOF'
feat(admin-ui): implement pipeline panel with processing/failed lists

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase C — Verification

## Task 9: Full build and test verification

- [ ] **Step 1: Run the full Vitest suite**

Run: `cd frontend && pnpm test 2>&1 | tail -40`
Expected: All four new admin tests PASS. Pre-existing TS errors in unrelated test files (DocumentList, EntitySearch, NodeDetail, useGraphData, etc.) are still acceptable as long as they were failing before this feature too — do NOT fix them in this plan.

- [ ] **Step 2: Run the Next.js build**

Run: `cd frontend && pnpm build 2>&1 | tail -30`
Expected: build succeeds. Verify the route list contains:
- `/admin`
- `/admin/collections`
- `/admin/config`
- `/admin/pipeline`

- [ ] **Step 3: Manual smoke check (optional but recommended)**

If a backend is available locally:
```bash
cd /Users/czarnik/IdeaProjects/GraphMesh/frontend && pnpm dev
```
Open `http://localhost:3000/admin` in a browser and verify:
- Sidebar shows four entries
- "Admin" link in the global SiteNav routes here
- Each sub-page renders without console errors
- Creating a collection via the dialog works end-to-end against a running backend

- [ ] **Step 4: Final commit if any tweaks were needed**

If Steps 1–3 surfaced any small fixes, commit them with:

```bash
cd /Users/czarnik/IdeaProjects/GraphMesh
git add -A
git commit -m "$(cat <<'EOF'
fix(admin-ui): post-verification adjustments

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Spec Coverage Check

| Spec acceptance criterion | Covered by |
|---|---|
| SiteNav shows "Admin" link | Task 4 |
| `/admin/layout.tsx` provides sidebar with 4 links + active state | Tasks 2 + 3 |
| `/admin` shows MetricCards + per-collection stats table | Task 5 |
| `/admin` polls every 30s | Task 5 (Apollo `pollInterval: 30000`) |
| `/admin/collections` lists all collections with full fields | Task 6 |
| Create dialog → `createCollection` | Task 6 |
| Edit dialog → `updateCollection` | Task 6 |
| Delete with confirmation dialog → `deleteCollection` | Task 6 |
| All mutations refetch + toast | Task 6 |
| `/admin/config` shows seven type filter buttons | Task 7 |
| Filter re-runs `configKeys` with chosen type | Task 7 |
| Edit + save calls `setConfig`, version refreshes | Task 7 |
| `/admin/pipeline` shows PROCESSING + FAILED lists per collection | Task 8 |
| Polling stops when PROCESSING list is empty | Task 8 (`skipPollAttempt`) |
| Existing pages keep working | Tasks 4 + 9 (build verification) |
| `pnpm test` passes for new admin tests | Task 9 |
| `pnpm build` succeeds | Tasks 3 + 9 |
