---
name: Feature Specs & Docs
scope: docs
layer: workflow
priority: medium
appliesTo: all
description: Regeln für Feature-Spezifikationen, Abschlussdokumentation und Übersichten.
---

# Feature Specs & Docs Rules

- Jede relevante fachliche Änderung benötigt eine Feature-Spec in `docs/features/`.
- Namensschema:
    - `NN-feature-name.md` = Spezifikation
    - `NN-feature-name-done.md` = Abschlussdokumentation
    - `NN` = zweistellige Sortiernummer (01, 02, 03, …)
- Aktualisiere `docs/features/00-feature-set-overview.md`, wenn:
    - ein neues Feature hinzu kommt
    - ein Feature abgeschlossen wird (Status "done")
- Bei unklarer Aufgabenbeschreibung:
    - Schlage zuerst Änderungen an der entsprechenden Feature-Spec vor, bevor du Code änderst.
- Abschlussdoku (`*-done.md`) beschreibt:
    - Was wurde umgesetzt?
    - Relevante APIs/Modelle
    - ggf. Screenshots / Beispielcalls