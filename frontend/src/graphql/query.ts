// frontend/src/graphql/query.ts
import { gql } from "@apollo/client";

export const GRAPH_RAG_QUERY = gql`
  query GraphRag($input: GraphRagInput!) {
    graphRag(input: $input) {
      sessionId
      answer
      selectedEdges {
        subject
        predicate
        objectValue
        dataset
        reasoning
        relevanceScore
      }
      retrievedEdgeCount
      durationMs
    }
  }
`;

export const DOCUMENT_RAG_QUERY = gql`
  query DocumentRag($input: DocumentRagInput!) {
    documentRag(input: $input) {
      sessionId
      answer
      sources {
        chunkId
        documentId
        documentTitle
        pageNumber
        score
        snippet
      }
      retrievedChunkCount
      durationMs
    }
  }
`;

export const NLP_QUERY = gql`
  query NlpQuery($input: NlpQueryInput!) {
    nlpQuery(input: $input) {
      answer
      detectedIntent {
        intent
        confidence
        reasoning
      }
      wasReformulated
      effectiveQuestion
      durationMs
      sources
    }
  }
`;

export const AGENT_STREAM_SUBSCRIPTION = gql`
  subscription AgentStream($input: AgentStreamInput!) {
    agentStream(input: $input) {
      content
      type
      endOfMessage
      endOfStream
    }
  }
`;
