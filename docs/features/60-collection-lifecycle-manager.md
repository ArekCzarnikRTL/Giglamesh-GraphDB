# Feature 60: Collection Lifecycle Manager

## Problem

`CollectionService.delete()` ruft 4 Storage-Backends sequentiell auf: `quadStore.deleteCollection()` → `vectorStore.deleteCollection()` → `deleteBlobsForCollection()` → `collectionStore.delete()`. Kein Error-Handling zwischen den Schritten. Wenn Qdrant fehlschlaegt, nachdem Cassandra-Quads schon geloescht sind, bleibt die Collection in einem korrupten Zustand. Zusaetzlich: Document-Blobs unter `doc/{uuid}` werden beim Collection-Delete gar nicht aufgeraeumt (nur `collections/{id}/`-Prefix-Blobs). OrphanSweepService existiert als Safety-Net, aber Partial-Failure ist unsichtbar.

## Ziel

Einfuehrung eines dedizierten `CollectionLifecycleManager`, der den gesamten Purge-Vorgang einer Collection in einzeln abgesicherten Schritten durchfuehrt und ein transparentes `PurgeResult` zurueckgibt.

1. **`CollectionLifecycleManager.purge(id): PurgeResult`** — neuer `@Service` in `collection/`, uebernimmt die komplette Loesch-Orchestrierung.
2. **Isoliertes Error-Handling** — jeder Backend-Call einzeln in try/catch. Ein Qdrant-Ausfall verhindert nicht den Blob-Cleanup.
3. **`PurgeResult` mit per-Step-Status** — `quadsDeleted`, `vectorsDeleted`, `blobsDeleted`, `documentsDeleted`, `collectionRowDeleted`, `failures`.
4. **Crash-sichere Reihenfolge** — Collection-Row wird zuletzt geloescht. Bei Crash/Retry findet ein erneuter Purge die Collection noch.
5. **Document-Blob-Cleanup** — Document-Blobs (`doc/{uuid}`) werden jetzt mit aufgeraeumt (aktueller Bug gefixt).
6. **`CollectionService.delete()` vereinfacht** — delegiert an `purge()`, verliert 3 Constructor-Dependencies (`QuadStore`, `VectorStore`, `BlobStore`).
7. **Idempotent** — Re-purge einer bereits geloeschten Collection ist ein No-Op.
8. **Kein persistierter Delete-State** — kein `DELETING`-Status, kein State-Tracking. OrphanSweepService existiert bereits als Fallback.

## Voraussetzungen

| Abhaengigkeit                                           | Status           | Blocker? |
|---------------------------------------------------------|------------------|----------|
| Feature 02 (Cassandra Storage)                          | Implementiert    | Nein     |
| Feature 03 (S3/MinIO Blob Storage)                     | Implementiert    | Nein     |
| Feature 04 (Qdrant Vector Store)                       | Implementiert    | Nein     |
| Feature 08 (Collections)                               | Implementiert    | Nein     |
| Feature 09 (Librarian / Document Management)           | Implementiert    | Nein     |

Keine Infra-Aenderung in Cassandra / Qdrant / MinIO / docker-compose.

## Architektur

### Ist-Zustand

```kotlin
// CollectionService.kt — delete()
fun delete(collectionId: String) {
    quadStore.deleteCollection(collectionId)
    vectorStore.deleteCollection(collectionId)
    deleteBlobsForCollection(collectionId)       // nur collections/{id}/ prefix
    collectionStore.delete(collectionId)
    // Kein try/catch — Partial-Failure = korrupter Zustand
    // Document-Blobs (doc/{uuid}) werden nicht aufgeraeumt
}
```

Ein Fehler in Schritt 2 (Qdrant) bricht die gesamte Methode ab. Schritt 3 und 4 werden nie erreicht. Die Collection-Row existiert noch, aber die Quads sind bereits geloescht. Document-Blobs unter `doc/{uuid}` bleiben als Waisen zurueck.

### Soll-Zustand

```kotlin
// CollectionService.kt — delete() delegiert
fun delete(collectionId: String) {
    val result = lifecycleManager.purge(collectionId)
    if (!result.complete) {
        log.warn("Partial purge for collection {}: {}", collectionId, result.failures)
    }
}
```

`CollectionService` verliert die direkten Dependencies auf `QuadStore`, `VectorStore` und `BlobStore`. Die gesamte Loesch-Logik liegt im `CollectionLifecycleManager`.

### Subsection 1: PurgeResult & PurgeStepFailure

```kotlin
data class PurgeResult(
    val collectionId: String,
    val quadsDeleted: Boolean,
    val vectorsDeleted: Boolean,
    val blobsDeleted: Int,
    val documentsDeleted: Int,
    val collectionRowDeleted: Boolean,
    val failures: List<PurgeStepFailure>
) {
    val complete: Boolean get() = failures.isEmpty()
}

data class PurgeStepFailure(val step: String, val error: String)
```

`PurgeResult` ist ein reines Daten-Objekt ohne Seiteneffekte. `complete` signalisiert, ob alle Schritte erfolgreich waren. Bei Partial-Failure enthaelt `failures` die fehlgeschlagenen Schritte mit Fehlermeldung.

### Subsection 2: CollectionLifecycleManager

```kotlin
@Service
class CollectionLifecycleManager(
    private val collectionStore: CollectionStore,
    private val quadStore: QuadStore,
    private val vectorStore: VectorStore,
    private val blobStore: BlobStore,
    private val documentStore: DocumentStore,
    @Value("\${graphmesh.storage.default-bucket}") private val defaultBucket: String
) {
    fun purge(collectionId: String): PurgeResult {
        val failures = mutableListOf<PurgeStepFailure>()

        val quadsDeleted = runStep("quads", failures) {
            quadStore.deleteCollection(collectionId)
        }
        val vectorsDeleted = runStep("vectors", failures) {
            vectorStore.deleteCollection(collectionId)
        }
        val (blobsDeleted, documentsDeleted) = runBlobCleanup(collectionId, failures)
        val collectionRowDeleted = runStep("collectionRow", failures) {
            collectionStore.delete(collectionId)
        }

        return PurgeResult(
            collectionId = collectionId,
            quadsDeleted = quadsDeleted,
            vectorsDeleted = vectorsDeleted,
            blobsDeleted = blobsDeleted,
            documentsDeleted = documentsDeleted,
            collectionRowDeleted = collectionRowDeleted,
            failures = failures
        )
    }

    private fun runStep(
        step: String,
        failures: MutableList<PurgeStepFailure>,
        action: () -> Unit
    ): Boolean {
        return try {
            action()
            true
        } catch (e: Exception) {
            failures.add(PurgeStepFailure(step, e.message ?: e.javaClass.simpleName))
            false
        }
    }
}
```

- Reihenfolge: Quads → Vectors → Document-Blobs → Collection-Blobs → Document-Rows → Collection-Row (zuletzt).
- `runStep` kapselt jeden Backend-Call in try/catch und sammelt Fehler statt abzubrechen.
- Idempotenz: Wenn die Collection bereits geloescht ist, liefern die Backend-Calls entweder No-Op-Verhalten oder einen Fehler, der gesammelt wird.

### Subsection 3: Document-Blob-Cleanup (Bug-Fix)

```kotlin
private fun runBlobCleanup(
    collectionId: String,
    failures: MutableList<PurgeStepFailure>
): Pair<Int, Int> {
    var blobsDeleted = 0
    var documentsDeleted = 0

    // 1. Document-Blobs (doc/{uuid}) — aktuell fehlend
    runStep("documentBlobs", failures) {
        val documents = documentStore.findByCollection(collectionId)
        documents.forEach { doc ->
            blobStore.delete(defaultBucket, "doc/${doc.id}")
        }
        blobsDeleted += documents.size
    }

    // 2. Collection-Blobs (collections/{id}/) — existierender Pfad
    runStep("collectionBlobs", failures) {
        val collectionBlobs = blobStore.listByPrefix(defaultBucket, "collections/$collectionId/")
        collectionBlobs.forEach { key ->
            blobStore.delete(defaultBucket, key)
        }
        blobsDeleted += collectionBlobs.size
    }

    // 3. Document-Rows
    runStep("documentRows", failures) {
        val count = documentStore.deleteByCollection(collectionId)
        documentsDeleted = count
    }

    return Pair(blobsDeleted, documentsDeleted)
}
```

Der aktuelle Bug: `deleteBlobsForCollection()` raeumt nur den `collections/{id}/`-Prefix auf. Document-Blobs unter `doc/{uuid}` bleiben als Waisen zurueck. Der neue Code loescht beide Pfade.

## Betroffene Dateien

### Backend

| Datei                                                                                                 | Aenderung                                                                                |
|-------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/collection/CollectionLifecycleManager.kt`                   | NEU — Purge-Orchestrierung mit per-Step-Error-Handling                                    |
| `src/main/kotlin/com/agentwork/graphmesh/collection/PurgeResult.kt`                                  | NEU — `PurgeResult` und `PurgeStepFailure` Data Classes                                   |
| `src/main/kotlin/com/agentwork/graphmesh/collection/CollectionService.kt`                            | `delete()` delegiert an `lifecycleManager.purge()`; `QuadStore`/`VectorStore`/`BlobStore` aus Constructor entfernen; `deleteBlobsForCollection()` entfernen |

### Frontend

Keine Aenderungen.

### Tests

| Datei                                                                                          | Aenderung                                                                                       |
|------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/collection/CollectionLifecycleManagerTest.kt`        | NEU — Unit-Test: alle Steps erfolgreich, Partial-Failure (einzelner Step schlaegt fehl, Rest laeuft weiter), Idempotenz bei bereits geloeschter Collection, Document-Blob-Cleanup wird ausgefuehrt |
| `src/test/kotlin/com/agentwork/graphmesh/collection/CollectionServiceTest.kt`                 | Anpassen: `delete()` prueft Delegation an `lifecycleManager.purge()`, reduzierte Constructor-Dependencies |

## Akzeptanzkriterien

- [ ] `CollectionLifecycleManager.purge(id)` loescht Quads, Vectors, Document-Blobs, Collection-Blobs, Document-Rows und Collection-Row in dieser Reihenfolge.
- [ ] Collection-Row wird immer als letzter Schritt geloescht (crash-safe: Retry findet Collection noch).
- [ ] Bei Fehler in einem Schritt (z. B. Qdrant nicht erreichbar) laufen alle weiteren Schritte trotzdem durch; `PurgeResult.failures` enthaelt den fehlgeschlagenen Schritt.
- [ ] `PurgeResult.complete` ist `true` nur wenn alle Schritte erfolgreich waren.
- [ ] Document-Blobs unter `doc/{uuid}` werden beim Collection-Delete mit aufgeraeumt (Bug-Fix verifiziert durch Test).
- [ ] `CollectionService.delete()` delegiert vollstaendig an `CollectionLifecycleManager.purge()` und hat keine direkten Dependencies mehr auf `QuadStore`, `VectorStore`, `BlobStore`.
- [ ] Re-purge einer bereits geloeschten Collection ist ein No-Op (kein Fehler, leeres `PurgeResult`).
- [ ] Kein `DELETING`-Status oder persistierter Delete-State eingefuehrt.
- [ ] Bestehende Tests von Feature 02 / 03 / 04 / 08 / 09 bleiben gruen.
- [ ] Bestehende Funktionalitaet bleibt unberuehrt.
