---
name: Monorepo Structure
scope: infra
layer: architecture
priority: high
appliesTo: monorepo
description: Regeln für Projektstruktur, Ordnerlayout und Grenzen im Monorepo.
---

# Monorepo Rules

- Du arbeitest in einem Monorepo mit:
    - Backend: `src/main/kotlin/com/agentwork/graphmesh/...`
    - Frontend: `frontend/` (Next.js 14)
    - Docs: `docs/`
    - Infra: `docker-compose.yaml`
- Lege **keine** neuen Gradle-Submodule an; das Backend bleibt ein Spring Modulith in einem JAR.
- Halte dich an die existierenden Top-Level-Ordner, statt neue „Sidecar“-Projekte anzulegen.
- Wenn du neue Bereiche brauchst, schlage zuerst eine Erweiterung der Monorepo-Struktur vor, bevor du Verzeichnisse erfindest.