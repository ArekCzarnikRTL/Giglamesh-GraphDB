---
name: Tech Stack Constraints
scope: backend
layer: architecture
priority: high
appliesTo: all
description: Erlaubte Sprachen, Frameworks und Infrastruktur-Komponenten.
---

# Tech Stack Rules

- Backend:
    - Sprache: Kotlin
    - JVM: Java 21
    - Build: Gradle 9.4.1 (Kotlin-DSL, `build.gradle.kts`)
    - Framework: Spring Boot 4.0.5, Spring Modulith 2.0.5
- AI:
    - Spring AI 2.0.0-M4 für MCP-Server-Funktionalität.
    - Koog 0.7.3 als Standard-Framework für LLM- und Agent-Integration.
- Storage & Messaging:
    - Apache Cassandra für operative Daten.
    - Qdrant für Vektor-/Embeddingsuche.
    - S3/MinIO für Dateien und große Blobs.
    - Kafka (Avro) für Events.
- Führe keine neuen Kerntechnologien (Persistenz, Messaging, AI-Frameworks) ein, ohne dies explizit abzustimmen.