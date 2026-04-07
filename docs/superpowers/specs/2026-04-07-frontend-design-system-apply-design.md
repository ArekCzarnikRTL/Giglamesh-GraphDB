# Apply Frontend Design System to GraphMesh — Design

**Status:** Approved (2026-04-07)
**Scope:** Frontend-only, design tokens + minimal sonner adjustment (Option B from brainstorming, scoped down after verifying components are already aligned)
**Source spec:** `docs/frontend-design-system.md`

## Problem

`docs/frontend-design-system.md` defines a generic dark-only OKLCh-based design
system. The user wants it applied to the GraphMesh frontend. The spec lists a
greenfield target stack (Next.js 16, React 19, Tailwind v4) that does **not**
match the project, and a 3-column app-shell layout that conflicts with the
existing global SiteNav-based layout.

## Goal

Apply the visual identity of the design system (colors, fonts, radius,
dark-only) to the existing GraphMesh frontend **without** framework upgrades
and **without** rewriting the global layout. The existing UI components have
already been built against the same patterns and are nearly spec-compliant out
of the box, so the change is concentrated in the theme tokens, font loading,
and a small `Sonner` adjustment.

## Out of scope (deliberate)

- **Framework upgrades:** Next.js 14 → 16, React 18 → 19, Tailwind v3 → v4 are
  each multi-week migrations with high risk against this codebase. Not in this
  spec.
- **App shell rewrite:** the spec's 3-column icon-bar + sidebar + main +
  detail-panel layout would require touching every page (`/documents`,
  `/graph`, `/query`, `/admin/*`) and detail panels would be empty decoration
  for most pages. Not in this spec.
- **Sheet component:** the spec lists a `Sheet` slide-panel primitive. It is
  not used anywhere in the codebase. YAGNI — added later when a real consumer
  needs it.
- **Removing `next-themes` dependency:** the package is only consumed by
  `Sonner`. After hardcoding Sonner to dark, the dependency becomes dead, but
  removing it is a separate, isolated cleanup. Not in this spec.
- **Snapshot/visual-regression tests:** the project has no such infrastructure
  and adding it for this small change would be massive overkill.

## Verified component baseline (read on 2026-04-07)

The following components already match the spec's component contracts and
require **no changes** as part of this work:

| Component | Spec compliance evidence |
|---|---|
| `Button` | h-8 default, all variants (default/outline/secondary/ghost/destructive/link), all sizes (xs/sm/default/lg/icon/icon-xs/icon-sm/icon-lg), `active:not-aria-[haspopup]:translate-y-px`, `data-slot="button"` |
| `Card` | `rounded-xl`, `default`/`sm` sizes via `data-size`, all slots (Card, CardHeader, CardTitle, CardDescription, CardAction, CardContent, CardFooter), `font-heading` on title |
| `Badge` | `h-5`, `rounded-4xl`, all variants (default/secondary/destructive/outline/ghost/link), `data-slot="badge"` |
| `Input` | `h-8`, `focus-visible:ring-3 focus-visible:ring-ring/50`, `dark:bg-input/30`, `data-slot="input"` |
| `Tabs` | already has `default` and `line` variants via `tabsListVariants` |
| `Dialog` | base-ui-based, `z-50` overlay with `backdrop-blur`, fade-in/zoom-in animations |

The only place that needs touching beyond the theme layer is `Sonner`, which
currently hardcodes a `useTheme()` call from `next-themes`.

## Architecture

The change is mechanical and lives in five files plus two file deletions.

### 1. Color tokens — `frontend/src/app/globals.css`

The current file has a light `:root` block (HSL channels) and a separate
`.dark` block. This is replaced with a **single** `:root` block containing the
OKLCh values from `docs/frontend-design-system.md`. The `.dark` block is
deleted entirely. Dark mode becomes the only mode.

The full list of CSS variables to set in `:root`:

```
--background          oklch(0.13 0.02 260)
--foreground          oklch(0.93 0.01 250)
--card                oklch(0.17 0.02 260)
--card-foreground     oklch(0.93 0.01 250)
--popover             oklch(0.17 0.02 260)
--popover-foreground  oklch(0.93 0.01 250)
--primary             oklch(0.62 0.19 260)
--primary-foreground  oklch(0.98 0 0)
--secondary           oklch(0.22 0.03 260)
--secondary-foreground oklch(0.93 0.01 250)
--muted               oklch(0.22 0.03 260)
--muted-foreground    oklch(0.55 0.03 250)
--accent              oklch(0.22 0.03 260)
--accent-foreground   oklch(0.93 0.01 250)
--destructive         oklch(0.65 0.2 25)
--destructive-foreground  (unchanged: white-ish; spec doesn't define one but
                           components reference it. Use oklch(0.98 0 0).)
--border              oklch(1 0 0 / 10%)
--input               oklch(1 0 0 / 12%)
--ring                oklch(0.62 0.19 260)
--sidebar             oklch(0.15 0.025 260)
--sidebar-foreground  oklch(0.93 0.01 250)
--sidebar-primary     oklch(0.62 0.19 260)
--sidebar-accent      oklch(0.22 0.03 260)
--sidebar-border      oklch(1 0 0 / 10%)
--chart-1             oklch(0.62 0.19 260)
--chart-2             oklch(0.65 0.15 160)
--chart-3             oklch(0.6 0.18 300)
--chart-4             oklch(0.7 0.15 60)
--chart-5             oklch(0.6 0.2 30)
--radius              0.625rem
```

The existing `@layer base { * { @apply border-border; } body { @apply bg-background text-foreground; } }` block is preserved. A single new line is added inside the body rule: `@apply font-sans;`.

### 2. HTML root class — `frontend/src/app/layout.tsx`

Change `<html lang="de">` → `<html lang="de" className={...dark...}>`. The
`className` includes `"dark"` plus the two font CSS-variable classes from the
next/font integration (see Section 3). Tailwind's `darkMode: ["class"]` config
in `tailwind.config.ts` is unchanged so all `dark:` utilities continue to
match.

### 3. Fonts — `frontend/src/app/layout.tsx`

```typescript
import { Inter, JetBrains_Mono } from "next/font/google";

const inter = Inter({
  subsets: ["latin"],
  variable: "--font-sans",
  display: "swap",
});

const jetBrainsMono = JetBrains_Mono({
  subsets: ["latin"],
  variable: "--font-mono",
  display: "swap",
});

// in <html className>: cn("dark", inter.variable, jetBrainsMono.variable)
```

`cn` is already exported from `@/lib/utils`.

The orphaned `frontend/src/app/fonts/GeistVF.woff` and
`frontend/src/app/fonts/GeistMonoVF.woff` files are deleted — they are not
imported anywhere (verified via grep on 2026-04-07). The empty `fonts/`
directory is removed too.

### 4. Tailwind config — `frontend/tailwind.config.ts`

Two changes:

**(a) Color tokens unwrap `hsl()`.** OKLCh values are full color strings, not
H/S/L channels. Every entry of the form `hsl(var(--xxx))` becomes
`var(--xxx)`. Affected entries: `border`, `input`, `ring`, `background`,
`foreground`, `primary`, `primary.foreground`, `secondary`,
`secondary.foreground`, `destructive`, `destructive.foreground`, `muted`,
`muted.foreground`, `accent`, `accent.foreground`, `popover`,
`popover.foreground`, `card`, `card.foreground`.

**(b) Add new color tokens** for sidebar and charts:

```typescript
sidebar: {
  DEFAULT: "var(--sidebar)",
  foreground: "var(--sidebar-foreground)",
  primary: "var(--sidebar-primary)",
  accent: "var(--sidebar-accent)",
  border: "var(--sidebar-border)",
},
chart: {
  "1": "var(--chart-1)",
  "2": "var(--chart-2)",
  "3": "var(--chart-3)",
  "4": "var(--chart-4)",
  "5": "var(--chart-5)",
},
```

**(c) Add fontFamily mappings.** The `Card` component already uses
`font-heading`, so the `heading` family must resolve to Inter as well:

```typescript
import defaultTheme from "tailwindcss/defaultTheme";

// inside theme.extend:
fontFamily: {
  sans: ["var(--font-sans)", ...defaultTheme.fontFamily.sans],
  mono: ["var(--font-mono)", ...defaultTheme.fontFamily.mono],
  heading: ["var(--font-sans)", ...defaultTheme.fontFamily.sans],
},
```

`darkMode: ["class"]` is **unchanged**. The class is still set on `<html>`,
which keeps every `dark:`-prefixed utility class in existing components
working without modification.

### 5. Sonner — `frontend/src/components/ui/sonner.tsx`

Replace `const { theme = "system" } = useTheme();` with hardcoded
`theme="dark"` on the `<Sonner>` component. Remove the unused `useTheme`
import. The existing `--normal-bg: var(--popover)` style block does not need
`hsl()` and continues to work as-is.

```typescript
// before
import { useTheme } from "next-themes";
const Toaster = ({ ...props }: ToasterProps) => {
  const { theme = "system" } = useTheme();
  return <Sonner theme={theme as ToasterProps["theme"]} ... />;
};

// after
const Toaster = ({ ...props }: ToasterProps) => {
  return <Sonner theme="dark" ... />;
};
```

## Files touched

| File | Change |
|---|---|
| `frontend/src/app/globals.css` | Replace HSL `:root` + `.dark` blocks with single OKLCh `:root` block; add `font-sans` to body |
| `frontend/src/app/layout.tsx` | Import `next/font/google` Inter + JetBrains_Mono; set `<html lang="de" className={cn("dark", inter.variable, jetBrainsMono.variable)}>` |
| `frontend/tailwind.config.ts` | Unwrap `hsl()`; add sidebar/chart color tokens; add `fontFamily.sans/mono/heading` |
| `frontend/src/components/ui/sonner.tsx` | Drop `useTheme`, hardcode `theme="dark"` |
| `frontend/src/app/fonts/GeistVF.woff` | DELETED (orphan) |
| `frontend/src/app/fonts/GeistMonoVF.woff` | DELETED (orphan) |
| `frontend/src/app/fonts/` | Empty directory removed |

## Verification strategy

No new tests. The 60 existing Vitest tests verify component behaviour (queries,
mutations, rendering of strings) and do not depend on theme tokens — they will
remain green.

Per-step gates:

1. After globals.css + tailwind.config.ts: `cd frontend && pnpm build` must
   succeed. If the OKLCh switch breaks color resolution, the build will fail
   at type-check or rendering will silently lose colors — this gate catches
   the obvious case.
2. After font integration: `pnpm build` must still succeed.
3. After Sonner fix: `cd frontend && pnpm test` must report 60/60 passing.
4. Final: `pnpm test` AND `pnpm build` both green.

Manual smoke (the user does this, not automation): start `pnpm dev` and visit
`/documents`, `/admin`, `/graph`, `/query`. All pages should render in the
dark OKLCh-blue theme with Inter as the body font.

## Acceptance criteria

- [ ] `frontend/src/app/globals.css` contains a single `:root` block with the
      OKLCh values from `docs/frontend-design-system.md` and `--radius: 0.625rem`
- [ ] No `.dark` block remains in `globals.css`
- [ ] `body` rule applies `font-sans`
- [ ] `frontend/src/app/layout.tsx` imports `Inter` and `JetBrains_Mono` from
      `next/font/google`
- [ ] `<html>` element carries the classes `"dark"`, `inter.variable`, and
      `jetBrainsMono.variable`
- [ ] `frontend/src/app/fonts/GeistVF.woff` and `GeistMonoVF.woff` are deleted;
      the `fonts/` directory no longer exists
- [ ] `frontend/tailwind.config.ts` color mappings use `var(--xxx)` (no `hsl()`)
- [ ] `tailwind.config.ts` contains `sidebar` and `chart` color groups
- [ ] `tailwind.config.ts` contains `fontFamily.sans`, `fontFamily.mono`, and
      `fontFamily.heading` referencing the CSS variables
- [ ] `frontend/src/components/ui/sonner.tsx` no longer imports or uses
      `next-themes`; `<Sonner theme="dark" />` is hardcoded
- [ ] `cd frontend && pnpm test` reports 60/60 passing
- [ ] `cd frontend && pnpm build` succeeds and lists all existing routes

## Known follow-ups (not in this spec)

- Removing the `next-themes` dependency from `package.json` — fully isolated
  cleanup, do separately.
- Adding a `Sheet` component when a consumer needs one.
- Visual smoke test infrastructure (Playwright screenshots or similar) — not a
  current project concern.
- A future Feature could replace the global SiteNav with the spec's 3-column
  app shell. This would be its own brainstorming + plan + implementation
  cycle and would touch every page.
