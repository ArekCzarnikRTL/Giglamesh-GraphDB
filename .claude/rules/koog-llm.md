---
name: Koog & LLM Usage
scope: ai
layer: architecture
priority: medium
appliesTo: backend
description: Regeln für den Einsatz von Koog, Agenten und LLM-Workflows.
---

# Koog & LLM Rules

- Für LLM-Interaktionen nutzt du Koog (0.7.3), keine eigenen HTTP-Clients gegen LLM-APIs.
- LLM-Modelle **immer** ueber `resolveLlmModel(name)` aus `com.agentwork.graphmesh.llm.ModelResolver` aufloesen; niemals rohe `LLModel(...)` bauen (Koog braucht Capabilities, sonst Laufzeitfehler).
- Mehrschrittige LLM-Flows modellierst du als Koog-Agenten mit Tools/Funktionen:
    - keine "God-Controller", die alles in einer Methode erledigen.
- Halte dich an bestehende Patterns:
    - Tool-Registrierung
    - Event-Behandlung (z.B. Agent Events, Tool Calls)
- Business-Logik bleibt in Domain/Application:
    - Koog-Agenten orchestrieren, sie enthalten keine fachlichen Regeln.
- Logging/Tracing:
    - LLM-/Agent-Events werden sinnvoll, aber nicht übermäßig detailliert geloggt.
    - Achte darauf, keine sensitiven Inhalte unnötig ins Log zu schreiben.