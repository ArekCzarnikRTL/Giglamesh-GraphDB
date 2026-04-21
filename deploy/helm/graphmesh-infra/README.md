# graphmesh-infra (Umbrella Helm Chart)

Ersetzt das root-`docker-compose.yaml` fuer k3s-Deployments.
Zieht 5 externe Charts als Dependencies und stellt ein eigenes Template fuer
die Cassandra-Traefik-IngressRouteTCP bereit.

## Mapping: docker-compose -> helm

| compose-service    | chart               | repo                                        |
|--------------------|---------------------|---------------------------------------------|
| kafka              | kafka               | oci://registry-1.docker.io/bitnamicharts    |
| schema-registry    | schema-registry     | oci://registry-1.docker.io/bitnamicharts    |
| cassandra          | cassandra           | oci://registry-1.docker.io/bitnamicharts    |
| minio              | minio               | oci://registry-1.docker.io/bitnamicharts    |
| qdrant             | qdrant              | https://qdrant.github.io/qdrant-helm        |

## Voraussetzungen

- Helm 3.x
- k3s-Cluster mit aktiver StorageClass `local-path` (k3s Default)
- Kubectl-Kontext zeigt auf den Ziel-Cluster

## Quick Start

```bash
cd deploy/helm/graphmesh-infra

# 1) Dependencies ziehen (Chart.lock + charts/-Ordner)
helm dependency update

# 2) Installation mit dev-Defaults
helm install graphmesh-infra . -f values-dev.yaml

# 3) Status pruefen
kubectl get pods -w
```

Nach dem ersten `helm install` sollten die 5 StatefulSet-/Deployment-Pods
nach ca. 2-5 Minuten `Ready` sein (Bitnami-Images sind ca. 500 MB pro Service).

## Service-DNS (im Cluster)

Release-Name = `graphmesh-infra`, Namespace = `default` (Beispiel):

| Service         | ClusterIP-DNS                                   | Port  |
|-----------------|-------------------------------------------------|-------|
| Kafka           | `graphmesh-infra-kafka`                         | 9092  |
| Schema Registry | `graphmesh-infra-schema-registry`               | 8181  |
| Cassandra       | `graphmesh-infra-cassandra`                     | 9042  |
| MinIO API       | `graphmesh-infra-minio`                         | 9000  |
| MinIO Console   | `graphmesh-infra-minio`                         | 9001  |
| Qdrant HTTP     | `graphmesh-infra-qdrant`                        | 6333  |
| Qdrant gRPC     | `graphmesh-infra-qdrant`                        | 6334  |

## Ports exposen (Backend laeuft ausserhalb des Clusters)

### Variante A: `kubectl port-forward` (keine Service-Typ-Aenderung noetig)

```bash
kubectl port-forward svc/graphmesh-infra-kafka            9092:9092
kubectl port-forward svc/graphmesh-infra-schema-registry  8181:8181
kubectl port-forward svc/graphmesh-infra-cassandra        9042:9042
kubectl port-forward svc/graphmesh-infra-minio            9000:9000 9001:9001
kubectl port-forward svc/graphmesh-infra-qdrant           6333:6333 6334:6334
```

Backend startet dann unveraendert mit den Hosts aus `application.yml`
(`localhost:9092`, `localhost:9042`, usw.).

### Variante B: NodePort (Default in `values-dev.yaml`)

`values-dev.yaml` setzt Service-Type auf NodePort fuer Kafka/Schema-Registry/MinIO/Qdrant
(Cassandra geht via Variante C):

| Service         | NodePort |
|-----------------|----------|
| Kafka           | 30092    |
| Schema Registry | 30181    |
| MinIO API       | 30900    |
| MinIO Console   | 30901    |
| Qdrant HTTP     | 30633    |
| Qdrant gRPC     | 30634    |

Da NodePorts nicht mit den docker-compose-Defaults uebereinstimmen, muessen
beim Backend entweder `application.yml` angepasst oder lokale Port-Weiterleitung
eingerichtet werden. Fuer minimalen Configaufwand ist Variante A meist bequemer.

## Variante C: Traefik TCP Ingress (IngressRouteTCP)

Cassandra (CQL) ist kein HTTP-Service — es braucht TCP-Routing via Traefik-CRD
`IngressRouteTCP` statt Standard-Ingress.

`values-dev.yaml` aktiviert IngressRouteTCP fuer Cassandra. Damit Traefik den
Traffic annimmt, muss ein zusaetzlicher **entryPoint** `cassandra:9042` in der
Traefik-Config registriert werden.

**Kafka geht bewusst nicht diesen Weg.** Bitnami's `externalAccess` legt einen
NodePort an und setzt `advertised.listeners` auf `<node-ip>:<nodePort>` —
genau das brauchen Remote-Clients. Eine Traefik-IngressRouteTCP wuerde zwar
TCP-Pakete weiterleiten, aber der Broker antwortet im Metadata-Handshake mit
der advertised-Adresse. Ohne aufwendiges Override (Routing auf den EXTERNAL-
Listener-Service pro Replica + `advertised.listeners`-Override) bleibt Kafka
fuer externe Clients an NodePort gebunden.

### Traefik TCP EntryPoints konfigurieren (k3s)

k3s deployt Traefik via `HelmChart`. Zusatz-EntryPoints werden ueber eine
`HelmChartConfig`-Resource gemerged. Datei liegt im Repo:

```bash
kubectl apply -f ../../k3s/traefik-config.yaml
```

Nach dem Apply reconciled der k3s helm-controller den Traefik-HelmChart
automatisch (30-60s). Verifizieren:

```bash
# Pods rollen durch
kubectl -n kube-system get pods -l app.kubernetes.io/name=traefik -w

# Service muss 9042 + 9092 zeigen
kubectl -n kube-system get svc traefik

# Logs sollten KEINE "EntryPoint doesn't exist"-Fehler mehr zeigen
kubectl -n kube-system logs -l app.kubernetes.io/name=traefik --tail=50
```

Danach ist Cassandra ueber `<node-ip>:9042` (CQL) erreichbar.

**Symptom wenn EntryPoint fehlt:** in den Traefik-Logs erscheint
`ERR EntryPoint doesn't exist entryPointName=cassandra`. Die
IngressRouteTCP-Resource ist dann zwar im Cluster, aber kein Listener
nimmt den Traffic an.

### Ingress-Status

| Service         | Typ              | Hostname / Route               | Aktiviert in      |
|-----------------|------------------|--------------------------------|-------------------|
| Schema Registry | Ingress (HTTP)   | schema-registry.k3s.home       | values-dev.yaml   |
| MinIO API       | Ingress (HTTP)   | minio.k3s.home                 | values-dev.yaml   |
| MinIO Console   | Ingress (HTTP)   | minio-console.k3s.home         | values-dev.yaml   |
| Qdrant HTTP     | Ingress (HTTP)   | qdrant.k3s.home                | values-dev.yaml   |
| Cassandra CQL   | IngressRouteTCP  | HostSNI(`*`) :9042             | values-dev.yaml   |
| Kafka Broker    | NodePort         | <node-ip>:30092 (advertised)   | values-dev.yaml   |

**Hinweis Cassandra:** CQL ist ein binaeres TCP-Protokoll ohne HTTP-Host-Header,
deshalb laeuft das Routing pro Traefik-EntryPoint (Port-basiert, `HostSNI(*)`).
Der DNS-Name `cassandra.k3s.home` ist nicht noetig — Clients verbinden sich
direkt gegen die Node-IP auf 9042. Hostname-basiertes Routing ginge nur mit
TLS+SNI (`HostSNI('cassandra.k3s.home')`), wird hier aber nicht genutzt.

## Einzelne Services deaktivieren

Alle Dependencies haben `condition: <name>.enabled`, d.h.:

```bash
helm install graphmesh-infra . -f values-dev.yaml \
  --set kafka.enabled=false \
  --set schema-registry.enabled=false
```

## Uninstall

```bash
helm uninstall graphmesh-infra

# PVCs werden NICHT automatisch geloescht (Standardverhalten bei Stateful-Charts).
# Fuer einen sauberen Reset:
kubectl delete pvc -l app.kubernetes.io/instance=graphmesh-infra
```

## Production

Siehe `values-prod.yaml.example`. Empfehlung: fuer echten Production-Betrieb
auf dedizierte Operatoren umsteigen (Strimzi / K8ssandra / MinIO-Operator).

## Known Issues

- **Erster `helm install` kann langsam sein.** Bitnami-Images werden erstmalig
  gepullt (zusammen ca. 2-3 GB). Auf langsamen Leitungen ggf. `--timeout 10m`
  mitgeben.
- **Kafka KRaft + PVC-Reuse.** Nach `helm uninstall` ohne PVC-Cleanup kann ein
  Re-Install mit abweichender Cluster-ID fehlschlagen (
  `kafka-storage: cluster id mismatch`). Loesung: PVC der Kafka-Controller
  loeschen (`kubectl delete pvc -l app.kubernetes.io/name=kafka`).
- **Qdrant-Auth.** Default = kein API-Key (wie in docker-compose). Fuer
  prod den Chart-Wert `qdrant.apiKey` (oder `existingSecret`) setzen und
  im Backend via `spring.ai.vectorstore.qdrant.api-key` spiegeln.
- **Schema-Registry-Bootstrap.** Die Dependency ist auf den DNS-Namen
  `graphmesh-infra-kafka` gepinnt. Wird ein abweichender Release-Name genutzt,
  muss `schema-registry.externalKafka.brokers` in den Values entsprechend
  angepasst werden.
