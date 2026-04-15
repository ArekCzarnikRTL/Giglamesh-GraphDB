---
name: Workflow & DoD
scope: docs
layer: workflow
priority: medium
appliesTo: all
description: Arbeitsmodus mit Claude, Planung und Definition of Done.
---

# Workflow & Definition of Done Rules

- Planung:
    - Bei mehrschrittigen Aufgaben zuerst einen kurzen Plan in Stichpunkten erstellen.
    - Bei unklaren Anforderungen: zunächst die entsprechende Feature-Spec in `docs/features/` ergänzen/klären.
- Definition of Done:
    - Feature-Spec aktualisiert oder neu angelegt.
    - Implementierung im passenden Feature-Package (Modulith-Regeln eingehalten).
    - Relevante Tests (Unit/Integration) erstellt/aktualisiert und grün.
    - Bei vollständigem Feature: `*-done.md` angelegt und `00-feature-set-overview.md` aktualisiert.
- Lernen:
    - Wichtige wiederkehrende Lessons im Umgang mit dir werden (oder können) in einem Doku-Dokument festgehalten (z.B. `docs/lessons/claude-lessons.md`).