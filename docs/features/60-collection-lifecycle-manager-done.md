# Feature 60: Collection Lifecycle Manager — Done

Abschlussdatum: 2026-04-16
Spec: [60-collection-lifecycle-manager.md](60-collection-lifecycle-manager.md)

## Zusammenfassung

Die Cross-Backend-Loesch-Logik aus `CollectionService.delete()` wurde in einen dedizierten `CollectionLifecycleManager.purge()` extrahiert. Jeder Backend-Call (Quads, Vectors, Document-Blobs, Collection-Blobs, Document-Rows, Collection-Row) ist einzeln in try/catch abgesichert. Partial-Failures korruptieren den Collection-Zustand nicht mehr. Zusaetzlich behoben: Document-Blobs unter `doc/{uuid}` wurden beim Collection-Delete bisher nicht aufgeraeumt.

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/collection/CollectionLifecycleManager.kt`** — NEU (132 LOC). `@Service` mit `purge(collectionId): PurgeResult`. Orchestriert 6 Loesch-Schritte in crash-sicherer Reihenfolge:
  1. Quads loeschen (`quadStore.deleteCollection`)
  2. Vectors loeschen (`vectorStore.deleteCollection`)
  3. Document-Blobs loeschen (`doc/{uuid}` via `contentUri`)
  4. Collection-Blobs loeschen (`collections/{id}/` Prefix)
  5. Document-Rows loeschen (`documentStore.deleteWithChildren`)
  6. Collection-Row loeschen (`collectionStore.delete`) — immer zuletzt

  `runStep(step, failures, action)` kapselt jeden Call in try/catch und sammelt Fehler in `MutableList<PurgeStepFailure>`. Bei Crash/Retry findet ein erneuter Purge die Collection noch, da die Row zuletzt geloescht wird.

  `PurgeResult` data class mit per-Step-Status (`quadsDeleted`, `vectorsDeleted`, `blobsDeleted`, `documentsDeleted`, `collectionRowDeleted`) und `failures: List<PurgeStepFailure>`. `complete: Boolean` ist ein computed Property.

  `PurgeStepFailure` data class mit `step: String` und `error: String`.

- **`src/main/kotlin/com/agentwork/graphmesh/collection/CollectionService.kt`** — `delete()` delegiert vollstaendig an `lifecycleManager.purge()`. Direkte Dependencies auf `QuadStore`, `VectorStore`, `BlobStore` entfernt. Alte inline-Loesch-Logik und `deleteBlobsForCollection()` entfernt.

### Tests

- **`src/test/kotlin/com/agentwork/graphmesh/collection/CollectionLifecycleManagerTest.kt`** — NEU (213 LOC), 8 Tests mit MockK:
  1. Alle Backends erfolgreich → `complete = true`
  2. Quad-Fehler → Rest laeuft weiter, `failures` enthaelt "quads"
  3. Vector-Fehler → Rest laeuft weiter, `failures` enthaelt "vectors"
  4. Nicht-existierende Collection → idempotent, kein Fehler
  5. Document-Blob-Cleanup wird ausgefuehrt (Bug-Fix-Verifikation)
  6. Crash-sichere Reihenfolge: Collection-Row wird zuletzt geloescht (`verifyOrder`)
  7. Mehrere Step-Failures gleichzeitig
  8. Documents ohne Blobs werden uebersprungen
- **`src/test/kotlin/com/agentwork/graphmesh/collection/CollectionServiceTenantTest.kt`** — Constructor-Mocks an neue `CollectionService`-Signatur (mit `lifecycleManager`) angepasst.

## Akzeptanzkriterien

- [x] `CollectionLifecycleManager.purge(id)` loescht Quads, Vectors, Document-Blobs, Collection-Blobs, Document-Rows und Collection-Row in dieser Reihenfolge.
- [x] Collection-Row wird immer als letzter Schritt geloescht (crash-safe).
- [x] Bei Fehler in einem Schritt laufen alle weiteren Schritte trotzdem durch; `PurgeResult.failures` enthaelt den fehlgeschlagenen Schritt.
- [x] `PurgeResult.complete` ist `true` nur wenn alle Schritte erfolgreich waren.
- [x] Document-Blobs unter `doc/{uuid}` werden beim Collection-Delete mit aufgeraeumt (Bug-Fix).
- [x] `CollectionService.delete()` delegiert vollstaendig an `CollectionLifecycleManager.purge()`.
- [x] Re-purge einer bereits geloeschten Collection ist idempotent.
- [x] Kein `DELETING`-Status oder persistierter Delete-State eingefuehrt.
- [x] Bestehende Tests bleiben gruen.

## Abweichungen vom Feature-Dokument

- **`PurgeResult`/`PurgeStepFailure` in gleicher Datei**: Beide data classes leben direkt in `CollectionLifecycleManager.kt` statt in einer separaten `PurgeResult.kt`, da sie nur dort gebraucht werden.
- **Document-Blob-Cleanup via `contentUri`**: Die Spec schlug `blobStore.delete(bucket, "doc/${doc.id}")` vor. Die Implementierung nutzt `doc.contentUri` (das tatsaechliche Feld), was korrekter ist, da der Blob-Pfad nicht immer `doc/{id}` sein muss.
- **`blobStore.deleteBatch()`**: Fuer Collection-Blobs wird `deleteBatch()` statt einzelner `delete()`-Calls verwendet — effizienter bei vielen Blobs.

## Commits

```
3e955b4 feat(collection): add CollectionLifecycleManager with per-step error isolation (feature 60)
fa6e699 fix: resolve build errors from feature 58/60 integration
```
