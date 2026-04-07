# Feature 35: Admin UI — Done

**Implemented:** 2026-04-07
**Scope:** Frontend-only (Option A from brainstorming)
**Spec:** `docs/superpowers/specs/2026-04-07-feature-35-admin-ui-design.md`
**Plan:** `docs/superpowers/plans/2026-04-07-feature-35-admin-ui.md`

## Summary

Added a `/admin` area to the existing Next.js frontend with four sub-pages
(Dashboard, Collections, Config, Pipeline) backed entirely by the existing
GraphQL API. Zero backend changes. The global `SiteNav` was extended with an
`Admin` link.

### What was built

- `/admin` Dashboard with four MetricCards (Collections, In Verarbeitung,
  Fehlgeschlagen, Mit Backlog) and a per-collection stats table. Polls every
  30 seconds.
- `/admin/collections` Collection manager with create/edit/delete via a
  shared `CollectionFormDialog` and a `DeleteConfirmDialog`. Toast feedback
  via `sonner`. Refetch after every mutation.
- `/admin/config` Config editor with seven type filter buttons (one per real
  `ConfigType` value: ONTOLOGY, FLOW, TOOL, PARAMETER, COLLECTION_SETTINGS,
  LLM_SETTINGS, SCHEMA) and inline `<textarea>` editing per item.
- `/admin/pipeline` Pipeline panel with a local Select for picking a
  collection (URL `?collectionId=…` honored), two tables for `PROCESSING`
  and `FAILED` documents. Polls every 10s, polling stops automatically when
  the PROCESSING list is empty.
- 4 new Vitest test files (one per page) — all 60 tests pass.

### Files added/modified

| File | Change |
|---|---|
| `frontend/src/types/admin.ts` | NEW — `ConfigTypeValue`, `ConfigEntry`, `AdminCollection`, `PipelineDocument`, `CollectionStats` |
| `frontend/src/graphql/admin.ts` | NEW — all admin queries/mutations as `gql` strings |
| `frontend/src/app/admin/layout.tsx` | NEW — sidebar shell |
| `frontend/src/app/admin/page.tsx` | NEW — dashboard page |
| `frontend/src/app/admin/collections/page.tsx` | NEW — collection manager page |
| `frontend/src/app/admin/config/page.tsx` | NEW — config editor page |
| `frontend/src/app/admin/pipeline/page.tsx` | NEW — pipeline panel page |
| `frontend/src/components/admin/AdminSidebar.tsx` | NEW — vertical 4-link nav |
| `frontend/src/components/admin/AdminDashboard.tsx` | NEW — Apollo container with metrics |
| `frontend/src/components/admin/MetricCard.tsx` | NEW |
| `frontend/src/components/admin/CollectionStatsRow.tsx` | NEW |
| `frontend/src/components/admin/CollectionManager.tsx` | NEW |
| `frontend/src/components/admin/CollectionFormDialog.tsx` | NEW |
| `frontend/src/components/admin/DeleteConfirmDialog.tsx` | NEW |
| `frontend/src/components/admin/ConfigEditor.tsx` | NEW |
| `frontend/src/components/admin/ConfigItemCard.tsx` | NEW |
| `frontend/src/components/admin/PipelinePanel.tsx` | NEW |
| `frontend/src/components/admin/PipelineDocumentList.tsx` | NEW |
| `frontend/src/components/SiteNav.tsx` | MODIFIED — added Admin entry |
| `frontend/src/__tests__/components/admin/AdminDashboard.test.tsx` | NEW |
| `frontend/src/__tests__/components/admin/CollectionManager.test.tsx` | NEW |
| `frontend/src/__tests__/components/admin/ConfigEditor.test.tsx` | NEW |
| `frontend/src/__tests__/components/admin/PipelinePanel.test.tsx` | NEW |

### Verification

- `cd frontend && pnpm test` → 60/60 passing across 30 files
- `cd frontend && pnpm build` → success, all four `/admin/*` routes appear
  in the static route list

### Commits

```
cabc725 fix(admin-ui): Apollo v4 compatibility for CollectionStatsRow and PipelinePanel
b9dd10e feat(admin-ui): implement pipeline panel with processing/failed lists
c2da77b feat(admin-ui): implement config editor with type filtering and inline edit
5750c11 feat(admin-ui): implement collection manager with create/edit/delete
7c223bb feat(admin-ui): implement admin dashboard with metrics and collection stats
2cb1167 feat(admin-ui): add Admin link to global SiteNav
bbb535b feat(admin-ui): scaffold /admin routes with sidebar layout
c9c6489 feat(admin-ui): add admin sidebar component
a476ea0 feat(admin-ui): add admin types and GraphQL operations
```

## Deviations from the original feature doc

The original `docs/features/35-admin-ui.md` assumed several backend GraphQL
fields and queries that do not exist (verified by reading the schema). All
divergences are documented in the spec; the deliberate scope cut decisions
were:

- **No System Health page** (no `systemHealth` query, no Spring Boot
  Actuator dependency, no Kafka AdminClient integration). Out of scope under
  Option A.
- **No `pipelineStatus` aggregate query** — the dashboard computes counts
  via parallel `documents(filter:{state}, pageSize:1)` queries and reads
  `totalCount`.
- **No `Collection.documentCount` backend field** — same reason.
- **Real names used for config:** `configKeys` / `setConfig` (not the
  fictional `configItems` / `updateConfigItem` from the doc).
- **`ConfigEntry.updatedAt` not exposed** — only `version` is shown.
- **Inline editing in collection table replaced with shadcn Dialog** for
  consistency with the rest of the UI.
- **`window.confirm` replaced with shadcn `DeleteConfirmDialog`** for the
  same reason.

## Deviations from the implementation plan

### Plan adjustments made during implementation

1. **AdminDashboard test (Task 5)** — the plan used `getByText("In Verarbeitung")`
   and `getByText("Fehlgeschlagen")`, but those strings appear twice on the page
   (MetricCard label + table header). The test was minimally adapted to use
   `getAllByText(...)` for those two assertions. Implementation unchanged.

2. **Apollo v4 compatibility (post-build)** — the plan code used two patterns
   that don't compile against `@apollo/client@4`:
   - `useQuery({ onCompleted })` — removed in v4. `CollectionStatsRow` now
     propagates counts via a `useEffect([data, onCounts, collection.id])`
     that calls a stable `useCallback`-memoized handler in `AdminDashboard`.
   - `skipPollAttempt: () => data?.… === 0` — circular reference because
     `data` is being declared by the same `useQuery` call. `PipelinePanel` now
     stores the latest processing total in a `useRef` updated by a post-render
     effect, and `skipPollAttempt` reads the ref instead.

   These were committed as `cabc725`.

## Open follow-ups / known gaps

- **No automated end-to-end test against a real backend.** The unit tests
  use `MockedProvider` only. Manual smoke testing against a running backend
  was not performed (no backend running locally during implementation).
- **No auth.** The project has no auth layer; `/admin` is accessible to
  anyone who can reach the Next.js app. Adding auth is a separate, explicit
  decision and was deliberately out of scope.
- **Pre-existing TS errors in unrelated test files** (`DocumentList.test.tsx`
  and friends) due to Apollo v4 `MockedProvider` typing changes are still
  present. They were not introduced by this feature and were explicitly
  excluded from this plan's scope. The new admin tests use the same pattern
  and inherit the same harmless TS warnings, but Vitest still runs and passes
  all 60 tests.
- **Dashboard counts on ≥20 collections** will fan out 2N parallel
  `documents` queries every 30 seconds. Acceptable for typical operator use,
  but if collection counts grow significantly an aggregate backend query
  would be a worthwhile follow-up (would belong in a hypothetical "Feature
  35b: Admin Health & Observability").
- **Polling on the dashboard happens at two levels** (the collections list
  every 30s AND each row's count query every 30s). The collections list poll
  serves as an automatic refresh of the row set; the rows manage their own
  data. This is intentional but means dashboard traffic = 1 + 2N queries per
  30s.
