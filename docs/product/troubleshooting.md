---
title: Fehlerbehebung
nav_order: 12
---

# Fehlerbehebung

Dieses Dokument beschreibt haeufige Probleme bei der Nutzung von GraphMesh und deren Loesungen.

---

## LLM-Verbindung

### API-Key nicht konfiguriert

**Symptom:** Dokumentenextraktion schlaegt fehl. In den Logs erscheint eine Fehlermeldung zur LLM-Verbindung oder ein leerer API-Key wird gemeldet.

**Ursache:** Der API-Key fuer den konfigurierten LLM-Provider ist nicht gesetzt.

**Loesung:**
1. Umgebungsvariable pruefen: `OPENAI_API_KEY` (fuer OpenAI) bzw. `ANTHROPIC_API_KEY` (fuer Anthropic) muss gesetzt sein.
2. In `application.yml` pruefen, ob der gewuenschte Provider aktiviert ist:
   - `ai.koog.openai.enabled: true` und `OPENAI_API_KEY` gesetzt, oder
   - `ai.koog.anthropic.enabled: true` und `ANTHROPIC_API_KEY` gesetzt.
3. Anwendung nach Aenderung der Umgebungsvariablen neu starten.

### LLM-Provider antwortet nicht

**Symptom:** Extraktion haengt oder bricht mit Timeout ab. Im Log erscheinen Verbindungsfehler zum LLM-Provider.

**Ursache:** Der LLM-Provider ist nicht erreichbar (Netzwerkproblem, API-Ausfall) oder das konfigurierte Modell existiert nicht.

**Loesung:**
1. Internetverbindung pruefen.
2. Status-Seite des Providers pruefen (z.B. status.openai.com).
3. Pruefen, ob das konfigurierte Modell gueltig ist. Standard-Extraktionsmodell: `gpt-4o-mini` (aenderbar ueber `LLM_EXTRACTION_MODEL`).
4. Bei Nutzung von Ollama: Sicherstellen, dass der Ollama-Server unter der konfigurierten URL laeuft (`OLLAMA_BASE_URL`, Standard: `http://localhost:11434`).

---

## Dokument-Upload

### PDF-Dekodierung schlaegt fehl

**Symptom:** Nach dem Hochladen eines PDFs erscheint eine Fehlermeldung. Das Dokument bleibt im Status "Fehler".

**Ursache:** Das PDF ist beschaedigt, passwortgeschuetzt oder enthaelt nur gescannte Bilder ohne Textebene.

**Loesung:**
1. Pruefen, ob das PDF in einem Standard-PDF-Viewer korrekt geoeffnet werden kann.
2. Sicherstellen, dass das PDF nicht passwortgeschuetzt ist.
3. Bei gescannten Dokumenten: OCR-Vorverarbeitung durchfuehren, bevor das Dokument hochgeladen wird.

### Dokument nicht gefunden nach Upload

**Symptom:** GraphQL-Abfrage liefert Fehler "Document not found" fuer ein soeben hochgeladenes Dokument.

**Ursache:** Die Verarbeitung des Dokuments ist noch nicht abgeschlossen, oder der Upload ist fehlgeschlagen.

**Loesung:**
1. Wenige Sekunden warten - die Verarbeitung laeuft asynchron ueber Kafka.
2. Im Admin-Dashboard den Verarbeitungsstatus des Dokuments pruefen.
3. Logs auf Fehlermeldungen des `PdfDecoderConsumer` oder `ChunkerConsumer` pruefen.

---

## Abfrage-Probleme

### Keine Ergebnisse bei semantischer Suche

**Symptom:** Eine NLP-Abfrage liefert keine oder irrelevante Ergebnisse.

**Ursache:** Die Embeddings wurden noch nicht erzeugt, die Collection enthaelt keine Dokumente, oder die Abfrage ist zu unspezifisch.

**Loesung:**
1. Sicherstellen, dass Dokumente in der gewaehlten Collection vorhanden und vollstaendig verarbeitet sind.
2. Im Admin-Dashboard pruefen, ob Embeddings fuer die Collection erzeugt wurden.
3. Abfrage praezisieren - spezifischere Begriffe verwenden.
4. Pruefen, ob das Embedding-Modell korrekt konfiguriert ist (`EMBEDDING_MODEL`, Standard: `text-embedding-3-small`).

### Collection nicht gefunden

**Symptom:** Fehler "Collection not found: <id>" bei Abfragen oder Dokumentenoperationen.

**Ursache:** Die angegebene Collection-ID existiert nicht oder gehoert zu einem anderen Mandanten.

**Loesung:**
1. Collection-ID pruefen - im Admin-Dashboard die vorhandenen Collections einsehen.
2. Sicherstellen, dass der korrekte Mandant (Tenant) im Request-Header gesetzt ist.
3. Neue Collection anlegen, falls die gewuenschte nicht existiert.

### Abfrage-Timeout

**Symptom:** GraphQL-Abfragen brechen mit Timeout ab, insbesondere bei grossen Collections.

**Ursache:** Die Qdrant-Vektordatenbank ist ueberlastet oder die Abfrage traversiert zu viele Graph-Knoten.

**Loesung:**
1. Qdrant-Service pruefen (siehe Abschnitt Infrastruktur).
2. Bei Graph-Abfragen das `limit`-Feld verwenden, um die Ergebnismenge einzuschraenken.
3. Bei RAG-Abfragen: kleinere, praezisere Fragen formulieren.

---

## Infrastruktur

### Cassandra nicht erreichbar

**Symptom:** Anwendung startet nicht oder Datenbankoperationen schlagen fehl. Im Log: Verbindungsfehler zu Cassandra.

**Ursache:** Cassandra laeuft nicht oder ist unter der konfigurierten Adresse nicht erreichbar.

**Loesung:**
1. Pruefen, ob Cassandra laeuft: `docker ps` (bei Docker-Setup) oder `nodetool status`.
2. Umgebungsvariablen pruefen: `CASSANDRA_CONTACT_POINTS` (Standard: `localhost`), `CASSANDRA_PORT` (Standard: `9042`).
3. Sicherstellen, dass der Keyspace `graphmesh` existiert.
4. `docker compose up -d cassandra` ausfuehren, falls der Container nicht laeuft.

### Qdrant nicht erreichbar

**Symptom:** Embedding-Erzeugung oder semantische Suche schlaegt fehl. Im Log: Verbindungsfehler zu Qdrant.

**Ursache:** Qdrant laeuft nicht oder ist unter der konfigurierten Adresse nicht erreichbar.

**Loesung:**
1. Pruefen, ob Qdrant laeuft: `docker ps` oder `curl http://localhost:6333/healthz`.
2. Umgebungsvariablen pruefen: `QDRANT_HOST` (Standard: `localhost`), `QDRANT_GRPC_PORT` (Standard: `6334`).
3. `docker compose up -d qdrant` ausfuehren, falls der Container nicht laeuft.

### Kafka nicht erreichbar

**Symptom:** Dokumente werden hochgeladen, aber nicht verarbeitet. Im Log: Verbindungsfehler zu Kafka oder Schema Registry.

**Ursache:** Kafka-Broker oder Schema Registry laufen nicht.

**Loesung:**
1. Pruefen, ob Kafka laeuft: `docker ps` oder die Broker-Adresse direkt testen.
2. Umgebungsvariablen pruefen: `KAFKA_BOOTSTRAP_SERVERS` (Standard: `localhost:9092`), `SCHEMA_REGISTRY_URL` (Standard: `http://localhost:8181`).
3. `docker compose up -d kafka schema-registry` ausfuehren.
4. Nach Neustart von Kafka: Die Anwendung neu starten, damit Consumer-Gruppen neu verbunden werden.

### MinIO/S3 nicht erreichbar

**Symptom:** Dokument-Upload schlaegt fehl. Im Log: S3-Verbindungsfehler.

**Ursache:** MinIO/S3-Service laeuft nicht oder Zugangsdaten sind falsch.

**Loesung:**
1. Pruefen, ob MinIO laeuft: `docker ps` oder `curl http://localhost:9000/minio/health/live`.
2. Umgebungsvariablen pruefen: `MINIO_ENDPOINT` (Standard: `http://localhost:9000`), `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` (Standard jeweils: `minioadmin`).
3. `docker compose up -d minio` ausfuehren, falls der Container nicht laeuft.

---

## Frontend

### Verbindung zum Backend schlaegt fehl

**Symptom:** Die Web-Oberflaeche zeigt keine Daten an oder meldet Netzwerkfehler.

**Ursache:** Das Backend laeuft nicht auf dem erwarteten Port oder die Frontend-Konfiguration zeigt auf eine falsche URL.

**Loesung:**
1. Pruefen, ob das Backend laeuft und unter `http://localhost:8083/graphql` erreichbar ist.
2. GraphiQL im Browser oeffnen: `http://localhost:8083/graphiql` - wenn diese Seite laedt, ist das Backend erreichbar.
3. Falls das Backend auf einem anderen Port laeuft: Umgebungsvariable `NEXT_PUBLIC_GRAPHQL_URL` im Frontend setzen (z.B. `http://localhost:8083/graphql`).

### WebSocket-Verbindung fuer Streaming bricht ab

**Symptom:** Streaming-Antworten (z.B. RAG-Abfragen mit Live-Ausgabe) starten nicht oder brechen mittendrin ab.

**Ursache:** WebSocket-Verbindung zum Backend kann nicht hergestellt werden.

**Loesung:**
1. Pruefen, ob der WebSocket-Endpunkt erreichbar ist: `ws://localhost:8083/graphql`.
2. Falls ein Reverse-Proxy verwendet wird: Sicherstellen, dass WebSocket-Upgrade-Requests weitergeleitet werden.
3. Umgebungsvariable `NEXT_PUBLIC_GRAPHQL_WS_URL` setzen, falls die WebSocket-URL von der HTTP-URL abweicht.
4. Browser-Konsole auf Fehlermeldungen pruefen.

### Admin-Dashboard zeigt keine Statistiken

**Symptom:** Das Admin-Dashboard laedt, aber Metriken und Collection-Statistiken bleiben leer.

**Ursache:** Die GraphQL-Abfragen fuer Admin-Daten schlagen fehl, oft wegen fehlender Backend-Verbindung.

**Loesung:**
1. Backend-Verbindung pruefen (siehe oben).
2. Browser-Entwicklertools oeffnen und Netzwerk-Tab auf fehlgeschlagene GraphQL-Requests pruefen.
3. Sicherstellen, dass mindestens eine Collection existiert.

---

## Allgemeine Tipps

- **Logs pruefen:** Die Anwendungslogs befinden sich unter `logs/graphmesh.log`. Log-Level ist standardmaessig `DEBUG`.
- **Docker-Services starten:** Alle Infrastruktur-Dienste gleichzeitig starten: `docker compose up -d`.
- **Konfiguration:** Alle konfigurierbaren Werte koennen ueber Umgebungsvariablen gesetzt werden. Die Standardwerte finden sich in `application.yml`.
- **GraphiQL:** Unter `http://localhost:8083/graphiql` steht ein interaktiver GraphQL-Editor zur Verfuegung, um Abfragen direkt zu testen.
