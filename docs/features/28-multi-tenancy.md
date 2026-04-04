# Feature 28: Multi-Tenancy

## Problem

Alle Benutzer teilen sich denselben Datenraum -- Collections, Dokumente und Knowledge-Graph-Daten sind nicht nach
Mandanten getrennt. Ein Benutzer kann potenziell auf Collections und Dokumente anderer Benutzer zugreifen. Fuer
Enterprise-Deployments fehlen Mechanismen zur Mandantenisolierung, sowohl auf Applikationsebene (
Collection-Zugriffskontrolle) als auch auf Datenbankebene (Keyspace-Trennung in Cassandra).

## Ziel

Implementierung einer mandantenfaehigen Architektur mit Collection-basierter Zugriffskontrolle, automatischer
Tenant-Kontext-Propagation und optionaler Cassandra-Keyspace-Isolation.

1. **Tenant-Kontext** -- Automatische Aufloesung des Mandanten aus JWT-Token oder Request-Headern
2. **Collection-Isolation** -- Benutzer koennen nur auf eigene Collections zugreifen
3. **Automatische Filterung** -- Alle Queries werden transparent nach Mandant gefiltert
4. **Keyspace-Isolation** -- Optionale Trennung auf Cassandra-Keyspace-Ebene pro Mandant
5. **Spring Security Integration** -- Nahtlose Einbindung in die bestehende Sicherheitsinfrastruktur
6. **Propagation** -- Tenant-Kontext wird automatisch durch alle Service-Aufrufe propagiert

## Voraussetzungen

| Abhaengigkeit                                                            | Status     | Blocker? |
|--------------------------------------------------------------------------|------------|----------|
| Feature 02: Cassandra Storage (CassandraClient, QuadStore)               | Geplant    | Ja       |
| Feature 08: Collection Management (CollectionService, Collection)        | Geplant    | Ja       |
| Feature 09: Document Management / Librarian (LibrarianService, Document) | Geplant    | Ja       |
| Feature 06: Configuration Service (ConfigService)                        | Geplant    | Ja       |
| Spring Security                                                          | Verfuegbar | Nein     |
| JSON Web Tokens (JWT)                                                    | Verfuegbar | Nein     |

## Architektur

### TenantContext

```kotlin
package com.graphmesh.tenant

/**
 * Haelt den aktuellen Mandantenkontext fuer den laufenden Request.
 * Wird via ThreadLocal / Coroutine-Context propagiert.
 */
data class TenantContext(
    /** Eindeutige Mandanten-ID (z.B. "acme-corp"). */
    val tenantId: String,
    /** Benutzer-ID innerhalb des Mandanten. */
    val userId: String,
    /** Optionaler dedizierter Cassandra-Keyspace fuer diesen Mandanten. */
    val keyspace: String? = null
) {
    companion object {
        private val current = ThreadLocal<TenantContext>()

        fun set(context: TenantContext) = current.set(context)
        fun get(): TenantContext = current.get()
            ?: throw IllegalStateException("Kein TenantContext gesetzt.")
        fun getOrNull(): TenantContext? = current.get()
        fun clear() = current.remove()
    }
}
```

### TenantResolver

```kotlin
package com.graphmesh.tenant

import jakarta.servlet.http.HttpServletRequest

/**
 * Loest den Mandantenkontext aus dem eingehenden Request auf.
 * Unterstuetzt JWT-Token und HTTP-Header-basierte Aufloesung.
 */
interface TenantResolver {

    /**
     * Extrahiert den TenantContext aus dem HTTP-Request.
     *
     * @param request Der eingehende HTTP-Request.
     * @return Der aufgeloeste TenantContext.
     * @throws TenantResolutionException wenn kein Mandant ermittelt werden kann.
     */
    fun resolve(request: HttpServletRequest): TenantContext
}

/**
 * JWT-basierter TenantResolver.
 * Liest tenant_id und user_id aus den JWT-Claims.
 */
class JwtTenantResolver : TenantResolver {

    override fun resolve(request: HttpServletRequest): TenantContext {
        val token = extractBearerToken(request)
            ?: throw TenantResolutionException("Kein Bearer-Token vorhanden.")
        val claims = validateAndDecode(token)
        return TenantContext(
            tenantId = claims["tenant_id"] as? String
                ?: throw TenantResolutionException("tenant_id fehlt im Token."),
            userId = claims["sub"] as? String
                ?: throw TenantResolutionException("sub fehlt im Token."),
            keyspace = claims["keyspace"] as? String
        )
    }

    private fun extractBearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        return if (header.startsWith("Bearer ")) header.substring(7) else null
    }
}

/**
 * Header-basierter TenantResolver (fuer Entwicklung/Tests).
 */
class HeaderTenantResolver : TenantResolver {

    override fun resolve(request: HttpServletRequest): TenantContext {
        return TenantContext(
            tenantId = request.getHeader("X-Tenant-Id")
                ?: throw TenantResolutionException("X-Tenant-Id Header fehlt."),
            userId = request.getHeader("X-User-Id")
                ?: throw TenantResolutionException("X-User-Id Header fehlt."),
            keyspace = request.getHeader("X-Keyspace")
        )
    }
}

class TenantResolutionException(message: String) : RuntimeException(message)
```

### TenantFilter (Servlet Filter)

```kotlin
package com.graphmesh.tenant

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Servlet-Filter, der den TenantContext fuer jeden Request setzt.
 * Muss in der Spring Security FilterChain registriert werden.
 */
class TenantFilter(
    private val resolver: TenantResolver
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        try {
            val context = resolver.resolve(request)
            TenantContext.set(context)
            filterChain.doFilter(request, response)
        } catch (e: TenantResolutionException) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.message)
        } finally {
            TenantContext.clear()
        }
    }
}
```

### TenantAwareCollectionService

```kotlin
package com.graphmesh.tenant

import com.graphmesh.collection.Collection
import com.graphmesh.collection.CollectionService
import java.util.UUID

/**
 * Dekoriert den CollectionService mit automatischer Mandantenfilterung.
 * Alle Operationen werden auf den aktuellen Mandanten eingeschraenkt.
 */
class TenantAwareCollectionService(
    private val delegate: CollectionService
) : CollectionService {

    override suspend fun listCollections(): List<Collection> {
        val tenant = TenantContext.get()
        return delegate.listCollections()
            .filter { it.tenantId == tenant.tenantId }
    }

    override suspend fun getCollection(id: UUID): Collection {
        val collection = delegate.getCollection(id)
        val tenant = TenantContext.get()
        if (collection.tenantId != tenant.tenantId) {
            throw AccessDeniedException(
                "Mandant '${tenant.tenantId}' hat keinen Zugriff auf Collection '$id'."
            )
        }
        return collection
    }

    override suspend fun createCollection(name: String, description: String): Collection {
        val tenant = TenantContext.get()
        return delegate.createCollection(name, description).copy(
            tenantId = tenant.tenantId,
            ownerId = tenant.userId
        )
    }

    override suspend fun deleteCollection(id: UUID) {
        // Zugriffspruefung vor Loeschung
        getCollection(id)
        delegate.deleteCollection(id)
    }
}

class AccessDeniedException(message: String) : RuntimeException(message)
```

### Keyspace-Isolation

```kotlin
package com.graphmesh.tenant

import com.graphmesh.storage.cassandra.CassandraClient

/**
 * Keyspace-Resolver fuer mandantenspezifische Cassandra-Keyspaces.
 * Ermoeglicht vollstaendige Datenisolierung auf Datenbankebene.
 */
class TenantKeyspaceResolver(
    private val defaultKeyspace: String = "graphmesh"
) {

    /**
     * Ermittelt den Keyspace fuer den aktuellen Mandanten.
     * Verwendet den konfigurierten Keyspace oder den Default.
     */
    fun resolveKeyspace(): String {
        val context = TenantContext.getOrNull() ?: return defaultKeyspace
        return context.keyspace ?: "${defaultKeyspace}_${sanitize(context.tenantId)}"
    }

    /**
     * Stellt sicher, dass der Keyspace existiert.
     */
    suspend fun ensureKeyspace(client: CassandraClient) {
        val keyspace = resolveKeyspace()
        client.execute("""
            CREATE KEYSPACE IF NOT EXISTS $keyspace
            WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
        """)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_]"), "_").lowercase()
}
```

### Coroutine-Context-Propagation

```kotlin
package com.graphmesh.tenant

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Coroutine-Context-Element fuer die Propagation des TenantContext
 * ueber Coroutine-Grenzen hinweg (z.B. bei async/launch).
 */
class TenantCoroutineContext(
    val tenant: TenantContext
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TenantCoroutineContext>
}

/**
 * Fuehrt einen Suspending-Block mit dem angegebenen TenantContext aus.
 */
suspend fun <T> withTenant(tenant: TenantContext, block: suspend () -> T): T {
    return withContext(TenantCoroutineContext(tenant)) {
        TenantContext.set(tenant)
        try {
            block()
        } finally {
            TenantContext.clear()
        }
    }
}
```

## Betroffene Dateien

### Backend

| Datei                                                                         | Aenderung                                      |
|-------------------------------------------------------------------------------|------------------------------------------------|
| `tenant/src/main/kotlin/com/graphmesh/tenant/TenantContext.kt`                | Mandantenkontext mit ThreadLocal               |
| `tenant/src/main/kotlin/com/graphmesh/tenant/TenantResolver.kt`               | Interface + JWT/Header-Implementierungen       |
| `tenant/src/main/kotlin/com/graphmesh/tenant/TenantFilter.kt`                 | Servlet-Filter fuer Request-Interception       |
| `tenant/src/main/kotlin/com/graphmesh/tenant/TenantAwareCollectionService.kt` | Dekorator fuer CollectionService               |
| `tenant/src/main/kotlin/com/graphmesh/tenant/TenantKeyspaceResolver.kt`       | Cassandra-Keyspace-Aufloesung                  |
| `tenant/src/main/kotlin/com/graphmesh/tenant/TenantCoroutineContext.kt`       | Coroutine-Propagation                          |
| `tenant/src/main/kotlin/com/graphmesh/tenant/TenantSecurityConfig.kt`         | Spring Security Konfiguration                  |
| `storage/src/main/kotlin/com/graphmesh/storage/cassandra/CassandraClient.kt`  | Erweiterung um dynamische Keyspace-Aufloesung  |
| `collection/src/main/kotlin/com/graphmesh/collection/Collection.kt`           | Erweiterung um `tenantId` und `ownerId` Felder |
| `librarian/src/main/kotlin/com/graphmesh/librarian/LibrarianService.kt`       | Tenant-Filterung bei Dokument-Zugriff          |

### Frontend

| Datei                     | Aenderung                              |
|---------------------------|----------------------------------------|
| HTTP-Client-Konfiguration | JWT-Token bzw. Tenant-Header mitsenden |

### Tests

| Datei                                                                             | Aenderung                           |
|-----------------------------------------------------------------------------------|-------------------------------------|
| `tenant/src/test/kotlin/com/graphmesh/tenant/TenantFilterTest.kt`                 | Tests fuer Header- und JWT-Resolver |
| `tenant/src/test/kotlin/com/graphmesh/tenant/TenantAwareCollectionServiceTest.kt` | Isolierungstests                    |
| `tenant/src/test/kotlin/com/graphmesh/tenant/TenantKeyspaceResolverTest.kt`       | Keyspace-Aufloesung                 |
| `tenant/src/test/kotlin/com/graphmesh/tenant/MultiTenantIntegrationTest.kt`       | End-to-End Multi-Tenant-Szenario    |

## Platform-Einschraenkungen

| Plattform         | Unterstuetzt | Anmerkung                                     |
|-------------------|--------------|-----------------------------------------------|
| Spring Boot (JVM) | Ja           | Primaere Zielplattform, Spring Security       |
| KMP Library       | Nein         | Abhaengig von Spring Security und Servlet-API |
| Ktor/Wasm         | Nein         | Server-seitige Zugriffskontrolle              |

## Akzeptanzkriterien

- [ ] TenantContext wird korrekt aus JWT-Token extrahiert (tenant_id, sub Claims)
- [ ] TenantContext wird korrekt aus X-Tenant-Id / X-User-Id Headern extrahiert
- [ ] Requests ohne gueltige Mandantenidentifikation erhalten HTTP 401
- [ ] `listCollections()` gibt nur Collections des aktuellen Mandanten zurueck
- [ ] Zugriff auf eine Collection eines anderen Mandanten wird mit AccessDeniedException abgelehnt
- [ ] Neue Collections werden automatisch dem aktuellen Mandanten zugeordnet
- [ ] TenantContext wird korrekt ueber Coroutine-Grenzen propagiert
- [ ] Bei konfigurierter Keyspace-Isolation wird ein separater Cassandra-Keyspace pro Mandant verwendet
- [ ] Mandanten-Keyspaces werden automatisch erstellt (CREATE IF NOT EXISTS)
- [ ] Ohne Keyspace-Konfiguration wird der Default-Keyspace verwendet (Backward Compatibility)
