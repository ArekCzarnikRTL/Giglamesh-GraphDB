# GraphMesh Document UI

Next.js 14 frontend for GraphMesh, providing the document management UI.

## Prerequisites

- Node.js 20+
- pnpm 9+
- GraphMesh backend running on `http://localhost:8080` (see top-level README)

## Setup

```bash
cp .env.local.example .env.local
pnpm install
```

## Development

```bash
pnpm dev
```

Open http://localhost:3000 — root redirects to `/documents`.

## Tests

```bash
pnpm test          # one-shot
pnpm test:watch    # watch mode
```

## Build

```bash
pnpm build
pnpm start
```

## Stack

- Next.js 14 (App Router)
- TypeScript (strict)
- Tailwind CSS v4 + shadcn/ui
- Apollo Client (`@apollo/client-integration-nextjs`)
- react-dropzone, react-hook-form, sonner
- Vitest + React Testing Library
