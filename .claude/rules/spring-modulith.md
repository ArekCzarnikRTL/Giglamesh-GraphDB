---
name: Spring Modulith Boundaries
scope: backend
layer: architecture
priority: high
appliesTo: spring-modulith
description: Feature-package boundaries and cross-module access rules.
---

# Spring Modulith Rules

- Each feature lives under `com.agentwork.graphmesh.<feature>`. New features always get a new package here.
- No new Gradle submodules — one Spring Modulith JAR.
- Every feature must follow the hexagonal layout in `backend-coding.md`. Introduce subpackages when moving files, not speculatively.
- **Cross-feature access:** only via the target feature's `application/port/in/` interfaces. No direct access to foreign `domain/`, `adapter/`, or concrete service class.
- **Global `api/` (transitional):** controllers depend on `port/in/` interfaces only; move into `adapter/in/` in PR4.
- **Shared domain:** multi-feature types go in `com.agentwork.graphmesh.common.domain` — see `backend-coding.md`.
