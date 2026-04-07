# Frontend Design System — Apply to GraphMesh — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply OKLCh design tokens, dark-only mode, Inter + JetBrains Mono fonts, and a Sonner theme fix to the existing GraphMesh frontend without touching frameworks or layout.

**Architecture:** Theme-only change. CSS custom properties in `globals.css` switch from HSL channels to full OKLCh strings; `tailwind.config.ts` unwraps `hsl()` and adds sidebar/chart/font extensions; `<html>` gets the `dark` class plus Inter and JetBrainsMono `next/font/google` CSS-variable classes; `Sonner` hardcodes `theme="dark"`. Two orphaned Geist `.woff` files are deleted. UI components (Button/Card/Badge/Input/Tabs/Dialog/...) are already spec-compliant and remain untouched.

**Tech Stack:** Next.js 14, Tailwind CSS v3, base-ui, sonner, Vitest. No upgrades.

**Spec:** `docs/superpowers/specs/2026-04-07-frontend-design-system-apply-design.md`

**Conventions:**
- Direct-to-main commits, no PRs.
- Frontend commands: `cd frontend && pnpm <cmd>`.
- Pre-existing test-file TS warnings (Apollo v4 `MockedProvider` typings) MUST NOT be touched.
- Commit messages: `style(theme): ...` / `chore(fonts): ...` / `fix(sonner): ...`. Co-author with Claude.

---

## File Structure

| Path | Status | Purpose |
|---|---|---|
| `frontend/src/app/globals.css` | REWRITE | Single OKLCh `:root` block, no `.dark` block, body uses `font-sans` |
| `frontend/tailwind.config.ts` | MODIFY | Color mappings unwrap `hsl()`; add sidebar + chart token groups; add `fontFamily.sans/mono/heading` |
| `frontend/src/app/layout.tsx` | MODIFY | Import Inter + JetBrains_Mono; set `<html>` className to `cn("dark", inter.variable, jetBrainsMono.variable)` |
| `frontend/src/components/ui/sonner.tsx` | MODIFY | Drop `useTheme`/`next-themes` import, hardcode `theme="dark"` |
| `frontend/src/app/fonts/GeistVF.woff` | DELETE | Orphan, not imported anywhere |
| `frontend/src/app/fonts/GeistMonoVF.woff` | DELETE | Orphan, not imported anywhere |
| `frontend/src/app/fonts/` | REMOVE DIR | After both files are deleted |

---

## Sequencing Note

The four tasks are **sequential**. Each task's verification gate is "the build still passes". Parallel execution would obscure which step caused a regression. Subagent-driven execution dispatches them one at a time.

---

# Task 1: Theme tokens (OKLCh + dark-only)

**Files:**
- Modify: `frontend/src/app/globals.css`
- Modify: `frontend/tailwind.config.ts`

- [ ] **Step 1: Replace `globals.css` content**

Open `frontend/src/app/globals.css` and replace its entire content with:

```css
@tailwind base;
@tailwind components;
@tailwind utilities;

@layer base {
  :root {
    --background: oklch(0.13 0.02 260);
    --foreground: oklch(0.93 0.01 250);

    --card: oklch(0.17 0.02 260);
    --card-foreground: oklch(0.93 0.01 250);

    --popover: oklch(0.17 0.02 260);
    --popover-foreground: oklch(0.93 0.01 250);

    --primary: oklch(0.62 0.19 260);
    --primary-foreground: oklch(0.98 0 0);

    --secondary: oklch(0.22 0.03 260);
    --secondary-foreground: oklch(0.93 0.01 250);

    --muted: oklch(0.22 0.03 260);
    --muted-foreground: oklch(0.55 0.03 250);

    --accent: oklch(0.22 0.03 260);
    --accent-foreground: oklch(0.93 0.01 250);

    --destructive: oklch(0.65 0.2 25);
    --destructive-foreground: oklch(0.98 0 0);

    --border: oklch(1 0 0 / 10%);
    --input: oklch(1 0 0 / 12%);
    --ring: oklch(0.62 0.19 260);

    --sidebar: oklch(0.15 0.025 260);
    --sidebar-foreground: oklch(0.93 0.01 250);
    --sidebar-primary: oklch(0.62 0.19 260);
    --sidebar-accent: oklch(0.22 0.03 260);
    --sidebar-border: oklch(1 0 0 / 10%);

    --chart-1: oklch(0.62 0.19 260);
    --chart-2: oklch(0.65 0.15 160);
    --chart-3: oklch(0.6 0.18 300);
    --chart-4: oklch(0.7 0.15 60);
    --chart-5: oklch(0.6 0.2 30);

    --radius: 0.625rem;
  }
}

@layer base {
  * {
    @apply border-border;
  }
  body {
    @apply bg-background text-foreground font-sans;
  }
}
```

The light-mode `:root` and the `.dark` block are gone; everything is in a single `:root`. The body now applies `font-sans` so Inter (set up in Task 2) takes over once the font CSS variable is provided.

- [ ] **Step 2: Replace `tailwind.config.ts` content**

Open `frontend/tailwind.config.ts` and replace its entire content with:

```typescript
import type { Config } from "tailwindcss";
import defaultTheme from "tailwindcss/defaultTheme";

const config: Config = {
  darkMode: ["class"],
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    container: {
      center: true,
      padding: "2rem",
      screens: {
        "2xl": "1400px",
      },
    },
    extend: {
      colors: {
        border: "var(--border)",
        input: "var(--input)",
        ring: "var(--ring)",
        background: "var(--background)",
        foreground: "var(--foreground)",
        primary: {
          DEFAULT: "var(--primary)",
          foreground: "var(--primary-foreground)",
        },
        secondary: {
          DEFAULT: "var(--secondary)",
          foreground: "var(--secondary-foreground)",
        },
        destructive: {
          DEFAULT: "var(--destructive)",
          foreground: "var(--destructive-foreground)",
        },
        muted: {
          DEFAULT: "var(--muted)",
          foreground: "var(--muted-foreground)",
        },
        accent: {
          DEFAULT: "var(--accent)",
          foreground: "var(--accent-foreground)",
        },
        popover: {
          DEFAULT: "var(--popover)",
          foreground: "var(--popover-foreground)",
        },
        card: {
          DEFAULT: "var(--card)",
          foreground: "var(--card-foreground)",
        },
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
      },
      borderRadius: {
        lg: "var(--radius)",
        md: "calc(var(--radius) - 2px)",
        sm: "calc(var(--radius) - 4px)",
      },
      fontFamily: {
        sans: ["var(--font-sans)", ...defaultTheme.fontFamily.sans],
        mono: ["var(--font-mono)", ...defaultTheme.fontFamily.mono],
        heading: ["var(--font-sans)", ...defaultTheme.fontFamily.sans],
      },
      keyframes: {
        "accordion-down": {
          from: { height: "0" },
          to: { height: "var(--radix-accordion-content-height)" },
        },
        "accordion-up": {
          from: { height: "var(--radix-accordion-content-height)" },
          to: { height: "0" },
        },
      },
      animation: {
        "accordion-down": "accordion-down 0.2s ease-out",
        "accordion-up": "accordion-up 0.2s ease-out",
      },
    },
  },
  plugins: [require("tailwindcss-animate")],
};
export default config;
```

The two changes vs. the existing file:
1. Every `hsl(var(--xxx))` is now `var(--xxx)`.
2. New keys: `colors.sidebar`, `colors.chart`, `fontFamily.sans/mono/heading`, plus the new `import defaultTheme from "tailwindcss/defaultTheme";` at the top.

Note: at this point the `--font-sans` and `--font-mono` CSS variables are not yet defined (Task 2 wires them up via `next/font`). Tailwind will still emit the rule with the variable references — the browser falls back to the rest of the family stack until Task 2 ships.

- [ ] **Step 3: Verify the build still passes**

Run: `cd frontend && pnpm build 2>&1 | tail -25`
Expected: build succeeds; the route list still contains `/`, `/admin`, `/admin/collections`, `/admin/config`, `/admin/pipeline`, `/documents`, `/documents/[id]`, `/documents/upload`, `/graph`, `/query`. No type errors. No `Failed to compile.` line.

If the build fails, do NOT proceed. Read the error, fix the smallest thing necessary, and re-run.

- [ ] **Step 4: Commit**

```bash
cd /Users/czarnik/IdeaProjects/GraphMesh
git add frontend/src/app/globals.css frontend/tailwind.config.ts
git commit -m "$(cat <<'EOF'
style(theme): switch to OKLCh dark-only theme tokens

Replaces HSL light+dark blocks in globals.css with a single OKLCh
:root block (--radius 0.625rem) and adds sidebar + chart token
groups in tailwind.config.ts. Color mappings unwrap hsl() since
OKLCh values are full color strings.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task 2: Inter + JetBrains Mono fonts

**Files:**
- Modify: `frontend/src/app/layout.tsx`
- Delete: `frontend/src/app/fonts/GeistVF.woff`
- Delete: `frontend/src/app/fonts/GeistMonoVF.woff`
- Delete: `frontend/src/app/fonts/` (empty after the two file deletions)

- [ ] **Step 1: Replace `layout.tsx` content**

Open `frontend/src/app/layout.tsx` and replace its entire content with:

```typescript
import type { Metadata } from "next";
import { Inter, JetBrains_Mono } from "next/font/google";
import "./globals.css";
import { ApolloWrapper } from "@/lib/apollo-wrapper";
import { SiteNav } from "@/components/SiteNav";
import { Toaster } from "@/components/ui/sonner";
import { cn } from "@/lib/utils";

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

export const metadata: Metadata = {
  title: "GraphMesh",
  description: "GraphMesh Document UI",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html
      lang="de"
      className={cn("dark", inter.variable, jetBrainsMono.variable)}
    >
      <body className="flex h-screen flex-col overflow-hidden">
        <ApolloWrapper>
          <SiteNav />
          <div className="flex-1 min-h-0 overflow-auto">{children}</div>
          <Toaster />
        </ApolloWrapper>
      </body>
    </html>
  );
}
```

The four diffs vs. the existing file:
1. New imports: `Inter`, `JetBrains_Mono` from `next/font/google` and `cn` from `@/lib/utils`.
2. Two `next/font` declarations exposing CSS variables.
3. `<html>` carries `className={cn("dark", inter.variable, jetBrainsMono.variable)}`.
4. Everything else (body classes, ApolloWrapper, SiteNav, Toaster) is unchanged.

- [ ] **Step 2: Delete the orphan Geist font files**

Run:

```bash
cd /Users/czarnik/IdeaProjects/GraphMesh
rm frontend/src/app/fonts/GeistVF.woff frontend/src/app/fonts/GeistMonoVF.woff
rmdir frontend/src/app/fonts
```

These were never imported (verified via grep on 2026-04-07) and are dead weight.

- [ ] **Step 3: Verify the build still passes**

Run: `cd frontend && pnpm build 2>&1 | tail -25`
Expected: build succeeds; the route list is unchanged from Task 1; no `Failed to compile.` line; the build log may briefly mention downloading Google fonts on first run.

- [ ] **Step 4: Commit**

```bash
cd /Users/czarnik/IdeaProjects/GraphMesh
git add frontend/src/app/layout.tsx frontend/src/app/fonts
git commit -m "$(cat <<'EOF'
chore(fonts): use Inter and JetBrains Mono via next/font

Wires Inter (--font-sans) and JetBrains Mono (--font-mono) into
the root layout and forces dark mode on <html>. Deletes the
orphaned Geist .woff files that were never imported.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

Note: `git add frontend/src/app/fonts` will also stage the directory deletion. If git complains about the directory being gone, use `git add -u frontend/src/app/fonts/GeistVF.woff frontend/src/app/fonts/GeistMonoVF.woff` instead, which records the deletions explicitly.

---

# Task 3: Sonner — hardcode dark theme

**Files:**
- Modify: `frontend/src/components/ui/sonner.tsx`

- [ ] **Step 1: Replace `sonner.tsx` content**

Open `frontend/src/components/ui/sonner.tsx` and replace its entire content with:

```typescript
"use client"

import { Toaster as Sonner, type ToasterProps } from "sonner"
import { CircleCheckIcon, InfoIcon, TriangleAlertIcon, OctagonXIcon, Loader2Icon } from "lucide-react"

const Toaster = ({ ...props }: ToasterProps) => {
  return (
    <Sonner
      theme="dark"
      className="toaster group"
      icons={{
        success: (
          <CircleCheckIcon className="size-4" />
        ),
        info: (
          <InfoIcon className="size-4" />
        ),
        warning: (
          <TriangleAlertIcon className="size-4" />
        ),
        error: (
          <OctagonXIcon className="size-4" />
        ),
        loading: (
          <Loader2Icon className="size-4 animate-spin" />
        ),
      }}
      style={
        {
          "--normal-bg": "var(--popover)",
          "--normal-text": "var(--popover-foreground)",
          "--normal-border": "var(--border)",
          "--border-radius": "var(--radius)",
        } as React.CSSProperties
      }
      toastOptions={{
        classNames: {
          toast: "cn-toast",
        },
      }}
      {...props}
    />
  )
}

export { Toaster }
```

The two diffs vs. the existing file:
1. Removed `import { useTheme } from "next-themes";`
2. Removed the `const { theme = "system" } = useTheme();` line and replaced `theme={theme as ToasterProps["theme"]}` with `theme="dark"`.

Everything else (the Sonner-style CSS variables, the lucide icons, the `cn-toast` class) is unchanged.

- [ ] **Step 2: Run the test suite**

Run: `cd frontend && pnpm test 2>&1 | tail -10`
Expected: `Test Files  30 passed (30)` and `Tests  60 passed (60)`. No new failures. The Sonner Toaster is mounted in the root layout but no test asserts on its content, so this change is invisible to the test suite — which is the point.

- [ ] **Step 3: Commit**

```bash
cd /Users/czarnik/IdeaProjects/GraphMesh
git add frontend/src/components/ui/sonner.tsx
git commit -m "$(cat <<'EOF'
fix(sonner): hardcode dark theme

The frontend is dark-only after the OKLCh token switch, so the
runtime useTheme() call from next-themes is no longer meaningful.
Sonner now renders with theme="dark" directly.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task 4: Final verification

**Files:** none modified.

- [ ] **Step 1: Run the full test suite one more time**

Run: `cd frontend && pnpm test 2>&1 | tail -10`
Expected: `Test Files  30 passed (30)` and `Tests  60 passed (60)`.

- [ ] **Step 2: Run the production build**

Run: `cd frontend && pnpm build 2>&1 | tail -25`
Expected: build succeeds; route list still contains every existing route (`/`, `/admin`, `/admin/collections`, `/admin/config`, `/admin/pipeline`, `/documents`, `/documents/[id]`, `/documents/upload`, `/graph`, `/query`).

- [ ] **Step 3: Manual smoke check (recommended, optional)**

If a backend is available locally:

```bash
cd /Users/czarnik/IdeaProjects/GraphMesh/frontend && pnpm dev
```

Open `http://localhost:3000/documents` in a browser and verify:
- The page renders in a dark blue OKLCh theme (not the previous slate-grey)
- Body text is in Inter
- Code/monospace elements (e.g., the ExtractedTriples panel) render in JetBrains Mono
- Nav items in the global SiteNav remain visible and clickable
- Navigate to `/admin`, `/graph`, `/query` and confirm each renders in the dark theme without console errors

If any visual regression is jarring, capture a screenshot and report it. Do NOT auto-fix without escalation.

- [ ] **Step 4: No commit needed** (verification only)

If everything passes, the feature is done. If Step 1 or Step 2 surfaces a regression, report it and stop — do not silently patch.

---

## Spec Coverage Check

| Spec acceptance criterion | Covered by |
|---|---|
| `globals.css` contains a single `:root` block with OKLCh values | Task 1 Step 1 |
| No `.dark` block remains in `globals.css` | Task 1 Step 1 |
| `body` rule applies `font-sans` | Task 1 Step 1 |
| `layout.tsx` imports `Inter` and `JetBrains_Mono` from `next/font/google` | Task 2 Step 1 |
| `<html>` carries `"dark"`, `inter.variable`, `jetBrainsMono.variable` classes | Task 2 Step 1 |
| `GeistVF.woff` and `GeistMonoVF.woff` deleted; `fonts/` directory removed | Task 2 Step 2 |
| `tailwind.config.ts` color mappings use `var(--xxx)` | Task 1 Step 2 |
| `tailwind.config.ts` contains `sidebar` and `chart` color groups | Task 1 Step 2 |
| `tailwind.config.ts` contains `fontFamily.sans/mono/heading` | Task 1 Step 2 |
| `sonner.tsx` no longer imports `next-themes`; hardcoded `theme="dark"` | Task 3 Step 1 |
| `pnpm test` reports 60/60 passing | Task 3 Step 2 + Task 4 Step 1 |
| `pnpm build` succeeds and lists all existing routes | Task 1 Step 3 + Task 2 Step 3 + Task 4 Step 2 |
