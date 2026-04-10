// frontend/src/graphql/admin.ts

import { gql } from "@apollo/client";

export const ADMIN_COLLECTIONS_QUERY = gql`
  query AdminCollections {
    collections {
      id
      name
      description
      tags
      metadata {
        key
        value
      }
      createdAt
      updatedAt
    }
  }
`;

export const ADMIN_COLLECTION_COUNTS_QUERY = gql`
  query AdminCollectionCounts(
    $collectionId: ID!
    $processingFilter: DocumentFilter
    $failedFilter: DocumentFilter
  ) {
    processing: documents(
      collectionId: $collectionId
      filter: $processingFilter
      page: 0
      pageSize: 1
    ) {
      totalCount
    }
    failed: documents(
      collectionId: $collectionId
      filter: $failedFilter
      page: 0
      pageSize: 1
    ) {
      totalCount
    }
  }
`;

export const CREATE_COLLECTION_MUTATION = gql`
  mutation CreateCollection($input: CreateCollectionInput!) {
    createCollection(input: $input) {
      id
      name
      description
      tags
    }
  }
`;

export const UPDATE_COLLECTION_MUTATION = gql`
  mutation UpdateCollection($id: ID!, $input: UpdateCollectionInput!) {
    updateCollection(id: $id, input: $input) {
      id
      name
      description
      tags
    }
  }
`;

export const DELETE_COLLECTION_MUTATION = gql`
  mutation DeleteCollection($id: ID!) {
    deleteCollection(id: $id)
  }
`;

export const CONFIG_KEYS_QUERY = gql`
  query AdminConfigKeys($type: String) {
    configKeys(type: $type) {
      id
      type
      key
      value
      version
    }
  }
`;

export const SET_CONFIG_MUTATION = gql`
  mutation AdminSetConfig($key: String!, $value: String!, $type: String!) {
    setConfig(key: $key, value: $value, type: $type) {
      id
      type
      key
      value
      version
    }
  }
`;

export const PURGE_ALL_DATA_MUTATION = gql`
  mutation PurgeAllData {
    purgeAllData {
      collectionsDeleted
      documentsDeleted
      ontologiesDeleted
      kafkaTopicsDeleted
      durationMs
    }
  }
`;

export const LIST_ONTOLOGIES_QUERY = gql`
  query ListOntologies {
    listOntologies {
      key
      name
      namespace
      version
      classCount
      objectPropertyCount
      datatypePropertyCount
    }
  }
`;

export const IMPORT_ONTOLOGY_MUTATION = gql`
  mutation ImportOntology($input: ImportOntologyInput!) {
    importOntology(input: $input) {
      key
      name
      classCount
      objectPropertyCount
      datatypePropertyCount
    }
  }
`;

export const DELETE_ONTOLOGY_MUTATION = gql`
  mutation DeleteOntology($key: String!) {
    deleteOntology(key: $key)
  }
`;

export const PIPELINE_DOCUMENTS_QUERY = gql`
  query PipelineDocuments(
    $collectionId: ID!
    $processingFilter: DocumentFilter
    $failedFilter: DocumentFilter
  ) {
    processing: documents(
      collectionId: $collectionId
      filter: $processingFilter
      page: 0
      pageSize: 50
    ) {
      items {
        id
        collectionId
        title
        state
        createdAt
      }
      totalCount
    }
    failed: documents(
      collectionId: $collectionId
      filter: $failedFilter
      page: 0
      pageSize: 50
    ) {
      items {
        id
        collectionId
        title
        state
        createdAt
      }
      totalCount
    }
  }
`;
