# Feature 28: Multi-Tenancy — Design Spec

## Zusammenfassung

Soft-Tenant Multi-Tenancy: Wenn `X-Tenant-Id` + `X-User-Id` Headers vorhanden, wird der Zugriff auf Collections nach Mandant gefiltert. Ohne Headers läuft alles wie bisher (backward-kompatibel). Kein JWT, kein Spring Security, keine Keyspace-Isolation.

## Entscheidungen

| Entscheidung | Gewählt | Grund |
|---|---|---|
| Tenant-Enforcement | Soft (optional Headers) | Backward-kompatibel, kein Breaking Change |
| Auth-Methode | X-Tenant-Id/X-User-Id Header | Einfach, kein JWT/Security-Stack nötig |
| JWT | Weggelassen | Keine JWT-Library im Projekt, kein Setup |
| Spring Security | Weggelassen | Nicht im Projekt, zu viel Scope |
| Keyspace-Isolation | Weggelassen | Kein Multi-Tenant-Deployment, YAGNI |
| Coroutine-Propagation | Weggelassen | Kein Coroutine-Service-Layer |
| Decorator-Pattern | Weggelassen | Direkte Erweiterung des CollectionService |

## Komponenten

### TenantContext (`com.agentwork.graphmesh.tenant`)
ThreadLocal-basiertes Context-Objekt. `set()`, `get()`, `getOrNull()`, `clear()`.

### TenantFilter
`OncePerRequestFilter` — liest X-Tenant-Id + X-User-Id aus Request. Wenn beide vorhanden → TenantContext.set(). Wenn fehlend → weiter ohne Context (kein 401). Finally: TenantContext.clear().

### Collection-Erweiterung
- `tenantId: String? = null` und `ownerId: String? = null` — nullable für Backward-Compatibility
- Cassandra-Tabellen: `ALTER TABLE ... ADD tenant_id text, owner_id text`
- CassandraCollectionStore: mapRow/save erweitern

### CollectionService-Erweiterung
- `create()`: wenn TenantContext vorhanden → tenantId/ownerId setzen
- `findAll()`: wenn TenantContext vorhanden → nach tenantId filtern
- `findById()`: wenn TenantContext vorhanden UND collection.tenantId gesetzt UND nicht matching → AccessDeniedException
- `delete()/update()`: Zugriffsprüfung via findById

## Tests

| Test | Fokus |
|------|-------|
| TenantContextTest | set/get/getOrNull/clear, Thread-Isolation |
| TenantFilterTest | Header vorhanden/fehlend, Context-Lifecycle |
| CollectionServiceTenantTest | findAll-Filterung, findById-Zugriffsprüfung, create mit Tenant |
