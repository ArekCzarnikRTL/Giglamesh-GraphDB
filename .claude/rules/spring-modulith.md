---
name: Spring Modulith Boundaries
scope: backend
layer: architecture
priority: high
appliesTo: spring-modulith
description: Regeln für Feature-Pakete, Modulgrenzen und Encapsulation im Modulith.
---

# Spring Modulith Rules

- Jedes Feature liegt unter `com.agentwork.graphmesh.<feature>`.
- Die bestehenden Feature-Pakete sind **flach** organisiert (z. B. `collection/`, `extraction/`, `pipeline/`, `rdf/`, `storage/`). Halte dich an diesen Ist-Zustand und fuege keine neuen `api/application/domain/infrastructure`-Subpackages ein, solange das Feature dies nicht zwingend erfordert.
- Wenn ein Feature gross genug wird, duerfen Subpackages eingefuehrt werden; uebliche Namen:
    - `api` (REST/GraphQL, DTOs)
    - `application` (Use Cases, Orchestrierung)
    - `domain` (Entities, Value Objects, Domain Events, Ports)
    - `infrastructure` (Adapter, Persistence, Messaging, Config)
- Schichten-Trennung (Controller/Application/Domain/Infrastructure) gilt logisch, auch wenn physisch alles im Feature-Root liegt (siehe `backend-coding.md`).
- Cross-Feature-Zugriffe:
    - Nur ueber explizite Service-Schnittstellen des jeweiligen Features, keine direkten Zugriffe auf interne Klassen anderer Features.
- Ein Feature haengt nicht von Infrastruktur-/Persistenzklassen anderer Features ab.
- Neue Features:
    - Immer als neues Package `com.agentwork.graphmesh.<featureName>` anlegen, in bestehender flacher Struktur.