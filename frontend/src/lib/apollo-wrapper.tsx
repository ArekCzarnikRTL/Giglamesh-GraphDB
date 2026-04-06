"use client";

import { HttpLink } from "@apollo/client";
import {
  ApolloNextAppProvider,
  ApolloClient,
  InMemoryCache,
} from "@apollo/client-integration-nextjs";

function makeClient() {
  const httpLink = new HttpLink({
    uri:
      process.env.NEXT_PUBLIC_GRAPHQL_URL ?? "http://localhost:8080/graphql",
  });

  return new ApolloClient({
    cache: new InMemoryCache(),
    link: httpLink,
  });
}

export function ApolloWrapper({ children }: { children: React.ReactNode }) {
  return (
    <ApolloNextAppProvider makeClient={makeClient}>
      {children}
    </ApolloNextAppProvider>
  );
}
