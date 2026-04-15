---
name: Testing Strategy
scope: backend
layer: testing
priority: high
appliesTo: all
description: Regeln für Unit- und Integration-Tests inklusive docker-compose-Nutzung.
---

# Testing Rules

- Unit-Tests:
    - Framework: `kotlin-test-junit5` (JUnit 5).
    - Ablage: `src/test/kotlin` im passenden Package.
    - Namenskonvention: `*Test.kt`.
    - Jede neue Business-Logik erhält mindestens einen positiven und einen negativen Testfall.
- Integration-Tests:
    - Setzen laufendes `docker-compose up` voraus (Cassandra, Qdrant, MinIO, Kafka).
    - Keine Testcontainers oder vergleichbare Infrastruktur-Emulatoren verwenden.
    - Benennung: `*IntegrationTest.kt` (bestehende Repo-Konvention).
    - In der Testklasse kurz dokumentieren, welche Services benötigt werden.
- Vor „done“:
    - Relevante Unit- und Integration-Tests laufen grün.