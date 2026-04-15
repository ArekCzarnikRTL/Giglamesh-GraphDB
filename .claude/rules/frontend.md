---
name: Frontend Standards
scope: frontend
layer: coding
priority: medium
appliesTo: nextjs
description: Struktur, Datenzugriff und Styling-Regeln für das Next.js-Frontend.
---

# Frontend Rules (Next.js 14)

- Struktur:
    - Nutze den App Router (`frontend/src/app/...`) für neue Pages/Routes.
    - Wiederverwendbare UI-Bausteine unter `frontend/src/components/...`.
    - Helpers, Hooks, API-Clients unter `frontend/src/lib/...` oder analog bestehender Struktur.
- Datenzugriff:
    - GraphQL-Zugriffe laufen über eine zentrale Apollo-Client-Instanz.
    - Keine direkten `fetch`-Calls auf GraphQL-Endpunkte.
- Styling:
    - Tailwind für Layout & Utilities.
    - shadcn/ui als Basis für Standard-Komponenten.
    - Neue Komponenten orientieren sich an bestehenden Patterns (Props, Struktur, Styling).