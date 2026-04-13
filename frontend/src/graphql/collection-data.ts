import { gql } from "@apollo/client";

export const COLLECTION_ONTOLOGIES_QUERY = gql`
  query CollectionOntologies($collectionId: ID!) {
    collectionOntologies(collectionId: $collectionId) {
      ontologyKey
      role
      assignedAt
      assignedBy
      ontology {
        key
        name
        namespace
        version
        classCount
        objectPropertyCount
        datatypePropertyCount
      }
    }
  }
`;

export const COLLECTION_DATA_STATS_QUERY = gql`
  query CollectionDataStats($collectionId: ID!) {
    collectionDataStats(collectionId: $collectionId) {
      tripleCount
      entityCount
      predicateCount
      datasets
    }
  }
`;

export const ASSIGN_ONTOLOGY_MUTATION = gql`
  mutation AssignOntology($collectionId: ID!, $ontologyKey: String!, $role: String!) {
    assignOntology(collectionId: $collectionId, ontologyKey: $ontologyKey, role: $role) {
      ontologyKey
      role
      assignedAt
      ontology {
        key
        name
        classCount
        objectPropertyCount
        datatypePropertyCount
      }
    }
  }
`;

export const UNASSIGN_ONTOLOGY_MUTATION = gql`
  mutation UnassignOntology($collectionId: ID!, $ontologyKey: String!) {
    unassignOntology(collectionId: $collectionId, ontologyKey: $ontologyKey)
  }
`;

export const DELETE_TRIPLES_MUTATION = gql`
  mutation DeleteTriples($collectionId: ID!, $dataset: String) {
    deleteTriples(collectionId: $collectionId, dataset: $dataset)
  }
`;
