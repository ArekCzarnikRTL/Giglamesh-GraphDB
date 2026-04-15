# deploy/helm

Helm-Charts fuer das GraphMesh-Projekt.

## Inhalt

- [`graphmesh-infra/`](./graphmesh-infra/) — Umbrella-Chart, das die Infrastruktur
  aus dem root-`docker-compose.yaml` als k3s-Deployment abbildet (Kafka,
  Schema Registry, Cassandra, MinIO, Qdrant).

## Mapping: docker-compose -> Helm-Dependency

| docker-compose-Service | Helm-Dependency   | Quelle                                      |
|------------------------|-------------------|---------------------------------------------|
| kafka                  | `kafka`           | Bitnami (OCI)                               |
| schema-registry        | `schema-registry` | Bitnami (OCI)                               |
| cassandra              | `cassandra`       | Bitnami (OCI)                               |
| minio                  | `minio`           | Bitnami (OCI)                               |
| qdrant                 | `qdrant`          | qdrant.github.io/qdrant-helm (offiziell)    |

## Rationale: Bitnami statt Operatoren

CLAUDE.md / `.claude/rules/backend-coding.md` geben vor: _"Simplicity first:
keine Abstraktions-Layer ohne Not, YAGNI"._ Fuer ein Dev-/Staging-Deployment
auf k3s (Single-Node) sind Operator-Stacks (Strimzi, K8ssandra, MinIO-Operator)
zu viel Infrastruktur — jedes bringt CRDs, eigene Controller und eigene
Upgrade-Pfade mit. Ein Umbrella-Chart mit Bitnami-Dependencies erfuellt den
Zweck mit einem einzigen `helm install`-Befehl.

Fuer echten Production-Betrieb verweist `values-prod.yaml.example` explizit
auf den Operator-Pfad.

## Weiterfuehrend

- Feature-Spec: [`docs/features/51-helm-deployment.md`](../../docs/features/51-helm-deployment.md)
