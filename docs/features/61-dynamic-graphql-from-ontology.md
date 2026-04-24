# Feature 61: Dynamic GraphQL from Ontology

## Status: done

## Beschreibung

Automatische Generierung eines GraphQL-Endpoints pro Collection basierend auf der zugewiesenen Ontologie. Beim RDF/TTL-Import wird aus den `OntologyClass`-, `ObjectProperty`- und `DatatypeProperty`-Definitionen ein typisiertes GraphQL-Schema gebaut, das unter `/graphql/{collectionName}` erreichbar ist und die gespeicherten Triples als Graph-Daten aufloest.

## Kern-Features

- Separater GraphQL-Endpoint pro Collection (`/graphql/{collectionName}`)
- Schema-Generierung aus zugewiesener Ontologie (OntologyClass → ObjectType, Properties → Fields)
- Graph-Traversal ueber ObjectProperties mit verschachtelter Paginierung und Filterung
- Umfangreiches XSD → GraphQL Scalar Mapping (inkl. Custom Scalars: Date, DateTime, Long)
- Automatisches Schema-Update bei jedem `importRdf()`
- Batch-Loading via DataLoaderRegistry zur N+1-Vermeidung

## Technologie

- Pure graphql-java (programmatische Schema-API)
- graphql-java-extended-scalars fuer Custom Scalars
- Kein DGS, kein Eingriff in bestehendes Spring GraphQL

## Package

`com.agentwork.graphmesh.dynamicgraphql`

## Design-Spec

`docs/superpowers/specs/2026-04-24-dynamic-graphql-from-ontology-design.md`
