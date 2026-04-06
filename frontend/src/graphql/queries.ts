import { gql } from "@apollo/client";

export const COLLECTIONS_QUERY = gql`
  query Collections {
    collections {
      id
      name
      description
    }
  }
`;

export const DOCUMENTS_QUERY = gql`
  query Documents(
    $collectionId: ID!
    $filter: DocumentFilter
    $page: Int
    $pageSize: Int
  ) {
    documents(
      collectionId: $collectionId
      filter: $filter
      page: $page
      pageSize: $pageSize
    ) {
      items {
        id
        collectionId
        parentId
        title
        type
        state
        mimeType
        createdAt
      }
      totalCount
      hasNextPage
    }
  }
`;

export const DOCUMENT_QUERY = gql`
  query Document($id: ID!) {
    document(id: $id) {
      id
      collectionId
      parentId
      title
      type
      state
      mimeType
      createdAt
      metadata {
        key
        value
      }
      children {
        id
        collectionId
        parentId
        title
        type
        state
        mimeType
        createdAt
      }
    }
  }
`;

export const DOCUMENT_CHUNKS_QUERY = gql`
  query DocumentChunks($documentId: ID!) {
    documentChunks(documentId: $documentId) {
      id
      title
      type
      metadata {
        key
        value
      }
    }
  }
`;

export const DOCUMENT_TRIPLES_QUERY = gql`
  query DocumentTriples($collectionId: ID!, $subject: String!) {
    triples(collectionId: $collectionId, subject: $subject) {
      subject
      predicate
      object
      objectType
    }
  }
`;
