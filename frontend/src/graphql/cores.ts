import { gql } from "@apollo/client";

export const CONTEXT_CORES_QUERY = gql`
  query ContextCores {
    contextCores {
      coreId
      version
      sourceCollection
      createdAt
      createdBy
      description
      tags
      stats {
        quadCount
        entityCount
        chunkEmbeddingCount
        ontologyAxiomCount
      }
      embeddingModel
      embeddingDimension
      checksum
    }
  }
`;

export const BUILD_CONTEXT_CORE_MUTATION = gql`
  mutation BuildContextCore(
    $coreId: String!
    $version: String!
    $sourceCollection: String!
    $description: String
    $tags: [String!]
    $embeddingModel: String
    $embeddingDimension: Int
    $ontologyKey: String
  ) {
    buildContextCore(
      coreId: $coreId
      version: $version
      sourceCollection: $sourceCollection
      description: $description
      tags: $tags
      embeddingModel: $embeddingModel
      embeddingDimension: $embeddingDimension
      ontologyKey: $ontologyKey
    ) {
      coreId
      version
      checksum
      stats {
        quadCount
        entityCount
        chunkEmbeddingCount
      }
    }
  }
`;

export const IMPORT_CONTEXT_CORE_MUTATION = gql`
  mutation ImportContextCore(
    $coreId: String!
    $version: String!
    $targetCollection: String!
    $strategy: ConflictStrategy!
    $namespaceFrom: String
    $namespaceTo: String
  ) {
    importContextCore(
      coreId: $coreId
      version: $version
      targetCollection: $targetCollection
      strategy: $strategy
      namespaceFrom: $namespaceFrom
      namespaceTo: $namespaceTo
    ) {
      coreId
      version
      quadsImported
      embeddingsImported
    }
  }
`;

export const TAG_CONTEXT_CORE_MUTATION = gql`
  mutation TagContextCore($coreId: String!, $version: String!, $tag: String!) {
    tagContextCore(coreId: $coreId, version: $version, tag: $tag) {
      coreId
      version
      tags
    }
  }
`;

export const DELETE_CONTEXT_CORE_MUTATION = gql`
  mutation DeleteContextCore($coreId: String!, $version: String!) {
    deleteContextCore(coreId: $coreId, version: $version)
  }
`;
