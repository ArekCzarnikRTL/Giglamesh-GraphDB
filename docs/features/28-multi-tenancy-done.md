# Feature 28: Multi-Tenancy — Done

## Zusammenfassung

Soft-Tenant Multi-Tenancy implementiert. Wenn `X-Tenant-Id` + `X-User-Id` Headers vorhanden, wird der Zugriff auf Collections nach Mandant gefiltert. Ohne Headers läuft alles wie bisher (vollständig backward-kompatibel).

### Implementierte Komponenten

| Datei | Zweck |
|-------|-------|
| `tenant/TenantContext.kt` | ThreadLocal-basierter Mandantenkontext + AccessDeniedException |
| `tenant/TenantFilter.kt` | OncePerRequestFilter — Header-basierte Tenant-Auflösung (soft) |
| `collection/Collection.kt` | Erweitert: tenantId? + ownerId? Felder |
| `collection/CollectionSchemaInitializer.kt` | ALTER TABLE für tenant_id + owner_id Spalten |
| `collection/CassandraCollectionStore.kt` | Erweitert: tenant-Spalten in allen Queries |
| `collection/CollectionService.kt` | Erweitert: Tenant-Filterung in findAll, Zugriffsprüfung in findById/delete/update |

### Tests

| Test | Anzahl |
|------|--------|
| TenantContextTest | 4 Tests (set/get/clear, Thread-Isolation) |
| TenantFilterTest | 4 Tests (Header vorhanden/fehlend, Exception-Safety) |
| CollectionServiceTenantTest | 8 Tests (findAll-Filterung, Zugriffsprüfung, create mit Tenant) |

### Verhalten

| Szenario | Ergebnis |
|----------|----------|
| Request ohne Header | Alle Collections sichtbar (wie bisher) |
| Request mit X-Tenant-Id: acme | Nur Collections mit tenantId=acme oder tenantId=null sichtbar |
| Zugriff auf fremde Collection | AccessDeniedException |
| Collection erstellen mit Tenant | tenantId + ownerId automatisch gesetzt |
| Collection erstellen ohne Tenant | tenantId = null (shared) |

## Abweichungen vom Feature-Dokument

1. **Soft-Tenant** — kein HTTP 401 bei fehlenden Headern (backward-kompatibel)
2. **Kein JWT** — nur Header-basiert, keine JWT-Library
3. **Kein Spring Security** — nur OncePerRequestFilter
4. **Keine Keyspace-Isolation** — ein Keyspace für alle Tenants
5. **Keine Coroutine-Propagation** — kein Coroutine-Service-Layer
6. **Kein TenantAwareCollectionService Decorator** — direkte Erweiterung
7. **Nullable tenantId** — bestehende Collections ohne Tenant bleiben zugänglich

## Offene Punkte

- JWT-basierte Auflösung kann als Phase 2 ergänzt werden
- Spring Security Integration bei Bedarf nachrüstbar
- Keyspace-Isolation für Enterprise-Deployments möglich
- LibrarianService braucht keine eigene Tenant-Filterung (Dokumente sind an Collections gebunden)
