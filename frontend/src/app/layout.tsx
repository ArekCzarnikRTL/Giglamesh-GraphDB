import type { Metadata } from "next";
import { Inter, JetBrains_Mono } from "next/font/google";
import "./globals.css";
import { ApolloWrapper } from "@/lib/apollo-wrapper";
import { SiteNav } from "@/components/SiteNav";
import { Toaster } from "@/components/ui/sonner";
import { cn } from "@/lib/utils";

const inter = Inter({
  subsets: ["latin"],
  variable: "--font-sans",
  display: "swap",
});

const jetBrainsMono = JetBrains_Mono({
  subsets: ["latin"],
  variable: "--font-mono",
  display: "swap",
});

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
    <html
      lang="de"
      className={cn("dark", inter.variable, jetBrainsMono.variable)}
    >
      <body className="flex h-screen flex-col overflow-hidden">
        <ApolloWrapper>
          <SiteNav />
          <div className="flex-1 min-h-0 overflow-auto">{children}</div>
          <Toaster />
        </ApolloWrapper>
      </body>
    </html>
  );
}
