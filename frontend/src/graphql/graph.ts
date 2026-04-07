import { gql } from "@apollo/client";

const QUAD_FIELDS = `
  subject
  predicate
  object
  dataset
  objectType
  datatype
  language
`;

export const GRAPH_TRIPLES_QUERY = gql`
  query GraphTriples(
    $collectionId: ID!
    $subject: String
    $predicate: String
    $object: String
    $dataset: String
    $limit: Int
  ) {
    triples(
      collectionId: $collectionId
      subject: $subject
      predicate: $predicate
      object: $object
      dataset: $dataset
      limit: $limit
    ) {
      ${QUAD_FIELDS}
    }
  }
`;

export const NODE_NEIGHBORS_QUERY = gql`
  query NodeNeighbors($collectionId: ID!, $entityUri: String!, $limit: Int) {
    asSubject: triples(collectionId: $collectionId, subject: $entityUri, limit: $limit) {
      ${QUAD_FIELDS}
    }
    asObject: triples(collectionId: $collectionId, object: $entityUri, limit: $limit) {
      ${QUAD_FIELDS}
    }
  }
`;

export const ENTITY_SEARCH_QUERY = gql`
  query EntitySearch($collectionId: ID!, $prefix: String!, $limit: Int) {
    entitySearch(collectionId: $collectionId, prefix: $prefix, limit: $limit)
  }
`;

export const GRAPH_METADATA_QUERY = gql`
  query GraphMetadata($collectionId: ID!) {
    graphMetadata(collectionId: $collectionId) {
      datasets
      predicates
      entityTypes
    }
  }
`;
