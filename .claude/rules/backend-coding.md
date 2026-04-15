---
name: Backend Coding Standards
scope: backend
layer: coding
priority: high
appliesTo: spring-modulith
description: Schichtentrennung, Domain-Modelierung und Fehlerbehandlung im Backend.
---

# Backend Coding Rules

- Schichten:
    - Controller: Transport & Mapping (DTO ↔ Domain), keine Business-Logik.
    - Application: Use Cases, Orchestrierung von Domain & externen Systemen.
    - Domain: Entities, Aggregate Roots, Value Objects, Domain Events, Ports.
- Verwende Kotlin-Idiome:
    - Datenklassen, `sealed`-Typen, Value Objects statt primitiver Strings/Ints.
- Fehlerhandling:
    - Domain-Fehler als spezifische Exceptions oder Result-Typen modellieren.
    - API-Schicht übersetzt in passende HTTP-Codes/Fehlerobjekte.
- Vermeide:
    - "Magische" globale Utility-Klassen, die Domain-Regeln quer über Features kapseln.
    - Cross-Layer-Shortcuts (z.B. Domain ruft direkt Infrastruktur-Client).