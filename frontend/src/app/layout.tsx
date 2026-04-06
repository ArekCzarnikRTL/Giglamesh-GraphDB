import type { Metadata } from "next";
import "./globals.css";
import { ApolloWrapper } from "@/lib/apollo-wrapper";
import { Toaster } from "@/components/ui/sonner";

export const metadata: Metadata = {
  title: "GraphMesh",
  description: "GraphMesh Document UI",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="de">
      <body>
        <ApolloWrapper>
          {children}
          <Toaster />
        </ApolloWrapper>
      </body>
    </html>
  );
}
