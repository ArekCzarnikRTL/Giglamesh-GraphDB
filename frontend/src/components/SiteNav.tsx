"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";

const NAV_ITEMS = [
  { href: "/documents", label: "Dokumente" },
  { href: "/collections", label: "Collections" },
  { href: "/graph", label: "Graph" },
  { href: "/query", label: "Query" },
  { href: "/cores", label: "Cores" },
  { href: "/admin", label: "Admin" },
];

export function SiteNav() {
  const pathname = usePathname();

  return (
    <nav className="flex h-14 shrink-0 items-center gap-1 border-b bg-background px-6">
      <Link href="/documents" className="mr-6 text-lg font-bold">
        GraphMesh
      </Link>
      <ul className="flex items-center gap-1">
        {NAV_ITEMS.map((item) => {
          const active =
            pathname === item.href || pathname.startsWith(`${item.href}/`);
          return (
            <li key={item.href}>
              <Link
                href={item.href}
                className={cn(
                  "inline-flex h-9 items-center rounded-md px-3 text-sm font-medium transition-colors",
                  active
                    ? "bg-secondary text-secondary-foreground"
                    : "text-muted-foreground hover:bg-secondary/50 hover:text-foreground",
                )}
              >
                {item.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
