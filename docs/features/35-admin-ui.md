# Feature 35: Admin UI

## Problem

GraphMesh bietet ueber verschiedene Backend-Services (Configuration Service, Collection Management, Kafka, Cassandra)
umfangreiche Verwaltungsfunktionen, jedoch fehlt eine zentrale Administrationsoberflaeche. Administratoren muessen
aktuell ueber GraphiQL, Konfigurationsdateien oder direkte Service-Aufrufe arbeiten. Ohne ein Admin-Dashboard gibt es
keine einfache Moeglichkeit, den Systemzustand zu ueberblicken, Collections zu verwalten, Konfigurationen zu aendern
oder den Fortschritt der Extraktionspipeline zu ueberwachen.

## Ziel

Implementierung eines Admin-Dashboards in Next.js, das Collection-Management, Konfigurationsbearbeitung,
Systemgesundheit und Pipeline-Status in einer einheitlichen Oberflaeche zusammenfasst.

1. **Admin Dashboard** -- Uebersichtsseite mit Key-Metriken und Systemstatus
2. **Collection Manager** -- Erstellen, Bearbeiten, Loeschen von Collections mit Tag-Verwaltung
3. **Configuration Editor** -- Anzeige und Bearbeitung von Konfigurationselementen, Ontologien und Flows
4. **System Health** -- Service-Status, Kafka-Consumer-Lag, Cassandra-Konnektivitaet
5. **Pipeline Status** -- Extraktionsfortschritt, Erfolgs- und Fehlerraten

## Voraussetzungen

| Abhaengigkeit                                                  | Status     | Blocker? |
|----------------------------------------------------------------|------------|----------|
| Feature 06: Configuration Service (ConfigService, ConfigStore) | Geplant    | Ja       |
| Feature 08: Collection Management (CollectionService)          | Geplant    | Ja       |
| Feature 14: GraphQL API (Schema, Queries, Mutations)           | Geplant    | Ja       |
| Next.js 14+ (App Router)                                       | Verfuegbar | Nein     |
| Apollo Client                                                  | Verfuegbar | Nein     |

## Architektur

### GraphQL Queries und Mutations

```graphql
# Dashboard-Uebersicht
query AdminDashboard {
    collections(tags: []) {
        id
        name
        documentCount
    }
    systemHealth {
        services {
            name
            status
            latency
            lastCheck
        }
        kafkaLag {
            topic
            consumerGroup
            lag
        }
        cassandraStatus {
            connected
            nodes
            datacenter
        }
    }
    pipelineStatus {
        documentsInProgress
        documentsCompleted
        documentsFailed
        successRate
        averageProcessingTime
    }
}

# Collections verwalten
query AdminCollections {
    collections(tags: []) {
        id
        name
        description
        tags
        documentCount
        metadata { key value }
        createdAt
        updatedAt
    }
}

mutation CreateCollection($input: CreateCollectionInput!) {
    createCollection(input: $input) {
        id
        name
        tags
    }
}

mutation UpdateCollection($id: ID!, $input: UpdateCollectionInput!) {
    updateCollection(id: $id, input: $input) {
        id
        name
        description
        tags
    }
}

mutation DeleteCollection($id: ID!) {
    deleteCollection(id: $id)
}

# Konfiguration verwalten
query ConfigItems($type: String) {
    configItems(type: $type) {
        id
        type
        key
        value
        version
        updatedAt
    }
}

mutation UpdateConfigItem($id: ID!, $value: String!) {
    updateConfigItem(id: $id, value: $value) {
        id
        version
        updatedAt
    }
}

# Pipeline-Details
query PipelineDetails($collectionId: ID) {
    pipelineDocuments(collectionId: $collectionId, states: [PROCESSING, FAILED]) {
        id
        title
        state
        collectionId
        createdAt
        metadata { key value }
    }
}
```

### TypeScript-Typen

```typescript
// frontend/src/types/admin.ts

export interface ServiceHealth {
    name: string;
    status: ServiceStatus;
    latency: number;
    lastCheck: string;
}

export enum ServiceStatus {
    HEALTHY = "HEALTHY",
    DEGRADED = "DEGRADED",
    DOWN = "DOWN",
}

export interface KafkaLagInfo {
    topic: string;
    consumerGroup: string;
    lag: number;
}

export interface CassandraStatus {
    connected: boolean;
    nodes: number;
    datacenter: string;
}

export interface PipelineStatus {
    documentsInProgress: number;
    documentsCompleted: number;
    documentsFailed: number;
    successRate: number;
    averageProcessingTime: number;
}

export interface ConfigItem {
    id: string;
    type: ConfigType;
    key: string;
    value: string;
    version: number;
    updatedAt: string;
}

export type ConfigType = "ontology" | "flow" | "parameter" | "tool";
```

### AdminDashboard-Komponente

```typescript
// frontend/src/components/admin/AdminDashboard.tsx
"use client";

import { useQuery } from "@apollo/client";
import { ADMIN_DASHBOARD_QUERY } from "@/graphql/queries/admin";
import { ServiceHealth, PipelineStatus as PipelineStatusType } from "@/types/admin";

export function AdminDashboard() {
    const { data, loading, error } = useQuery(ADMIN_DASHBOARD_QUERY, {
        pollInterval: 30000, // Alle 30 Sekunden aktualisieren
    });

    if (loading) return <DashboardSkeleton />;
    if (error) return <ErrorMessage message={error.message} />;

    const { collections, systemHealth, pipelineStatus } = data;

    return (
        <div className="space-y-8">
            {/* Key-Metriken */}
            <div className="grid grid-cols-4 gap-4">
                <MetricCard
                    label="Collections"
                    value={collections.length}
                    icon="database"
                />
                <MetricCard
                    label="Dokumente gesamt"
                    value={collections.reduce((sum: number, c: any) => sum + c.documentCount, 0)}
                    icon="file"
                />
                <MetricCard
                    label="In Verarbeitung"
                    value={pipelineStatus.documentsInProgress}
                    icon="loader"
                />
                <MetricCard
                    label="Erfolgsrate"
                    value={`${(pipelineStatus.successRate * 100).toFixed(1)}%`}
                    icon="check"
                />
            </div>

            {/* Service-Status */}
            <section>
                <h2 className="text-lg font-semibold mb-3">Systemgesundheit</h2>
                <div className="grid grid-cols-3 gap-4">
                    {systemHealth.services.map((service: ServiceHealth) => (
                        <ServiceStatusCard key={service.name} service={service} />
                    ))}
                </div>
            </section>

            {/* Kafka Lag */}
            <section>
                <h2 className="text-lg font-semibold mb-3">Kafka Consumer Lag</h2>
                <KafkaLagTable entries={systemHealth.kafkaLag} />
            </section>

            {/* Pipeline-Status */}
            <section>
                <h2 className="text-lg font-semibold mb-3">Extraktionspipeline</h2>
                <PipelineStatusPanel status={pipelineStatus} />
            </section>
        </div>
    );
}
```

### CollectionManager-Komponente

```typescript
// frontend/src/components/admin/CollectionManager.tsx
"use client";

import { useState } from "react";
import { useQuery, useMutation } from "@apollo/client";
import { ADMIN_COLLECTIONS_QUERY } from "@/graphql/queries/admin";
import {
    CREATE_COLLECTION,
    UPDATE_COLLECTION,
    DELETE_COLLECTION,
} from "@/graphql/mutations/admin";

interface CollectionFormData {
    name: string;
    description: string;
    tags: string[];
}

export function CollectionManager() {
    const { data, loading, refetch } = useQuery(ADMIN_COLLECTIONS_QUERY);
    const [createCollection] = useMutation(CREATE_COLLECTION);
    const [updateCollection] = useMutation(UPDATE_COLLECTION);
    const [deleteCollection] = useMutation(DELETE_COLLECTION);
    const [editingId, setEditingId] = useState<string | null>(null);
    const [showCreateForm, setShowCreateForm] = useState(false);

    const handleCreate = async (formData: CollectionFormData) => {
        await createCollection({
            variables: {
                input: {
                    name: formData.name,
                    description: formData.description,
                    tags: formData.tags,
                },
            },
        });
        setShowCreateForm(false);
        refetch();
    };

    const handleUpdate = async (id: string, formData: CollectionFormData) => {
        await updateCollection({
            variables: {
                id,
                input: {
                    name: formData.name,
                    description: formData.description,
                    tags: formData.tags,
                },
            },
        });
        setEditingId(null);
        refetch();
    };

    const handleDelete = async (id: string) => {
        if (!confirm("Collection wirklich loeschen? Alle Dokumente werden ebenfalls geloescht.")) return;
        await deleteCollection({ variables: { id } });
        refetch();
    };

    if (loading) return <TableSkeleton />;

    return (
        <div className="space-y-4">
            <div className="flex justify-between items-center">
                <h2 className="text-lg font-semibold">Collections</h2>
                <button
                    onClick={() => setShowCreateForm(true)}
                    className="px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700"
                >
                    Neue Collection
                </button>
            </div>

            {showCreateForm && (
                <CollectionForm
                    onSubmit={handleCreate}
                    onCancel={() => setShowCreateForm(false)}
                />
            )}

            <table className="w-full border-collapse">
                <thead>
                    <tr className="border-b text-left text-sm text-gray-500">
                        <th className="py-2">Name</th>
                        <th>Beschreibung</th>
                        <th>Tags</th>
                        <th>Dokumente</th>
                        <th>Erstellt</th>
                        <th>Aktionen</th>
                    </tr>
                </thead>
                <tbody>
                    {data.collections.map((col: any) => (
                        <CollectionRow
                            key={col.id}
                            collection={col}
                            isEditing={editingId === col.id}
                            onEdit={() => setEditingId(col.id)}
                            onUpdate={(formData) => handleUpdate(col.id, formData)}
                            onDelete={() => handleDelete(col.id)}
                            onCancel={() => setEditingId(null)}
                        />
                    ))}
                </tbody>
            </table>
        </div>
    );
}
```

### ConfigEditor-Komponente

```typescript
// frontend/src/components/admin/ConfigEditor.tsx
"use client";

import { useState } from "react";
import { useQuery, useMutation } from "@apollo/client";
import { CONFIG_ITEMS_QUERY } from "@/graphql/queries/admin";
import { UPDATE_CONFIG_ITEM } from "@/graphql/mutations/admin";
import { ConfigItem, ConfigType } from "@/types/admin";

export function ConfigEditor() {
    const [selectedType, setSelectedType] = useState<ConfigType | null>(null);
    const [editingItem, setEditingItem] = useState<ConfigItem | null>(null);

    const { data, loading, refetch } = useQuery(CONFIG_ITEMS_QUERY, {
        variables: { type: selectedType },
    });

    const [updateConfig] = useMutation(UPDATE_CONFIG_ITEM);

    const handleSave = async (id: string, value: string) => {
        await updateConfig({ variables: { id, value } });
        setEditingItem(null);
        refetch();
    };

    const configTypes: { value: ConfigType; label: string }[] = [
        { value: "ontology", label: "Ontologien" },
        { value: "flow", label: "Flows" },
        { value: "parameter", label: "Parameter" },
        { value: "tool", label: "Tools" },
    ];

    return (
        <div className="space-y-4">
            <div className="flex gap-2">
                {configTypes.map((type) => (
                    <button
                        key={type.value}
                        onClick={() => setSelectedType(type.value)}
                        className={`px-3 py-1.5 rounded text-sm ${
                            selectedType === type.value
                                ? "bg-blue-600 text-white"
                                : "bg-gray-100 text-gray-700 hover:bg-gray-200"
                        }`}
                    >
                        {type.label}
                    </button>
                ))}
            </div>

            {loading ? (
                <TableSkeleton />
            ) : (
                <div className="space-y-3">
                    {data?.configItems?.map((item: ConfigItem) => (
                        <div key={item.id} className="border rounded-lg p-4">
                            <div className="flex justify-between items-start mb-2">
                                <div>
                                    <h4 className="font-medium">{item.key}</h4>
                                    <span className="text-xs text-gray-500">
                                        Version {item.version} | {item.updatedAt}
                                    </span>
                                </div>
                                <button
                                    onClick={() => setEditingItem(item)}
                                    className="text-sm text-blue-600 hover:underline"
                                >
                                    Bearbeiten
                                </button>
                            </div>
                            {editingItem?.id === item.id ? (
                                <ConfigValueEditor
                                    value={item.value}
                                    onSave={(value) => handleSave(item.id, value)}
                                    onCancel={() => setEditingItem(null)}
                                />
                            ) : (
                                <pre className="text-sm bg-gray-50 p-2 rounded overflow-x-auto">
                                    {item.value}
                                </pre>
                            )}
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
```

### SystemHealth-Komponente

```typescript
// frontend/src/components/admin/SystemHealth.tsx
"use client";

import { useQuery } from "@apollo/client";
import { SYSTEM_HEALTH_QUERY } from "@/graphql/queries/admin";
import { ServiceHealth, ServiceStatus, KafkaLagInfo, CassandraStatus } from "@/types/admin";

export function SystemHealth() {
    const { data, loading } = useQuery(SYSTEM_HEALTH_QUERY, {
        pollInterval: 15000, // Alle 15 Sekunden aktualisieren
    });

    if (loading) return <HealthSkeleton />;

    const { services, kafkaLag, cassandraStatus } = data.systemHealth;

    return (
        <div className="space-y-6">
            {/* Service-Uebersicht */}
            <section>
                <h2 className="text-lg font-semibold mb-3">Services</h2>
                <div className="grid grid-cols-2 gap-4">
                    {services.map((service: ServiceHealth) => (
                        <div key={service.name} className="border rounded-lg p-4">
                            <div className="flex justify-between items-center">
                                <span className="font-medium">{service.name}</span>
                                <StatusBadge status={service.status} />
                            </div>
                            <div className="text-sm text-gray-500 mt-1">
                                Latenz: {service.latency}ms | Letzte Pruefung: {service.lastCheck}
                            </div>
                        </div>
                    ))}
                </div>
            </section>

            {/* Cassandra-Status */}
            <section>
                <h2 className="text-lg font-semibold mb-3">Cassandra</h2>
                <div className="border rounded-lg p-4">
                    <div className="flex items-center gap-2">
                        <StatusBadge status={cassandraStatus.connected ? ServiceStatus.HEALTHY : ServiceStatus.DOWN} />
                        <span>{cassandraStatus.nodes} Knoten | Datacenter: {cassandraStatus.datacenter}</span>
                    </div>
                </div>
            </section>

            {/* Kafka Consumer Lag */}
            <section>
                <h2 className="text-lg font-semibold mb-3">Kafka Consumer Lag</h2>
                <table className="w-full border-collapse">
                    <thead>
                        <tr className="border-b text-left text-sm text-gray-500">
                            <th className="py-2">Topic</th>
                            <th>Consumer Group</th>
                            <th>Lag</th>
                        </tr>
                    </thead>
                    <tbody>
                        {kafkaLag.map((entry: KafkaLagInfo, i: number) => (
                            <tr key={i} className="border-b">
                                <td className="py-2 font-mono text-sm">{entry.topic}</td>
                                <td className="font-mono text-sm">{entry.consumerGroup}</td>
                                <td>
                                    <span className={entry.lag > 1000 ? "text-red-600 font-bold" : ""}>
                                        {entry.lag.toLocaleString()}
                                    </span>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </section>
        </div>
    );
}

function StatusBadge({ status }: { status: ServiceStatus }) {
    const colors: Record<ServiceStatus, string> = {
        [ServiceStatus.HEALTHY]: "bg-green-100 text-green-800",
        [ServiceStatus.DEGRADED]: "bg-yellow-100 text-yellow-800",
        [ServiceStatus.DOWN]: "bg-red-100 text-red-800",
    };

    return (
        <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${colors[status]}`}>
            {status}
        </span>
    );
}
```

### PipelineStatus-Komponente

```typescript
// frontend/src/components/admin/PipelineStatus.tsx
"use client";

import { useQuery } from "@apollo/client";
import { PIPELINE_DETAILS_QUERY } from "@/graphql/queries/admin";
import Link from "next/link";

interface PipelineStatusProps {
    collectionId?: string;
}

export function PipelineStatus({ collectionId }: PipelineStatusProps) {
    const { data, loading } = useQuery(PIPELINE_DETAILS_QUERY, {
        variables: { collectionId },
        pollInterval: 10000, // Alle 10 Sekunden aktualisieren
    });

    if (loading) return <TableSkeleton />;

    const documents = data?.pipelineDocuments ?? [];

    return (
        <div className="space-y-4">
            <h2 className="text-lg font-semibold">Pipeline-Dokumente</h2>
            <table className="w-full border-collapse">
                <thead>
                    <tr className="border-b text-left text-sm text-gray-500">
                        <th className="py-2">Titel</th>
                        <th>Status</th>
                        <th>Collection</th>
                        <th>Erstellt</th>
                    </tr>
                </thead>
                <tbody>
                    {documents.map((doc: any) => (
                        <tr key={doc.id} className="border-b">
                            <td className="py-2">
                                <Link href={`/documents/${doc.id}`} className="text-blue-600 hover:underline">
                                    {doc.title}
                                </Link>
                            </td>
                            <td>
                                <DocumentStateBadge state={doc.state} />
                            </td>
                            <td className="text-sm text-gray-600">{doc.collectionId}</td>
                            <td className="text-sm text-gray-500">{doc.createdAt}</td>
                        </tr>
                    ))}
                </tbody>
            </table>
            {documents.length === 0 && (
                <p className="text-center text-gray-500 py-4">Keine Dokumente in Verarbeitung</p>
            )}
        </div>
    );
}
```

### Seitenstruktur

```typescript
// frontend/src/app/admin/page.tsx
import { AdminDashboard } from "@/components/admin/AdminDashboard";

export default function AdminPage() {
    return (
        <main className="container mx-auto p-6">
            <h1 className="text-2xl font-bold mb-6">Administration</h1>
            <AdminDashboard />
        </main>
    );
}
```

```typescript
// frontend/src/app/admin/collections/page.tsx
import { CollectionManager } from "@/components/admin/CollectionManager";

export default function AdminCollectionsPage() {
    return (
        <main className="container mx-auto p-6">
            <h1 className="text-2xl font-bold mb-6">Collection-Verwaltung</h1>
            <CollectionManager />
        </main>
    );
}
```

```typescript
// frontend/src/app/admin/config/page.tsx
import { ConfigEditor } from "@/components/admin/ConfigEditor";

export default function AdminConfigPage() {
    return (
        <main className="container mx-auto p-6">
            <h1 className="text-2xl font-bold mb-6">Konfiguration</h1>
            <ConfigEditor />
        </main>
    );
}
```

```typescript
// frontend/src/app/admin/health/page.tsx
import { SystemHealth } from "@/components/admin/SystemHealth";

export default function AdminHealthPage() {
    return (
        <main className="container mx-auto p-6">
            <h1 className="text-2xl font-bold mb-6">Systemgesundheit</h1>
            <SystemHealth />
        </main>
    );
}
```

```typescript
// frontend/src/app/admin/layout.tsx
import Link from "next/link";

const adminNav = [
    { href: "/admin", label: "Dashboard" },
    { href: "/admin/collections", label: "Collections" },
    { href: "/admin/config", label: "Konfiguration" },
    { href: "/admin/health", label: "Systemgesundheit" },
];

export default function AdminLayout({ children }: { children: React.ReactNode }) {
    return (
        <div className="flex">
            <nav className="w-56 border-r min-h-screen p-4 space-y-1">
                {adminNav.map((item) => (
                    <Link
                        key={item.href}
                        href={item.href}
                        className="block px-3 py-2 rounded text-sm hover:bg-gray-100"
                    >
                        {item.label}
                    </Link>
                ))}
            </nav>
            <div className="flex-1">{children}</div>
        </div>
    );
}
```

## Betroffene Dateien

### Backend

Nicht betroffen (die Admin-UI nutzt ausschliesslich die bestehende GraphQL-API aus Feature 14 sowie die
Konfigurations-Endpoints aus Feature 06).

### Frontend

| Datei                                                  | Aenderung                                             |
|--------------------------------------------------------|-------------------------------------------------------|
| `frontend/src/app/admin/page.tsx`                      | NEU - Admin-Dashboard-Seite                           |
| `frontend/src/app/admin/layout.tsx`                    | NEU - Admin-Layout mit Sidebar-Navigation             |
| `frontend/src/app/admin/collections/page.tsx`          | NEU - Collection-Verwaltungsseite                     |
| `frontend/src/app/admin/config/page.tsx`               | NEU - Konfigurationseditor-Seite                      |
| `frontend/src/app/admin/health/page.tsx`               | NEU - Systemgesundheits-Seite                         |
| `frontend/src/components/admin/AdminDashboard.tsx`     | NEU - Dashboard mit Key-Metriken und Status           |
| `frontend/src/components/admin/CollectionManager.tsx`  | NEU - CRUD fuer Collections                           |
| `frontend/src/components/admin/CollectionForm.tsx`     | NEU - Formular fuer Collection-Erstellung/Bearbeitung |
| `frontend/src/components/admin/CollectionRow.tsx`      | NEU - Tabellenzeile mit Inline-Editing                |
| `frontend/src/components/admin/ConfigEditor.tsx`       | NEU - Konfigurationselement-Editor                    |
| `frontend/src/components/admin/ConfigValueEditor.tsx`  | NEU - Werteditor mit Syntax-Highlighting              |
| `frontend/src/components/admin/SystemHealth.tsx`       | NEU - Service-Status und Infrastruktur-Uebersicht     |
| `frontend/src/components/admin/PipelineStatus.tsx`     | NEU - Extraktionspipeline-Uebersicht                  |
| `frontend/src/components/admin/MetricCard.tsx`         | NEU - Wiederverwendbare Metrik-Karte                  |
| `frontend/src/components/admin/StatusBadge.tsx`        | NEU - Status-Badge (HEALTHY/DEGRADED/DOWN)            |
| `frontend/src/components/admin/DocumentStateBadge.tsx` | NEU - Dokument-Status-Badge                           |
| `frontend/src/components/admin/KafkaLagTable.tsx`      | NEU - Kafka-Lag-Tabelle                               |
| `frontend/src/graphql/queries/admin.ts`                | NEU - GraphQL-Queries fuer Admin-Bereich              |
| `frontend/src/graphql/mutations/admin.ts`              | NEU - GraphQL-Mutations fuer Collection/Config        |
| `frontend/src/types/admin.ts`                          | NEU - TypeScript-Typen fuer Admin-Bereich             |

### Tests

| Datei                                                                | Aenderung                             |
|----------------------------------------------------------------------|---------------------------------------|
| `frontend/src/__tests__/components/admin/AdminDashboard.test.tsx`    | NEU - Dashboard-Rendering und Polling |
| `frontend/src/__tests__/components/admin/CollectionManager.test.tsx` | NEU - CRUD-Operationen                |
| `frontend/src/__tests__/components/admin/ConfigEditor.test.tsx`      | NEU - Konfigurationsbearbeitung       |
| `frontend/src/__tests__/components/admin/SystemHealth.test.tsx`      | NEU - Status-Anzeige und Auto-Refresh |
| `frontend/src/__tests__/components/admin/PipelineStatus.test.tsx`    | NEU - Pipeline-Darstellung            |
| `frontend/src/__tests__/pages/admin.test.tsx`                        | NEU - Admin-Seitenintegrationstests   |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                                           |
|-------------------|-------------|-----------------------------------------------------------------|
| Spring Boot (JVM) | Ja          | Frontend kommuniziert ueber GraphQL-API mit Spring Boot Backend |
| KMP Library       | Nein        | Reines Frontend-Feature, kein Backend-Code betroffen            |
| Ktor/Wasm         | Nein        | Reines Frontend-Feature, kein Backend-Code betroffen            |

## Akzeptanzkriterien

- [ ] Seite `/admin` zeigt ein Dashboard mit Key-Metriken (Collections, Dokumente, Erfolgsrate)
- [ ] Dashboard aktualisiert sich automatisch alle 30 Sekunden
- [ ] Seite `/admin/collections` listet alle Collections mit Name, Tags und Dokumentanzahl
- [ ] Neue Collection kann ueber Formular erstellt werden (Name, Beschreibung, Tags)
- [ ] Bestehende Collection kann inline bearbeitet werden (Name, Beschreibung, Tags)
- [ ] Collection kann geloescht werden mit Bestaetigung (confirm-Dialog)
- [ ] Seite `/admin/config` zeigt Konfigurationselemente gruppiert nach Typ (Ontologie, Flow, Parameter, Tool)
- [ ] Konfigurationswerte koennen bearbeitet und gespeichert werden
- [ ] Versionsnummer wird nach Speicherung inkrementiert angezeigt
- [ ] Seite `/admin/health` zeigt Service-Status mit farbigen Badges (gruen/gelb/rot)
- [ ] Kafka-Consumer-Lag wird tabellarisch angezeigt, hohe Werte (>1000) werden rot hervorgehoben
- [ ] Cassandra-Konnektivitaet und Knotenanzahl werden angezeigt
- [ ] Pipeline-Status zeigt Dokumente in Verarbeitung und fehlgeschlagene Dokumente
- [ ] Admin-Navigation ermoeglicht Wechsel zwischen allen Admin-Unterseiten
- [ ] Bestehende Funktionalitaet bleibt unberuehrt
