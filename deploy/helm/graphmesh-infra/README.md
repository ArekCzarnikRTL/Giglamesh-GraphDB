# graphmesh-infra (Umbrella Helm Chart)

Ersetzt das root-`docker-compose.yaml` fuer k3s-Deployments.
Zieht 5 externe Charts als Dependencies (keine eigenen Templates noetig).

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

`values-dev.yaml` setzt Service-Type auf NodePort:

| Service         | NodePort |
|-----------------|----------|
| Kafka           | 30092    |
| Schema Registry | 30181    |
| Cassandra       | 30942    |
| MinIO API       | 30900    |
| MinIO Console   | 30901    |
| Qdrant HTTP     | 30633    |
| Qdrant gRPC     | 30634    |

Da NodePorts nicht mit den docker-compose-Defaults uebereinstimmen, muessen
beim Backend entweder `application.yml` angepasst oder lokale Port-Weiterleitung
eingerichtet werden. Fuer minimalen Configaufwand ist Variante A meist bequemer.

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
