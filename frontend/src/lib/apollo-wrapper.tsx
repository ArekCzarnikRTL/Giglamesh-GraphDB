"use client";

import { HttpLink, ApolloLink } from "@apollo/client";
import { GraphQLWsLink } from "@apollo/client/link/subscriptions";
import { createClient } from "graphql-ws";
import { OperationTypeNode } from "graphql";
import {
  ApolloNextAppProvider,
  ApolloClient,
  InMemoryCache,
} from "@apollo/client-integration-nextjs";

function makeClient() {
  const httpUri =
    process.env.NEXT_PUBLIC_GRAPHQL_URL ?? "http://localhost:8083/graphql";
  const wsUri =
    process.env.NEXT_PUBLIC_GRAPHQL_WS_URL ??
    httpUri.replace(/^http/, "ws");

  const httpLink = new HttpLink({ uri: httpUri });

  const link =
    typeof window === "undefined"
      ? httpLink
      : ApolloLink.split(
          ({ operationType }) =>
            operationType === OperationTypeNode.SUBSCRIPTION,
          new GraphQLWsLink(createClient({ url: wsUri })),
          httpLink,
        );

  return new ApolloClient({ cache: new InMemoryCache(), link });
}

export function ApolloWrapper({ children }: { children: React.ReactNode }) {
  return (
    <ApolloNextAppProvider makeClient={makeClient}>
      {children}
    </ApolloNextAppProvider>
  );
}
