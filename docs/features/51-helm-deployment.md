# Feature 51: Helm Deployment Setup

## Problem

Die GraphMesh-Infrastruktur (Kafka, Schema Registry, Cassandra, MinIO, Qdrant)
wird lokal ueber `docker-compose.yaml` bereitgestellt. Fuer Deployments auf
einen k3s-Cluster (Dev/Staging) fehlt eine wiederholbare, Kubernetes-native
Variante. Manuelles Uebersetzen einzelner Manifeste skaliert nicht und
driftet gegen die Compose-Datei.

## Ziel

Ein Umbrella-Helm-Chart, das die 5 Compose-Services auf k3s deployt,
konsistent mit den Defaults aus der Backend-`application.yml`.

1. **Umbrella-Chart `graphmesh-infra`** — 5 Dependencies (4x Bitnami, 1x offizielles Qdrant-Chart), keine eigenen Templates.
2. **Dev-Values fuer k3s** — kleine Resources, `local-path` StorageClass, NodePort-Exposure fuer den ausserhalb laufenden Backend-Prozess.
3. **Dokumentation** — Quick Start, Port-Forward-Rezepte, Known-Issues, Production-Hinweis (Operator-Pfad).

## Voraussetzungen

| Abhaengigkeit                               | Status      | Blocker? |
|---------------------------------------------|-------------|----------|
| Helm 3.x                                    | Verfuegbar  | Nein     |
| k3s-Cluster mit StorageClass `local-path`   | Verfuegbar  | Nein     |
| Backend-`application.yml` (Ports/Env)       | Implementiert | Nein   |
| docker-compose.yaml als Referenz            | Implementiert | Nein   |

## Architektur

### Umbrella-Chart-Struktur

```
deploy/helm/graphmesh-infra/
  Chart.yaml                 # v2, dependencies-Block (5 Eintraege)
  values.yaml                # sensible Defaults (ClusterIP, local-path, kleine Heaps)
  values-dev.yaml            # NodePort + kleinere Resources fuer k3s Single-Node
  values-prod.yaml.example   # Kommentierte Vorlage inkl. Operator-Hinweis
  README.md                  # Quick Start, DNS-Tabelle, Known Issues
  charts/                    # (generiert von `helm dependency update`)
  Chart.lock                 # (generiert)
```

Keine `templates/` — das Chart ist rein deklarativ ueber Dependencies. Ein
eigener Secret-/ConfigMap-Layer waere YAGNI (alle Dependencies haben Chart-
native Auth-Werte).

### Dependency-Tabelle

| Dependency        | Version   | Repository                                    | Bemerkung                                  |
|-------------------|-----------|-----------------------------------------------|--------------------------------------------|
| `kafka`           | 30.1.8    | `oci://registry-1.docker.io/bitnamicharts`    | KRaft, 1 kombinierter controller+broker    |
| `schema-registry` | 22.0.2    | `oci://registry-1.docker.io/bitnamicharts`    | Port 8181, externalKafka auf in-chart Kafka |
| `cassandra`       | 12.1.4    | `oci://registry-1.docker.io/bitnamicharts`    | Cluster `graphmesh`, DC `datacenter1`, 512M |
| `minio`           | 14.8.5    | `oci://registry-1.docker.io/bitnamicharts`    | standalone, minioadmin/minioadmin          |
| `qdrant`          | 1.12.0    | `https://qdrant.github.io/qdrant-helm`        | Bitnami publiziert kein Qdrant             |

### Entscheidung: Bitnami statt Operator

`CLAUDE.md` und `.claude/rules/backend-coding.md`: _"Simplicity first, YAGNI."_
Ein Umbrella-Chart ist fuer ein Dev-Deployment der direkteste Weg:

- Keine CRDs, keine Operator-Controller-Lifecycles.
- Ein `helm install` reicht.
- Production-Pfad (Strimzi / K8ssandra / MinIO-Operator) ist explizit in
  `values-prod.yaml.example` dokumentiert und kann spaeter angegangen werden.

### Kompatibilitaet zur Backend-Config

Die Service-DNS-Namen lauten `graphmesh-infra-<dep>` (Release-Name +
Dependency-Alias). Das Backend laeuft in dev ausserhalb des Clusters; es
erreicht die Services entweder per `kubectl port-forward` (Ports = Compose-
Defaults, `application.yml` unveraendert) oder ueber die NodePorts aus
`values-dev.yaml` (dann Anpassung in `application.yml` oder lokales
Port-Mapping noetig). README dokumentiert beide Varianten.

## Betroffene Dateien

### Infra

| Datei                                                      | Aenderung |
|------------------------------------------------------------|-----------|
| `deploy/helm/graphmesh-infra/Chart.yaml`                   | NEU       |
| `deploy/helm/graphmesh-infra/values.yaml`                  | NEU       |
| `deploy/helm/graphmesh-infra/values-dev.yaml`              | NEU       |
| `deploy/helm/graphmesh-infra/values-prod.yaml.example`     | NEU       |
| `deploy/helm/graphmesh-infra/README.md`                    | NEU       |
| `deploy/helm/README.md`                                    | NEU       |

### Docs

| Datei                                    | Aenderung                                   |
|------------------------------------------|---------------------------------------------|
| `docs/features/51-helm-deployment.md`    | NEU (diese Spec)                            |
| `docs/features/00-feature-set-overview.md` | Phase 10 + DAG-Zeile ergaenzt             |

### Backend / Frontend / Tests

Keine Aenderungen. Das Chart ist infrastrukturreine Ergaenzung.

## Akzeptanzkriterien

- [ ] `helm dependency update` im Chart-Verzeichnis laeuft ohne Fehler durch (5 Charts in `charts/` landen).
- [ ] `helm install graphmesh-infra . -f values-dev.yaml` auf einem frischen k3s-Cluster bringt alle 5 Pods in den `Ready`-Zustand.
- [ ] `kubectl port-forward svc/graphmesh-infra-kafka 9092:9092` + analog fuer die anderen 4 Services ermoeglichen Backend-Start (`./gradlew bootRun`) ohne Config-Aenderung.
- [ ] Ein einzelner Service kann per `--set <name>.enabled=false` deaktiviert werden.
- [ ] README erklaert PVC-Cleanup fuer Kafka-KRaft-Reinstall.
- [ ] Bestehende Funktionalitaet (docker-compose, Backend-Code) bleibt unberuehrt.
